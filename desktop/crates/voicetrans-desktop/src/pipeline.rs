use ort::environment::Environment;
use ort::session::Session;
use ort::value::Tensor;
use std::collections::HashMap;
use std::f32::consts::PI;
use std::fs;
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
    eprintln!("[diag-pipeline] ort_path={} exists={}", ort_path.display(), ort_path.exists());
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

    for rel in ["vad/silero_vad.onnx", "asr-sv/model.int8.onnx"] {
        let p = models_dir.join(rel);
        eprintln!("[diag-pipeline] loading {} (exists={})", p.display(), p.exists());
        match Session::builder(&env).and_then(|mut b| b.commit_from_file(&p)) {
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
    }

    // Test a 1-second silent/sine buffer through SenseVoiceAsr
    match SenseVoiceAsr::load(&env, &models_dir.join("asr-sv/model.int8.onnx"), &models_dir.join("asr-sv/tokens.txt")) {
        Ok(mut asr) => {
            let fake_audio: Vec<f32> = (0..16000).map(|i| (i as f32 * 0.05).sin() * 0.1).collect();
            eprintln!("[diag-pipeline] SenseVoice transcribe test: {:?}", asr.transcribe(&fake_audio));
        }
        Err(e) => eprintln!("[diag-pipeline] SenseVoice load err: {e}"),
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
        let asr_path = models_dir.join("asr-sv/model.int8.onnx");
        let tokens_path = models_dir.join("asr-sv/tokens.txt");

        let mut vad = match SileroVad::load(&env, &vad_path) {
            Ok(v) => Some(v),
            Err(e) => {
                let _ = event_tx.send(PipelineEvent::Warning(format!("Lỗi nạp VAD: {e}")));
                on_wake();
                None
            }
        };

        let mut asr = match SenseVoiceAsr::load(&env, &asr_path, &tokens_path) {
            Ok(a) => a,
            Err(e) => {
                let _ = event_tx.send(PipelineEvent::Warning(format!(
                    "Lỗi nạp SenseVoice ASR: {e}"
                )));
                on_wake();
                return;
            }
        };

        let lang_det = CjkRatioLanguageDetector;
        let mut pcm_ring: Vec<f32> = Vec::with_capacity(16000);
        let mut speech_buf: Vec<f32> = Vec::with_capacity(16000 * 10);
        let mut in_speech = false;
        let mut silence_frames = 0usize;
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
                        let _ = event_tx.send(PipelineEvent::Partial("Đang nghe câu nói...".into()));
                        on_wake();
                    }
                    silence_frames = 0;
                    speech_buf.extend_from_slice(&frame);
                } else if in_speech {
                    speech_buf.extend_from_slice(&frame);
                    silence_frames += 1;

                    // 600 ms silence (~19 frames of 32ms) or 10 seconds max segment
                    if silence_frames >= 19 || speech_buf.len() >= 16000 * 10 {
                        let segment = std::mem::take(&mut speech_buf);
                        in_speech = false;
                        silence_frames = 0;
                        if let Some(v) = vad.as_mut() {
                            v.reset();
                        }

                        if segment.len() >= 4800 {
                            if let Ok(text) = asr.transcribe(&segment) {
                                let trimmed = text.trim().to_string();
                                if !trimmed.is_empty() {
                                    let src_lang = lang_det.detect(&trimmed);
                                    let id = next_id;
                                    next_id += 1;
                                    let translation = translate_text(&trimmed, src_lang, &target_lang);
                                    let utt = Utterance {
                                        id,
                                        kind: UtteranceKind::Speech,
                                        source: trimmed,
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
                            }
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

    fn is_speech(&mut self, frame_512: &[f32]) -> Result<bool, String> {
        let t_in = Tensor::from_array(([1usize, frame_512.len()], frame_512.to_vec()))
            .map_err(|e| e.to_string())?;
        let t_h = Tensor::from_array(([2usize, 1, self.state_dim], self.h.clone()))
            .map_err(|e| e.to_string())?;

        let outputs = if self.is_v5 {
            let t_sr = Tensor::from_array(([1usize], vec![16000i64])).map_err(|e| e.to_string())?;
            self.session
                .run(ort::inputs!["input" => t_in, "state" => t_h, "sr" => t_sr])
                .map_err(|e| e.to_string())?
        } else if self.has_x_input {
            let t_c = Tensor::from_array(([2usize, 1, self.state_dim], self.c.clone()))
                .map_err(|e| e.to_string())?;
            self.session
                .run(ort::inputs!["x" => t_in, "h" => t_h, "c" => t_c])
                .map_err(|e| e.to_string())?
        } else {
            let t_sr = Tensor::from_array(([1usize], vec![16000i64])).map_err(|e| e.to_string())?;
            let t_c = Tensor::from_array(([2usize, 1, self.state_dim], self.c.clone()))
                .map_err(|e| e.to_string())?;
            self.session
                .run(ort::inputs!["input" => t_in, "sr" => t_sr, "h" => t_h, "c" => t_c])
                .map_err(|e| e.to_string())?
        };

        let (_, prob_slice) = outputs[0]
            .try_extract_tensor::<f32>()
            .map_err(|e| e.to_string())?;
        let prob = prob_slice.first().copied().unwrap_or(0.0);

        if outputs.len() > 1 {
            if let Ok((_shape, state_slice)) = outputs[1].try_extract_tensor::<f32>() {
                if state_slice.len() == self.h.len() {
                    self.h.copy_from_slice(state_slice);
                }
            }
        }
        if outputs.len() > 2 {
            if let Ok((_shape, c_slice)) = outputs[2].try_extract_tensor::<f32>() {
                if c_slice.len() == self.c.len() {
                    self.c.copy_from_slice(c_slice);
                }
            }
        }

        Ok(prob >= 0.40)
    }
}

struct SenseVoiceAsr {
    session: Session,
    tokens: HashMap<usize, String>,
    mel_bank: Vec<Vec<f32>>,
}

impl SenseVoiceAsr {
    fn load(env: &Environment, model_path: &Path, tokens_path: &Path) -> Result<Self, String> {
        let session = Session::builder(env)
            .map_err(|e| e.to_string())?
            .with_intra_threads(2)
            .map_err(|e| e.to_string())?
            .commit_from_file(model_path)
            .map_err(|e| e.to_string())?;

        let raw_tokens = fs::read_to_string(tokens_path).map_err(|e| e.to_string())?;
        let mut tokens = HashMap::new();
        for line in raw_tokens.lines() {
            let mut parts = line.rsplitn(2, char::is_whitespace);
            if let (Some(id_str), Some(tok)) = (parts.next(), parts.next()) {
                if let Ok(id) = id_str.trim().parse::<usize>() {
                    tokens.insert(id, tok.to_string());
                }
            }
        }

        let mel_bank = build_mel_filterbank(80, 512, 16000.0, 20.0, 7600.0);
        Ok(Self {
            session,
            tokens,
            mel_bank,
        })
    }

    fn transcribe(&mut self, samples: &[f32]) -> Result<String, String> {
        let fbanks = compute_fbank_80(samples, &self.mel_bank);
        if fbanks.is_empty() {
            return Ok(String::new());
        }
        let (t_lfr, lfr_flat) = apply_lfr(&fbanks, 7, 6);
        if t_lfr == 0 {
            return Ok(String::new());
        }

        let t_x = Tensor::from_array(([1usize, t_lfr, 560], lfr_flat)).map_err(|e| e.to_string())?;
        let t_len = Tensor::from_array(([1usize], vec![t_lfr as i32])).map_err(|e| e.to_string())?;
        let t_lang = Tensor::from_array(([1usize], vec![0i32])).map_err(|e| e.to_string())?;
        let t_norm = Tensor::from_array(([1usize], vec![14i32])).map_err(|e| e.to_string())?;

        let outputs = self
            .session
            .run(ort::inputs![
                "x" => t_x,
                "x_length" => t_len,
                "language" => t_lang,
                "text_norm" => t_norm
            ])
            .map_err(|e| e.to_string())?;

        let (shape, logits) = outputs[0]
            .try_extract_tensor::<f32>()
            .map_err(|e| e.to_string())?;
        if shape.len() != 3 {
            return Ok(String::new());
        }
        let frames = shape[1] as usize;
        let vocab = shape[2] as usize;

        let mut prev_id = 0usize;
        let mut out = String::new();
        // Skip first 4 SenseVoice prompt tokens (language, emotion, event, itn)
        for t in 4..frames {
            let row = &logits[t * vocab..(t + 1) * vocab];
            let mut best_id = 0usize;
            let mut best_val = f32::NEG_INFINITY;
            for (idx, &v) in row.iter().enumerate() {
                if v > best_val {
                    best_val = v;
                    best_id = idx;
                }
            }
            if best_id != 0 && best_id != prev_id {
                if let Some(tok) = self.tokens.get(&best_id) {
                    if !tok.starts_with("<|") {
                        out.push_str(&tok.replace('▁', " "));
                    }
                }
            }
            prev_id = best_id;
        }

        Ok(out.trim().to_string())
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

fn build_mel_filterbank(
    n_mels: usize,
    n_fft: usize,
    sample_rate: f32,
    f_min: f32,
    f_max: f32,
) -> Vec<Vec<f32>> {
    let hz_to_mel = |hz: f32| 2595.0 * (1.0 + hz / 700.0).log10();
    let mel_to_hz = |mel: f32| 700.0 * (10.0f32.powf(mel / 2595.0) - 1.0);

    let mel_min = hz_to_mel(f_min);
    let mel_max = hz_to_mel(f_max);
    let n_bins = n_fft / 2 + 1;

    let mut points = Vec::with_capacity(n_mels + 2);
    for i in 0..(n_mels + 2) {
        let mel = mel_min + (mel_max - mel_min) * (i as f32 / (n_mels + 1) as f32);
        let hz = mel_to_hz(mel);
        points.push(((n_fft as f32 + 1.0) * hz / sample_rate).floor() as usize);
    }

    let mut bank = vec![vec![0.0f32; n_bins]; n_mels];
    for m in 0..n_mels {
        let left = points[m];
        let center = points[m + 1];
        let right = points[m + 2];
        for k in left..center {
            if k < n_bins && center > left {
                bank[m][k] = (k - left) as f32 / (center - left) as f32;
            }
        }
        for k in center..right {
            if k < n_bins && right > center {
                bank[m][k] = (right - k) as f32 / (right - center) as f32;
            }
        }
    }
    bank
}

fn compute_fbank_80(samples: &[f32], mel_bank: &[Vec<f32>]) -> Vec<[f32; 80]> {
    let win_len = 400; // 25ms @ 16kHz
    let hop = 160; // 10ms @ 16kHz
    let n_fft = 512;
    if samples.len() < win_len {
        return Vec::new();
    }

    let mut window = vec![0.0f32; win_len];
    for i in 0..win_len {
        window[i] = 0.54 - 0.46 * (2.0 * PI * i as f32 / (win_len - 1) as f32).cos();
    }

    let n_frames = (samples.len() - win_len) / hop + 1;
    let mut out = Vec::with_capacity(n_frames);
    let mut re = vec![0.0f32; n_fft];
    let mut im = vec![0.0f32; n_fft];

    for f in 0..n_frames {
        let start = f * hop;
        re.fill(0.0);
        im.fill(0.0);
        // Pre-emphasis 0.97
        for i in 0..win_len {
            let s = samples[start + i];
            let prev = if start + i > 0 { samples[start + i - 1] } else { s };
            re[i] = (s - 0.97 * prev) * window[i];
        }
        fft_radix2(&mut re, &mut im);

        let n_bins = n_fft / 2 + 1;
        let mut frame_mel = [0.0f32; 80];
        for m in 0..80 {
            let mut energy = 0.0f32;
            for k in 0..n_bins {
                let p = re[k] * re[k] + im[k] * im[k];
                energy += p * mel_bank[m][k];
            }
            frame_mel[m] = energy.max(1e-10).ln();
        }
        out.push(frame_mel);
    }

    // Per-utterance CMVN (zero mean, unit variance)
    if !out.is_empty() {
        for m in 0..80 {
            let mean = out.iter().map(|fr| fr[m]).sum::<f32>() / out.len() as f32;
            let var = out
                .iter()
                .map(|fr| (fr[m] - mean) * (fr[m] - mean))
                .sum::<f32>()
                / out.len() as f32;
            let inv_std = 1.0 / (var.sqrt() + 1e-6);
            for fr in &mut out {
                fr[m] = (fr[m] - mean) * inv_std;
            }
        }
    }
    out
}

fn apply_lfr(frames: &[[f32; 80]], m: usize, n: usize) -> (usize, Vec<f32>) {
    if frames.is_empty() {
        return (0, Vec::new());
    }
    let pad = (m - 1) / 2;
    let mut padded = Vec::with_capacity(frames.len() + pad);
    for _ in 0..pad {
        padded.push(frames[0]);
    }
    padded.extend_from_slice(frames);

    let t_lfr = (padded.len() + n - 1) / n;
    let mut flat = Vec::with_capacity(t_lfr * m * 80);
    for i in 0..t_lfr {
        let idx = i * n;
        for k in 0..m {
            let src_idx = (idx + k).min(padded.len() - 1);
            flat.extend_from_slice(&padded[src_idx]);
        }
    }
    (t_lfr, flat)
}

fn fft_radix2(re: &mut [f32], im: &mut [f32]) {
    let n = re.len();
    let mut j = 0usize;
    for i in 1..n {
        let mut bit = n >> 1;
        while j & bit != 0 {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if i < j {
            re.swap(i, j);
            im.swap(i, j);
        }
    }
    let mut len = 2;
    while len <= n {
        let ang = -2.0 * PI / len as f32;
        let wlen_re = ang.cos();
        let wlen_im = ang.sin();
        for i in (0..n).step_by(len) {
            let mut w_re = 1.0f32;
            let mut w_im = 0.0f32;
            for k in 0..(len / 2) {
                let u_re = re[i + k];
                let u_im = im[i + k];
                let v_re = re[i + k + len / 2] * w_re - im[i + k + len / 2] * w_im;
                let v_im = re[i + k + len / 2] * w_im + im[i + k + len / 2] * w_re;
                re[i + k] = u_re + v_re;
                im[i + k] = u_im + v_im;
                re[i + k + len / 2] = u_re - v_re;
                im[i + k + len / 2] = u_im - v_im;
                let next_re = w_re * wlen_re - w_im * wlen_im;
                w_im = w_re * wlen_im + w_im * wlen_re;
                w_re = next_re;
            }
        }
        len <<= 1;
    }
}
