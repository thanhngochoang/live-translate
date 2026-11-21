use ort::environment::Environment;
use ort::session::Session;
use ort::value::Tensor;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_void};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::mpsc;
use voicetrans_core::session::{Utterance, UtteranceKind};
use voicetrans_core::translate::{CjkRatioLanguageDetector, LanguageDetector, SourceLang};

use crate::model_manager;

#[derive(Debug, Clone)]
pub enum PipelineEvent {
    Partial(String),
    Utterance(Utterance),
    Warning(String),
}

pub fn diag_inspect_models(models_dir: &Path) {
    let ort_path = model_manager::resolve_ort_lib_path(models_dir);
    let sherpa_path = model_manager::resolve_sherpa_c_api_path(models_dir);
    eprintln!("[diag-pipeline] ort_path={} exists={}", ort_path.display(), ort_path.exists());
    eprintln!("[diag-pipeline] sherpa_path={} exists={}", sherpa_path.display(), sherpa_path.exists());

    let env = match ort::init_from(&ort_path)
        .map_err(|e| e.to_string())
        .and_then(|b| b.with_name("voicetrans-diag").build().map_err(|e| e.to_string()))
    {
        Ok(e) => e,
        Err(err) => {
            eprintln!("[diag-pipeline] ort init error: {err}");
            return;
        }
    };

    let vad_path = models_dir.join("vad/silero_vad.onnx");
    eprintln!("[diag-pipeline] loading VAD {} (exists={})", vad_path.display(), vad_path.exists());
    match Session::builder(&env).and_then(|mut b| b.commit_from_file(&vad_path)) {
        Ok(sess) => {
            for inp in sess.inputs() {
                eprintln!("  IN  name={} type={:?}", inp.name(), inp.dtype());
            }
            for out in sess.outputs() {
                eprintln!("  OUT name={} type={:?}", out.name(), out.dtype());
            }
        }
        Err(e) => eprintln!("  LOAD ERR: {e}"),
    }

    let encoder_path = models_dir.join("asr/encoder-epoch-99-avg-1.int8.onnx");
    let decoder_path = models_dir.join("asr/decoder-epoch-99-avg-1.onnx");
    let joiner_path = models_dir.join("asr/joiner-epoch-99-avg-1.int8.onnx");
    let tokens_path = models_dir.join("asr/tokens.txt");

    match SherpaStreamingZipformer::load(
        &sherpa_path,
        &encoder_path,
        &decoder_path,
        &joiner_path,
        &tokens_path,
    ) {
        Ok(mut asr) => {
            let fake_audio: Vec<f32> = (0..16000).map(|i| (i as f32 * 0.05).sin() * 0.05).collect();
            asr.accept_waveform(&fake_audio);
            asr.decode_all_ready();
            eprintln!("[diag-pipeline] Zipformer init & decode test OK: result='{}'", asr.get_text());
        }
        Err(e) => eprintln!("[diag-pipeline] Zipformer load err: {e}"),
    }
}

pub fn spawn_asr_translate_pipeline(
    models_dir: std::path::PathBuf,
    target_lang: String,
    running: Arc<AtomicBool>,
    mut audio_rx: mpsc::Receiver<Vec<f32>>,
    event_tx: std::sync::mpsc::Sender<PipelineEvent>,
    on_wake: impl Fn() + Send + 'static,
) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        let ort_path = model_manager::resolve_ort_lib_path(&models_dir);
        let env = match ort::init_from(&ort_path)
            .map_err(|e| e.to_string())
            .and_then(|b| b.with_name("voicetrans").build().map_err(|e| e.to_string()))
        {
            Ok(env) => env,
            Err(e) => {
                let _ = event_tx.send(PipelineEvent::Warning(format!(
                    "Không nạp được ONNX Runtime: {e}"
                )));
                on_wake();
                return;
            }
        };

        let vad_path = models_dir.join("vad/silero_vad.onnx");
        let mut vad = match SileroVad::load(&env, &vad_path) {
            Ok(v) => Some(v),
            Err(e) => {
                let _ = event_tx.send(PipelineEvent::Warning(format!("Lỗi nạp VAD: {e}")));
                on_wake();
                None
            }
        };

        let sherpa_path = model_manager::resolve_sherpa_c_api_path(&models_dir);
        let encoder_path = models_dir.join("asr/encoder-epoch-99-avg-1.int8.onnx");
        let decoder_path = models_dir.join("asr/decoder-epoch-99-avg-1.onnx");
        let joiner_path = models_dir.join("asr/joiner-epoch-99-avg-1.int8.onnx");
        let tokens_path = models_dir.join("asr/tokens.txt");

        let mut asr = match SherpaStreamingZipformer::load(
            &sherpa_path,
            &encoder_path,
            &decoder_path,
            &joiner_path,
            &tokens_path,
        ) {
            Ok(a) => a,
            Err(e) => {
                let _ = event_tx.send(PipelineEvent::Warning(format!(
                    "Lỗi nạp Sherpa Streaming Zipformer ASR: {e}"
                )));
                on_wake();
                return;
            }
        };

        let lang_det = CjkRatioLanguageDetector;
        let mut pcm_ring: Vec<f32> = Vec::with_capacity(16000);
        let mut in_speech = false;
        let mut silence_frames = 0usize;
        let mut last_partial = String::new();
        let mut next_id: i64 = 1;

        while running.load(Ordering::Relaxed) {
            match audio_rx.blocking_recv() {
                Some(chunk) => {
                    pcm_ring.extend_from_slice(&chunk);
                }
                None => break,
            }
            while let Ok(more) = audio_rx.try_recv() {
                pcm_ring.extend_from_slice(&more);
            }

            while pcm_ring.len() >= 512 {
                let frame: Vec<f32> = pcm_ring.drain(..512).collect();
                let is_speech = if let Some(v) = vad.as_mut() {
                    v.is_speech(&frame).unwrap_or_else(|_| rms_energy(&frame) > 0.012)
                } else {
                    rms_energy(&frame) > 0.012
                };

                if is_speech {
                    if !in_speech {
                        in_speech = true;
                    }
                    silence_frames = 0;
                    asr.accept_waveform(&frame);
                    asr.decode_all_ready();

                    let partial = asr.get_text();
                    let trimmed = partial.trim().to_string();
                    if !trimmed.is_empty() && trimmed != last_partial {
                        last_partial = trimmed.clone();
                        let _ = event_tx.send(PipelineEvent::Partial(trimmed));
                        on_wake();
                    }
                } else if in_speech {
                    silence_frames += 1;
                    asr.accept_waveform(&frame);
                    asr.decode_all_ready();

                    let is_ep = asr.is_endpoint() || silence_frames >= 22; // ~700ms silence
                    if is_ep {
                        asr.input_finished();
                        asr.decode_all_ready();
                        let final_text = asr.get_text().trim().to_string();

                        in_speech = false;
                        silence_frames = 0;
                        last_partial.clear();
                        asr.reset();
                        if let Some(v) = vad.as_mut() {
                            v.reset();
                        }

                        if !final_text.is_empty() {
                            let src_lang = lang_det.detect(&final_text);
                            let id = next_id;
                            next_id += 1;
                            let translation = translate_text(&final_text, src_lang, &target_lang);
                            let utt = Utterance {
                                id,
                                kind: UtteranceKind::Speech,
                                source: final_text,
                                source_lang: src_lang,
                                target_lang: target_lang.clone(),
                                translation: translation.ok(),
                                translation_failed: false,
                                timestamp_ms: 0,
                                end_ms: 0,
                            };
                            let _ = event_tx.send(PipelineEvent::Utterance(utt));
                            on_wake();
                        }
                    } else {
                        let partial = asr.get_text();
                        let trimmed = partial.trim().to_string();
                        if !trimmed.is_empty() && trimmed != last_partial {
                            last_partial = trimmed.clone();
                            let _ = event_tx.send(PipelineEvent::Partial(trimmed));
                            on_wake();
                        }
                    }
                }
            }
        }
    })
}

fn rms_energy(samples: &[f32]) -> f32 {
    if samples.is_empty() {
        return 0.0;
    }
    let sum: f32 = samples.iter().map(|s| s * s).sum();
    (sum / samples.len() as f32).sqrt()
}

struct SileroVad {
    session: Session,
    h: Vec<f32>,
    c: Vec<f32>,
    state_dim: usize,
    is_v5: bool,
    has_x_input: bool,
}

impl SileroVad {
    fn load(env: &Environment, path: &Path) -> Result<Self, String> {
        let session = Session::builder(env)
            .map_err(|e| e.to_string())?
            .with_intra_threads(1)
            .map_err(|e| e.to_string())?
            .commit_from_file(path)
            .map_err(|e| e.to_string())?;

        let is_v5 = session.inputs().iter().any(|i| i.name() == "state");
        let has_x_input = session.inputs().iter().any(|i| i.name() == "x");
        let state_dim = if is_v5 { 128 } else { 64 };
        let h = vec![0.0f32; 2 * state_dim];
        let c = vec![0.0f32; 2 * state_dim];
        Ok(Self {
            session,
            h,
            c,
            state_dim,
            is_v5,
            has_x_input,
        })
    }

    fn reset(&mut self) {
        self.h.fill(0.0);
        self.c.fill(0.0);
    }

    fn is_speech(&mut self, chunk: &[f32]) -> Result<bool, String> {
        let n = chunk.len();
        if n != 512 {
            return Ok(false);
        }
        let t_audio = Tensor::from_array(([1usize, n], chunk.to_vec())).map_err(|e| e.to_string())?;
        let t_sr = Tensor::from_array(([1usize], vec![16000i64])).map_err(|e| e.to_string())?;

        let prob = if self.is_v5 {
            let t_state = Tensor::from_array(([2usize, 1, self.state_dim], self.h.clone()))
                .map_err(|e| e.to_string())?;
            let outputs = self
                .session
                .run(ort::inputs![
                    "input" => t_audio,
                    "state" => t_state,
                    "sr" => t_sr
                ])
                .map_err(|e| e.to_string())?;
            let (_shape, out_state) = outputs[1]
                .try_extract_tensor::<f32>()
                .map_err(|e| e.to_string())?;
            let h_len = self.h.len();
            if out_state.len() >= h_len {
                self.h.copy_from_slice(&out_state[..h_len]);
            }
            let (_p_shape, p_data) = outputs[0]
                .try_extract_tensor::<f32>()
                .map_err(|e| e.to_string())?;
            p_data.first().copied().unwrap_or(0.0)
        } else {
            let t_h = Tensor::from_array(([2usize, 1, self.state_dim], self.h.clone()))
                .map_err(|e| e.to_string())?;
            let t_c = Tensor::from_array(([2usize, 1, self.state_dim], self.c.clone()))
                .map_err(|e| e.to_string())?;
            let audio_input_name = if self.has_x_input { "x" } else { "input" };
            let outputs = self
                .session
                .run(ort::inputs![
                    audio_input_name => t_audio,
                    "sr" => t_sr,
                    "h" => t_h,
                    "c" => t_c
                ])
                .map_err(|e| e.to_string())?;

            let (_h_shape, out_h) = outputs[1]
                .try_extract_tensor::<f32>()
                .map_err(|e| e.to_string())?;
            let (_c_shape, out_c) = outputs[2]
                .try_extract_tensor::<f32>()
                .map_err(|e| e.to_string())?;
            let h_len = self.h.len();
            if out_h.len() >= h_len {
                self.h.copy_from_slice(&out_h[..h_len]);
            }
            let c_len = self.c.len();
            if out_c.len() >= c_len {
                self.c.copy_from_slice(&out_c[..c_len]);
            }
            let (_p_shape, p_data) = outputs[0]
                .try_extract_tensor::<f32>()
                .map_err(|e| e.to_string())?;
            p_data.first().copied().unwrap_or(0.0)
        };

        Ok(prob >= 0.40)
    }
}

#[repr(C)]
struct SherpaOnnxFeatureConfig {
    sample_rate: i32,
    feature_dim: i32,
}

#[repr(C)]
struct SherpaOnnxOnlineTransducerModelConfig {
    encoder: *const c_char,
    decoder: *const c_char,
    joiner: *const c_char,
}

#[repr(C)]
struct SherpaOnnxOnlineParaformerModelConfig {
    encoder: *const c_char,
    decoder: *const c_char,
}

#[repr(C)]
struct SherpaOnnxOnlineZipformer2CtcModelConfig {
    model: *const c_char,
}

#[repr(C)]
struct SherpaOnnxOnlineModelConfig {
    transducer: SherpaOnnxOnlineTransducerModelConfig,
    paraformer: SherpaOnnxOnlineParaformerModelConfig,
    zipformer2_ctc: SherpaOnnxOnlineZipformer2CtcModelConfig,
    tokens: *const c_char,
    num_threads: i32,
    provider: *const c_char,
    debug: i32,
    model_type: *const c_char,
    modeling_unit: *const c_char,
    bpe_vocab: *const c_char,
    tokens_buf: *const c_char,
    tokens_buf_size: i32,
    nemo_ctc_model: *const c_char,
    t_one_ctc_model: *const c_char,
}

#[repr(C)]
struct SherpaOnnxOnlineCtcFstDecoderConfig {
    graph: *const c_char,
    max_active: i32,
}

#[repr(C)]
struct SherpaOnnxHomophoneReplacerConfig {
    dict_dir: *const c_char,
    lexicon: *const c_char,
    rule_fsts: *const c_char,
}

#[repr(C)]
struct SherpaOnnxOnlineRecognizerConfig {
    feat_config: SherpaOnnxFeatureConfig,
    model_config: SherpaOnnxOnlineModelConfig,
    decoding_method: *const c_char,
    max_active_paths: i32,
    enable_endpoint: i32,
    rule1_min_trailing_silence: f32,
    rule2_min_trailing_silence: f32,
    rule3_min_utterance_length: f32,
    hotwords_file: *const c_char,
    hotwords_score: f32,
    ctc_fst_decoder_config: SherpaOnnxOnlineCtcFstDecoderConfig,
    rule_fsts: *const c_char,
    rule_fars: *const c_char,
    blank_penalty: f32,
    hotwords_buf: *const c_char,
    hotwords_buf_size: i32,
    hr: SherpaOnnxHomophoneReplacerConfig,
}

#[repr(C)]
struct SherpaOnnxOnlineRecognizerResult {
    text: *const c_char,
    tokens: *const c_char,
    tokens_arr: *const *const c_char,
    timestamps: *mut f32,
    count: i32,
    json: *const c_char,
}

type FnCreateOnlineRecognizer =
    unsafe extern "C" fn(*const SherpaOnnxOnlineRecognizerConfig) -> *mut c_void;
type FnDestroyOnlineRecognizer = unsafe extern "C" fn(*mut c_void);
type FnCreateOnlineStream = unsafe extern "C" fn(*mut c_void) -> *mut c_void;
type FnDestroyOnlineStream = unsafe extern "C" fn(*mut c_void);
type FnOnlineStreamAcceptWaveform =
    unsafe extern "C" fn(*mut c_void, i32, *const f32, i32);
type FnIsOnlineStreamReady = unsafe extern "C" fn(*mut c_void, *mut c_void) -> i32;
type FnDecodeOnlineStream = unsafe extern "C" fn(*mut c_void, *mut c_void);
type FnOnlineStreamIsEndpoint = unsafe extern "C" fn(*mut c_void, *mut c_void) -> i32;
type FnOnlineStreamReset = unsafe extern "C" fn(*mut c_void, *mut c_void);
type FnOnlineStreamInputFinished = unsafe extern "C" fn(*mut c_void);
type FnGetOnlineStreamResult =
    unsafe extern "C" fn(*mut c_void, *mut c_void) -> *const SherpaOnnxOnlineRecognizerResult;
type FnDestroyOnlineRecognizerResult =
    unsafe extern "C" fn(*const SherpaOnnxOnlineRecognizerResult);

pub struct SherpaStreamingZipformer {
    _lib: Arc<libloading::Library>,
    recognizer: *mut c_void,
    stream: *mut c_void,
    fn_destroy_recognizer: FnDestroyOnlineRecognizer,
    fn_destroy_stream: FnDestroyOnlineStream,
    fn_accept_waveform: FnOnlineStreamAcceptWaveform,
    fn_is_ready: FnIsOnlineStreamReady,
    fn_decode: FnDecodeOnlineStream,
    fn_is_endpoint: FnOnlineStreamIsEndpoint,
    fn_reset: FnOnlineStreamReset,
    fn_input_finished: FnOnlineStreamInputFinished,
    fn_get_result: FnGetOnlineStreamResult,
    fn_destroy_result: FnDestroyOnlineRecognizerResult,
}

unsafe impl Send for SherpaStreamingZipformer {}

impl SherpaStreamingZipformer {
    pub fn load(
        lib_path: &Path,
        encoder_path: &Path,
        decoder_path: &Path,
        joiner_path: &Path,
        tokens_path: &Path,
    ) -> Result<Self, String> {
        let lib = unsafe {
            libloading::Library::new(lib_path)
                .map_err(|e| format!("Không thể nạp thư viện {}: {e}", lib_path.display()))?
        };
        let lib = Arc::new(lib);

        let fn_create_recognizer: FnCreateOnlineRecognizer = unsafe {
            *lib.get(b"SherpaOnnxCreateOnlineRecognizer")
                .map_err(|e| e.to_string())?
        };
        let fn_destroy_recognizer: FnDestroyOnlineRecognizer = unsafe {
            *lib.get(b"SherpaOnnxDestroyOnlineRecognizer")
                .map_err(|e| e.to_string())?
        };
        let fn_create_stream: FnCreateOnlineStream = unsafe {
            *lib.get(b"SherpaOnnxCreateOnlineStream")
                .map_err(|e| e.to_string())?
        };
        let fn_destroy_stream: FnDestroyOnlineStream = unsafe {
            *lib.get(b"SherpaOnnxDestroyOnlineStream")
                .map_err(|e| e.to_string())?
        };
        let fn_accept_waveform: FnOnlineStreamAcceptWaveform = unsafe {
            *lib.get(b"SherpaOnnxOnlineStreamAcceptWaveform")
                .map_err(|e| e.to_string())?
        };
        let fn_is_ready: FnIsOnlineStreamReady = unsafe {
            *lib.get(b"SherpaOnnxIsOnlineStreamReady")
                .map_err(|e| e.to_string())?
        };
        let fn_decode: FnDecodeOnlineStream = unsafe {
            *lib.get(b"SherpaOnnxDecodeOnlineStream")
                .map_err(|e| e.to_string())?
        };
        let fn_is_endpoint: FnOnlineStreamIsEndpoint = unsafe {
            *lib.get(b"SherpaOnnxOnlineStreamIsEndpoint")
                .map_err(|e| e.to_string())?
        };
        let fn_reset: FnOnlineStreamReset = unsafe {
            *lib.get(b"SherpaOnnxOnlineStreamReset")
                .map_err(|e| e.to_string())?
        };
        let fn_input_finished: FnOnlineStreamInputFinished = unsafe {
            *lib.get(b"SherpaOnnxOnlineStreamInputFinished")
                .map_err(|e| e.to_string())?
        };
        let fn_get_result: FnGetOnlineStreamResult = unsafe {
            *lib.get(b"SherpaOnnxGetOnlineStreamResult")
                .map_err(|e| e.to_string())?
        };
        let fn_destroy_result: FnDestroyOnlineRecognizerResult = unsafe {
            *lib.get(b"SherpaOnnxDestroyOnlineRecognizerResult")
                .map_err(|e| e.to_string())?
        };

        let c_encoder = CString::new(encoder_path.to_string_lossy().as_bytes())
            .map_err(|e| e.to_string())?;
        let c_decoder = CString::new(decoder_path.to_string_lossy().as_bytes())
            .map_err(|e| e.to_string())?;
        let c_joiner = CString::new(joiner_path.to_string_lossy().as_bytes())
            .map_err(|e| e.to_string())?;
        let c_tokens = CString::new(tokens_path.to_string_lossy().as_bytes())
            .map_err(|e| e.to_string())?;
        let c_provider = CString::new("cpu").unwrap();
        let c_model_type = CString::new("zipformer").unwrap();
        let c_method = CString::new("greedy_search").unwrap();

        let mut config: SherpaOnnxOnlineRecognizerConfig = unsafe { std::mem::zeroed() };
        config.feat_config.sample_rate = 16000;
        config.feat_config.feature_dim = 80;
        config.model_config.transducer.encoder = c_encoder.as_ptr();
        config.model_config.transducer.decoder = c_decoder.as_ptr();
        config.model_config.transducer.joiner = c_joiner.as_ptr();
        config.model_config.tokens = c_tokens.as_ptr();
        config.model_config.num_threads = 2;
        config.model_config.provider = c_provider.as_ptr();
        config.model_config.model_type = c_model_type.as_ptr();
        config.decoding_method = c_method.as_ptr();
        config.enable_endpoint = 1;
        config.rule1_min_trailing_silence = 2.4;
        config.rule2_min_trailing_silence = 0.8;
        config.rule3_min_utterance_length = 20.0;

        let recognizer = unsafe { fn_create_recognizer(&config) };
        if recognizer.is_null() {
            return Err("SherpaOnnxCreateOnlineRecognizer trả về NULL".into());
        }

        let stream = unsafe { fn_create_stream(recognizer) };
        if stream.is_null() {
            unsafe { fn_destroy_recognizer(recognizer) };
            return Err("SherpaOnnxCreateOnlineStream trả về NULL".into());
        }

        Ok(Self {
            _lib: lib,
            recognizer,
            stream,
            fn_destroy_recognizer,
            fn_destroy_stream,
            fn_accept_waveform,
            fn_is_ready,
            fn_decode,
            fn_is_endpoint,
            fn_reset,
            fn_input_finished,
            fn_get_result,
            fn_destroy_result,
        })
    }

    pub fn accept_waveform(&mut self, samples: &[f32]) {
        if !self.stream.is_null() && !samples.is_empty() {
            unsafe {
                (self.fn_accept_waveform)(
                    self.stream,
                    16000,
                    samples.as_ptr(),
                    samples.len() as i32,
                );
            }
        }
    }

    pub fn decode_all_ready(&mut self) {
        if !self.recognizer.is_null() && !self.stream.is_null() {
            unsafe {
                while (self.fn_is_ready)(self.recognizer, self.stream) != 0 {
                    (self.fn_decode)(self.recognizer, self.stream);
                }
            }
        }
    }

    pub fn get_text(&self) -> String {
        if self.recognizer.is_null() || self.stream.is_null() {
            return String::new();
        }
        unsafe {
            let res = (self.fn_get_result)(self.recognizer, self.stream);
            if res.is_null() {
                return String::new();
            }
            let text = if (*res).text.is_null() {
                String::new()
            } else {
                CStr::from_ptr((*res).text).to_string_lossy().to_string()
            };
            (self.fn_destroy_result)(res);
            text
        }
    }

    pub fn is_endpoint(&self) -> bool {
        if self.recognizer.is_null() || self.stream.is_null() {
            false
        } else {
            unsafe { (self.fn_is_endpoint)(self.recognizer, self.stream) != 0 }
        }
    }

    pub fn input_finished(&mut self) {
        if !self.stream.is_null() {
            unsafe { (self.fn_input_finished)(self.stream) };
        }
    }

    pub fn reset(&mut self) {
        if !self.recognizer.is_null() && !self.stream.is_null() {
            unsafe { (self.fn_reset)(self.recognizer, self.stream) };
        }
    }
}

impl Drop for SherpaStreamingZipformer {
    fn drop(&mut self) {
        if !self.stream.is_null() {
            unsafe { (self.fn_destroy_stream)(self.stream) };
            self.stream = std::ptr::null_mut();
        }
        if !self.recognizer.is_null() {
            unsafe { (self.fn_destroy_recognizer)(self.recognizer) };
            self.recognizer = std::ptr::null_mut();
        }
    }
}

fn translate_text(text: &str, src: SourceLang, target: &str) -> Result<String, String> {
    let sl = match src {
        SourceLang::En => "en",
        SourceLang::Zh => "zh-CN",
    };
    if sl == target {
        return Ok(text.to_string());
    }
    let encoded: String = url_encode(text);
    let url = format!(
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl={sl}&tl={target}&dt=t&q={encoded}"
    );
    let connector = native_tls::TlsConnector::new().map_err(|e| e.to_string())?;
    let agent = ureq::AgentBuilder::new()
        .tls_connector(Arc::new(connector))
        .build();
    let resp = agent
        .get(&url)
        .timeout(std::time::Duration::from_secs(4))
        .call()
        .map_err(|e| e.to_string())?;
    let body = resp.into_string().map_err(|e| e.to_string())?;
    let val: serde_json::Value = serde_json::from_str(&body).map_err(|e| e.to_string())?;
    let mut out = String::new();
    if let Some(arr) = val.get(0).and_then(|v| v.as_array()) {
        for item in arr {
            if let Some(seg) = item.get(0).and_then(|v| v.as_str()) {
                out.push_str(seg);
            }
        }
    }
    if out.is_empty() {
        Err("Empty translation".into())
    } else {
        Ok(out)
    }
}

fn url_encode(s: &str) -> String {
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.as_bytes() {
        match *b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char);
            }
            _ => {
                out.push_str(&format!("%{:02X}", b));
            }
        }
    }
    out
}
