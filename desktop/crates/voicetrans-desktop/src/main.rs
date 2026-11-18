#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(target_os = "windows")]
mod audio_wasapi;
mod config;
#[cfg(target_os = "windows")]
mod hold_drag_hook;
mod model_manager;
#[cfg(target_os = "windows")]
mod monitors;
mod overlay_window;
mod pipeline;
mod platform;
mod tray;

use std::sync::atomic::AtomicBool;
use std::sync::Arc;

use config::AppConfig;
use overlay_window::VoiceTransApp;
use tray::AppTray;

fn main() -> eframe::Result<()> {
    if std::env::args().any(|a| a == "--diag-pipeline") {
        let dir = std::env::args()
            .nth(2)
            .map(std::path::PathBuf::from)
            .unwrap_or_else(model_manager::resolve_models_dir);
        pipeline::diag_inspect_models(&dir);
        return Ok(());
    }
    if std::env::args().any(|a| a == "--diag-audio") {
        #[cfg(target_os = "windows")]
        run_audio_diag();
        return Ok(());
    }
    if std::env::args().any(|a| a == "--diag-download") {
        let dir = model_manager::resolve_models_dir();
        let st = Arc::new(std::sync::Mutex::new(model_manager::EngineState::Checking));
        model_manager::spawn_download_missing(dir.clone(), st.clone(), || {});
        for _ in 0..20 {
            std::thread::sleep(std::time::Duration::from_millis(500));
            if let Ok(g) = st.lock() {
                eprintln!("[diag-download] dir={} state={g:?}", dir.display());
                if matches!(*g, model_manager::EngineState::Ready | model_manager::EngineState::Error(_)) {
                    break;
                }
            }
        }
        return Ok(());
    }

    let Some(_instance_guard) = platform::acquire_single_instance(|| {
        overlay_window::request_open_settings();
    }) else {
        return Ok(());
    };

    platform::enable_dpi_awareness();

    let config = AppConfig::load();
    let is_capturing = Arc::new(AtomicBool::new(false));

    let mut viewport = egui::ViewportBuilder::default()
        .with_title("VoiceTrans VI — Cài đặt & Điều khiển")
        .with_decorations(true)
        .with_transparent(false)
        .with_taskbar(true)
        .with_inner_size([580.0, 580.0])
        .with_min_inner_size([500.0, 460.0]);

    if let Ok(icon) = eframe::icon_data::from_png_bytes(include_bytes!(
        "../../../../app/src/main/res/mipmap-xhdpi/ic_launcher.png"
    )) {
        viewport = viewport.with_icon(Arc::new(icon));
    }

    let native_options = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };

    let res = eframe::run_native(
        "VoiceTrans VI",
        native_options,
        Box::new(move |cc| {
            platform::configure_fonts(&cc.egui_ctx);
            overlay_window::register_egui_context(cc.egui_ctx.clone());
            let ctx_ticker = cc.egui_ctx.clone();
            let cap_ticker = is_capturing.clone();
            std::thread::spawn(move || {
                let float_id = egui::ViewportId::from_hash_of("voicetrans_float_sub");
                loop {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                    if cap_ticker.load(std::sync::atomic::Ordering::Relaxed)
                        || platform::is_hold_dragging()
                    {
                        ctx_ticker.request_repaint();
                        ctx_ticker.request_repaint_of(float_id);
                    }
                }
            });
            let ctx_wake = cc.egui_ctx.clone();
            let tray = AppTray::new(move |action| {
                match action {
                    tray::TrayAction::ShowSettings => overlay_window::request_open_settings(),
                    tray::TrayAction::Quit => {
                        platform::stop_hooks();
                        std::process::exit(0);
                    }
                    _ => ctx_wake.request_repaint(),
                }
            });
            Ok(Box::new(VoiceTransApp::new(config, is_capturing, tray)))
        }),
    );

    platform::stop_hooks();
    res
}

#[cfg(target_os = "windows")]
fn run_audio_diag() {
    use std::sync::atomic::Ordering;
    let running = Arc::new(AtomicBool::new(true));
    let (tx, mut rx) = tokio::sync::mpsc::channel::<Vec<f32>>(128);
    let handle = audio_wasapi::start_wasapi_loopback(running.clone(), tx);

    let start = std::time::Instant::now();
    let mut chunks = 0usize;
    let mut non_zero_chunks = 0usize;
    let mut max_peak = 0.0f32;
    while start.elapsed() < std::time::Duration::from_secs(3) {
        while let Ok(samples) = rx.try_recv() {
            chunks += 1;
            let peak = samples.iter().fold(0.0f32, |m, &s| m.max(s.abs()));
            if peak > 1e-5 {
                non_zero_chunks += 1;
            }
            if peak > max_peak {
                max_peak = peak;
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    running.store(false, Ordering::SeqCst);
    let _ = handle.join();
    eprintln!("[diag-audio] chunks={chunks} non_zero_chunks={non_zero_chunks} max_peak={max_peak:.6}");
}
