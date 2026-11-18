use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::mpsc;
use voicetrans_core::audio::Resampler;
use windows::Win32::Media::Audio::{
    eConsole, eRender, IAudioCaptureClient, IAudioClient, IMMDeviceEnumerator, MMDeviceEnumerator,
    AUDCLNT_BUFFERFLAGS_SILENT, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
    WAVEFORMATEX,
};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize, CLSCTX_ALL,
    COINIT_MULTITHREADED,
};

/// Captures system playback audio via WASAPI Loopback and emits 16 kHz mono f32 chunks.
pub fn start_wasapi_loopback(
    running: Arc<AtomicBool>,
    tx: mpsc::Sender<Vec<f32>>,
) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);

        let enumerator: Result<IMMDeviceEnumerator, _> =
            CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL);
        let enumerator = match enumerator {
            Ok(e) => e,
            Err(err) => {
                eprintln!("[wasapi] CoCreateInstance(MMDeviceEnumerator) failed: {err:?}");
                CoUninitialize();
                return;
            }
        };

        let device = match enumerator.GetDefaultAudioEndpoint(eRender, eConsole) {
            Ok(d) => d,
            Err(err) => {
                eprintln!("[wasapi] GetDefaultAudioEndpoint failed: {err:?}");
                CoUninitialize();
                return;
            }
        };

        let audio_client = match device.Activate::<IAudioClient>(CLSCTX_ALL, None) {
            Ok(c) => c,
            Err(err) => {
                eprintln!("[wasapi] device.Activate::<IAudioClient> failed: {err:?}");
                CoUninitialize();
                return;
            }
        };

        let pwfx = match audio_client.GetMixFormat() {
            Ok(p) => p,
            Err(err) => {
                eprintln!("[wasapi] GetMixFormat failed: {err:?}");
                CoUninitialize();
                return;
            }
        };

        let wfx: WAVEFORMATEX = *pwfx;
        let channels = (wfx.nChannels as usize).max(1);
        let sample_rate = wfx.nSamplesPerSec as f64;
        let bits = wfx.wBitsPerSample;
        let format_tag = wfx.wFormatTag;
        eprintln!("[wasapi] MixFormat: tag=0x{format_tag:04X} ch={channels} rate={sample_rate} bits={bits}");

        // 100 ms buffer in 100-ns units
        let hns_buffer = 1_000_000i64;
        if let Err(err) = audio_client.Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK,
            hns_buffer,
            0,
            pwfx,
            None,
        ) {
            eprintln!("[wasapi] Initialize(LOOPBACK) failed: {err:?}");
            CoTaskMemFree(Some(pwfx as *const _));
            CoUninitialize();
            return;
        }
        CoTaskMemFree(Some(pwfx as *const _));

        let capture_client = match audio_client.GetService::<IAudioCaptureClient>() {
            Ok(c) => c,
            Err(err) => {
                eprintln!("[wasapi] GetService::<IAudioCaptureClient> failed: {err:?}");
                CoUninitialize();
                return;
            }
        };

        if let Err(err) = audio_client.Start() {
            eprintln!("[wasapi] audio_client.Start() failed: {err:?}");
            CoUninitialize();
            return;
        }
        eprintln!("[wasapi] Loopback started successfully");

        let mut interleaved_buf: Vec<f32> = Vec::with_capacity(4800 * channels);
        let mut mono_buf: Vec<f32> = Vec::with_capacity(4800);

        while running.load(Ordering::SeqCst) {
            std::thread::sleep(std::time::Duration::from_millis(20));
            while let Ok(packet_frames) = capture_client.GetNextPacketSize() {
                if packet_frames == 0 {
                    break;
                }
                let mut data_ptr: *mut u8 = std::ptr::null_mut();
                let mut num_frames = 0u32;
                let mut flags = 0u32;
                if capture_client
                    .GetBuffer(&mut data_ptr, &mut num_frames, &mut flags, None, None)
                    .is_err()
                {
                    break;
                }

                let total_samples = num_frames as usize * channels;
                let is_silent = (flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32) != 0;
                interleaved_buf.clear();

                if is_silent || data_ptr.is_null() {
                    interleaved_buf.resize(total_samples, 0.0);
                } else if bits == 32 {
                    let slice = std::slice::from_raw_parts(data_ptr as *const f32, total_samples);
                    interleaved_buf.extend_from_slice(slice);
                } else if bits == 16 {
                    let slice = std::slice::from_raw_parts(data_ptr as *const i16, total_samples);
                    interleaved_buf.reserve(total_samples);
                    for &s in slice {
                        interleaved_buf.push(s as f32 / 32768.0);
                    }
                } else {
                    interleaved_buf.resize(total_samples, 0.0);
                }

                let _ = capture_client.ReleaseBuffer(num_frames);

                Resampler::downmix_to_mono_into(&interleaved_buf, channels, &mut mono_buf);
                let resampled = Resampler::resample_mono(&mono_buf, sample_rate, 16_000.0);
                if !resampled.is_empty() && tx.blocking_send(resampled).is_err() {
                    running.store(false, Ordering::SeqCst);
                    break;
                }
            }
        }

        let _ = audio_client.Stop();
        CoUninitialize();
    })
}
