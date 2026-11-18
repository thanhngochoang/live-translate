use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use display_info::DisplayInfo;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::mpsc;
use voicetrans_core::audio::Resampler;
use voicetrans_core::overlay::{MonitorInfo, MonitorRect};

pub struct SingleInstanceGuard {
    lock_path: PathBuf,
}

impl Drop for SingleInstanceGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.lock_path);
    }
}

pub fn acquire_single_instance(_on_activate: impl Fn() + Send + 'static) -> Option<SingleInstanceGuard> {
    let runtime_dir = std::env::var("XDG_RUNTIME_DIR")
        .or_else(|_| std::env::var("TMPDIR"))
        .unwrap_or_else(|_| "/tmp".to_string());
    let lock_path = PathBuf::from(runtime_dir).join("voicetrans.lock");

    if let Ok(existing) = fs::read_to_string(&lock_path) {
        if let Ok(pid) = existing.trim().parse::<u32>() {
            if is_pid_alive(pid) {
                return None;
            }
        }
        let _ = fs::remove_file(&lock_path);
    }

    if let Ok(mut f) = OpenOptions::new().write(true).create_new(true).open(&lock_path) {
        let _ = writeln!(f, "{}", std::process::id());
        Some(SingleInstanceGuard { lock_path })
    } else {
        None
    }
}

fn is_pid_alive(pid: u32) -> bool {
    #[cfg(target_os = "linux")]
    {
        std::path::Path::new(&format!("/proc/{pid}")).exists()
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("kill")
            .args(["-0", &pid.to_string()])
            .status()
            .map(|s| s.success())
            .unwrap_or(false)
    }
}

pub fn enable_dpi_awareness() {}

pub fn enumerate_monitors() -> Vec<MonitorInfo> {
    let Ok(displays) = DisplayInfo::all() else {
        return vec![MonitorInfo {
            id: "default".into(),
            name: "Màn hình 1 (Chính)".into(),
            work_area: MonitorRect {
                left: 0,
                top: 0,
                right: 1920,
                bottom: 1040,
            },
            dpi: 96,
            is_primary: true,
        }];
    };

    displays
        .into_iter()
        .enumerate()
        .map(|(i, d)| {
            let idx = i + 1;
            let name = if d.is_primary {
                format!("Màn hình {idx} (Chính)")
            } else {
                format!("Màn hình {idx}")
            };
            MonitorInfo {
                id: d.id.to_string(),
                name,
                work_area: MonitorRect {
                    left: d.x,
                    top: d.y,
                    right: d.x + d.width as i32,
                    bottom: d.y + d.height as i32,
                },
                dpi: (d.scale_factor * 96.0).round() as u32,
                is_primary: d.is_primary,
            }
        })
        .collect()
}

pub fn configure_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    let candidates: &[(&str, &str, bool)] = &[
        #[cfg(target_os = "macos")]
        ("sfns", "/System/Library/Fonts/SFNS.ttf", true),
        #[cfg(target_os = "macos")]
        ("pingfang", "/System/Library/Fonts/PingFang.ttc", false),
        #[cfg(target_os = "linux")]
        ("noto", "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf", true),
        #[cfg(target_os = "linux")]
        ("dejavu", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", true),
        #[cfg(target_os = "linux")]
        ("notocjk", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", false),
    ];

    for &(name, path, primary) in candidates {
        if let Ok(bytes) = fs::read(path) {
            fonts
                .font_data
                .insert(name.to_owned(), egui::FontData::from_owned(bytes).into());
            if let Some(prop) = fonts.families.get_mut(&egui::FontFamily::Proportional) {
                if primary {
                    prop.insert(0, name.to_owned());
                } else {
                    let pos = prop.len().min(1);
                    prop.insert(pos, name.to_owned());
                }
            }
        }
    }

    ctx.set_fonts(fonts);
}

pub fn start_audio_capture(
    running: Arc<AtomicBool>,
    tx: mpsc::Sender<Vec<f32>>,
) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        let host = cpal::default_host();
        // On Linux (PipeWire/PulseAudio) prefer a ".monitor" source for playback loopback;
        // fallback to default input device.
        let device = host
            .input_devices()
            .ok()
            .and_then(|mut devs| {
                devs.find(|d| {
                    d.name()
                        .map(|n| n.to_lowercase().contains("monitor"))
                        .unwrap_or(false)
                })
            })
            .or_else(|| host.default_input_device());

        let Some(device) = device else {
            return;
        };
        let Ok(config) = device.default_input_config() else {
            return;
        };
        let channels = (config.channels() as usize).max(1);
        let sample_rate = config.sample_rate().0 as f64;
        let running_cb = running.clone();

        let stream = device.build_input_stream(
            &config.into(),
            move |data: &[f32], _| {
                if !running_cb.load(Ordering::Relaxed) {
                    return;
                }
                let mono = Resampler::downmix_to_mono(data, channels);
                let resampled = Resampler::resample_mono(&mono, sample_rate, 16_000.0);
                if !resampled.is_empty() {
                    let _ = tx.try_send(resampled);
                }
            },
            |_err| {},
            None,
        );

        if let Ok(stream) = stream {
            let _ = stream.play();
            while running.load(Ordering::Relaxed) {
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
        }
    })
}

pub fn init_float_overlay_window(ctx: &egui::Context, _title: &str, locked: bool, _opacity: f32) {
    ctx.send_viewport_cmd(egui::ViewportCommand::MousePassthrough(locked));
}

pub fn set_float_opacity(_opacity: f32) {}

pub fn set_float_locked(ctx: &egui::Context, locked: bool) {
    ctx.send_viewport_cmd(egui::ViewportCommand::MousePassthrough(locked));
}

pub fn is_hold_dragging() -> bool {
    false
}

pub fn stop_hooks() {}

pub fn init_settings_window(_title: &str) {}

pub fn show_settings_window_native() {}

pub fn hide_settings_window_native() {}
