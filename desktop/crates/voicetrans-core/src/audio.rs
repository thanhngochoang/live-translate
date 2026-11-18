/// Audio resampling and capture-blocked detection utilities.
pub struct Resampler;

impl Resampler {
    /// Downmixes interleaved multi-channel audio to mono.
    pub fn downmix_to_mono(interleaved: &[f32], channel_count: usize) -> Vec<f32> {
        let mut out = Vec::new();
        Self::downmix_to_mono_into(interleaved, channel_count, &mut out);
        out
    }

    /// Downmixes interleaved multi-channel audio into a reusable mono buffer.
    pub fn downmix_to_mono_into(
        interleaved: &[f32],
        channel_count: usize,
        out: &mut Vec<f32>,
    ) {
        out.clear();
        if channel_count <= 1 || interleaved.is_empty() {
            out.extend_from_slice(interleaved);
            return;
        }
        let frame_count = interleaved.len() / channel_count;
        out.reserve(frame_count);
        let inv = 1.0 / channel_count as f32;
        for i in 0..frame_count {
            let mut sum = 0.0f32;
            let base = i * channel_count;
            for ch in 0..channel_count {
                sum += interleaved[base + ch];
            }
            out.push(sum * inv);
        }
    }

    /// Resamples mono audio from `from_sample_rate` to `to_sample_rate`.
    /// Uses boxcar anti-aliasing averaging for exact integer decimation (e.g. 48 kHz -> 16 kHz)
    /// and linear interpolation otherwise.
    pub fn resample_mono(samples: &[f32], from_sample_rate: f64, to_sample_rate: f64) -> Vec<f32> {
        let mut out = Vec::new();
        Self::resample_mono_into(samples, from_sample_rate, to_sample_rate, &mut out);
        out
    }

    /// Resamples mono audio into a reusable output buffer to avoid per-packet heap allocations.
    pub fn resample_mono_into(
        samples: &[f32],
        from_sample_rate: f64,
        to_sample_rate: f64,
        out: &mut Vec<f32>,
    ) {
        out.clear();
        if (from_sample_rate - to_sample_rate).abs() < f64::EPSILON || samples.is_empty() {
            out.extend_from_slice(samples);
            return;
        }
        let ratio = from_sample_rate / to_sample_rate;
        let output_count = (samples.len() as f64 / ratio) as usize;
        out.reserve(output_count);

        let rounded_ratio = ratio.round() as usize;
        if rounded_ratio >= 2 && (ratio - rounded_ratio as f64).abs() < 1e-6 {
            let inv = 1.0 / rounded_ratio as f32;
            for i in 0..output_count {
                let base = i * rounded_ratio;
                let mut sum = 0.0f32;
                for k in 0..rounded_ratio {
                    sum += samples[base + k];
                }
                out.push(sum * inv);
            }
            return;
        }

        for i in 0..output_count {
            let src_index = i as f64 * ratio;
            let idx = src_index as usize;
            let frac = (src_index - idx as f64) as f32;
            if idx + 1 < samples.len() {
                let s1 = samples[idx];
                let s2 = samples[idx + 1];
                out.push(s1 + frac * (s2 - s1));
            } else if idx < samples.len() {
                out.push(samples[idx]);
            }
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlaybackState {
    pub active: bool,
    pub blocks_capture: bool,
}

pub struct CaptureBlockedDetector {
    frames_needed: usize,
    zero_frames: usize,
}

impl CaptureBlockedDetector {
    pub fn new(threshold_ms: u64, frame_ms: u64) -> Self {
        let frames_needed = (threshold_ms / frame_ms.max(1)).max(1) as usize;
        Self {
            frames_needed,
            zero_frames: 0,
        }
    }

    pub fn on_frame(&mut self, frame: &[f32], playback: PlaybackState) -> bool {
        if !playback.active || frame.iter().any(|&s| s != 0.0) {
            self.zero_frames = 0;
            return false;
        }
        self.zero_frames += 1;
        self.zero_frames == self.frames_needed
    }

    pub fn reset(&mut self) {
        self.zero_frames = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn downmix_stereo_averages_channels() {
        let stereo = [0.5f32, -0.5, 0.2, 0.4];
        let mono = Resampler::downmix_to_mono(&stereo, 2);
        assert_eq!(mono.len(), 2);
        assert!((mono[0] - 0.0).abs() < 1e-6);
        assert!((mono[1] - 0.3).abs() < 1e-6);
    }

    #[test]
    fn resample_48k_to_16k_reduces_count_by_3() {
        let input = vec![0.25f32; 4800];
        let out = Resampler::resample_mono(&input, 48_000.0, 16_000.0);
        assert_eq!(out.len(), 1600);
        assert!((out[0] - 0.25).abs() < 1e-6);
    }

    #[test]
    fn capture_blocked_fires_once_at_threshold() {
        let mut det = CaptureBlockedDetector::new(300, 100);
        let zeros = [0.0f32; 160];
        let active = PlaybackState {
            active: true,
            blocks_capture: false,
        };
        assert!(!det.on_frame(&zeros, active));
        assert!(!det.on_frame(&zeros, active));
        assert!(det.on_frame(&zeros, active));
        assert!(!det.on_frame(&zeros, active));
    }
}
