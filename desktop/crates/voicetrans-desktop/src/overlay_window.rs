use egui::{Color32, FontId, RichText, Rounding, Stroke, ViewportBuilder, ViewportId};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;
use voicetrans_core::overlay::{
    BilingualLines, LineOrder, MonitorBounds, MonitorInfo, OverlayMode, SubtitleBlock,
};
use voicetrans_core::session::UtteranceKind;
use voicetrans_core::translate::SourceLang;

use crate::config::AppConfig;
use crate::model_manager::{self, EngineState};
use crate::pipeline::{self, PipelineEvent};
use crate::platform;
use crate::tray::{AppTray, TrayAction};

static OPEN_SETTINGS_REQUESTED: AtomicBool = AtomicBool::new(false);
static EGUI_CTX: std::sync::OnceLock<egui::Context> = std::sync::OnceLock::new();

pub fn register_egui_context(ctx: egui::Context) {
    let _ = EGUI_CTX.set(ctx);
}

pub fn request_open_settings() {
    OPEN_SETTINGS_REQUESTED.store(true, Ordering::SeqCst);
    platform::show_settings_window_native();
    if let Some(ctx) = EGUI_CTX.get() {
        ctx.request_repaint();
    }
}

// SaaS dark theme tokens
const BG_CANVAS: Color32 = Color32::from_rgb(11, 13, 18);
const BG_CARD: Color32 = Color32::from_rgb(19, 22, 31);
const BG_ELEVATED: Color32 = Color32::from_rgb(27, 32, 44);
const BORDER_SUBTLE: Color32 = Color32::from_rgb(37, 42, 56);
const ACCENT_PRIMARY: Color32 = Color32::from_rgb(1, 102, 246);
const ACCENT_SOFT: Color32 = Color32::from_rgb(124, 176, 255);
const TEXT_PRIMARY: Color32 = Color32::from_rgb(240, 244, 248);
const TEXT_SECONDARY: Color32 = Color32::from_rgb(142, 152, 174);
const STATUS_GREEN: Color32 = Color32::from_rgb(34, 197, 94);
const DANGER_RED: Color32 = Color32::from_rgb(220, 38, 38);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MainTab {
    Transcript,
    Settings,
}

#[derive(Clone)]
pub struct SharedOverlayState {
    pub config: AppConfig,
    pub blocks: Vec<SubtitleBlock>,
    pub history: Vec<SubtitleBlock>,
    pub partial: Option<String>,
    pub capture_warning: Option<String>,
    pub float_visible: bool,
    pub is_capturing: bool,
    pub audio_level: f32,
    pub audio_db: f32,
    pub monitors: Vec<MonitorInfo>,
    pub pending_position: Option<(i32, i32)>,
    pub hwnd_initialized: bool,
}

pub struct VoiceTransApp {
    shared: Arc<Mutex<SharedOverlayState>>,
    engine_state: Arc<Mutex<EngineState>>,
    models_dir: std::path::PathBuf,
    is_capturing_flag: Arc<AtomicBool>,
    audio_rx: Option<mpsc::Receiver<Vec<f32>>>,
    pipeline_audio_tx: Option<mpsc::Sender<Vec<f32>>>,
    pipeline_event_rx: Option<std::sync::mpsc::Receiver<PipelineEvent>>,
    capture_handle: Option<std::thread::JoinHandle<()>>,
    pipeline_handle: Option<std::thread::JoinHandle<()>>,
    tray: Option<AppTray>,
    settings_visible: bool,
    active_tab: MainTab,
    export_notice: Option<String>,
    float_viewport_id: ViewportId,
}

impl VoiceTransApp {
    pub fn new(
        config: AppConfig,
        is_capturing_flag: Arc<AtomicBool>,
        tray: Option<AppTray>,
    ) -> Self {
        let monitors_list = platform::enumerate_monitors();
        let init_pos = MonitorBounds::resolve_position(
            config.overlay_position.as_ref(),
            &monitors_list,
            680,
            130,
        );
        let shared = Arc::new(Mutex::new(SharedOverlayState {
            config,
            blocks: Vec::new(),
            history: Vec::new(),
            partial: None,
            capture_warning: None,
            float_visible: true,
            is_capturing: false,
            audio_level: 0.0,
            audio_db: -120.0,
            monitors: monitors_list,
            pending_position: Some(init_pos),
            hwnd_initialized: false,
        }));
        let models_dir = model_manager::resolve_models_dir();
        let missing = model_manager::check_missing_components(&models_dir);
        let initial_engine = if missing.is_empty() {
            EngineState::Ready
        } else {
            EngineState::Missing(missing)
        };
        let engine_state = Arc::new(Mutex::new(initial_engine.clone()));

        if matches!(initial_engine, EngineState::Missing(_)) {
            model_manager::spawn_download_missing(
                models_dir.clone(),
                engine_state.clone(),
                || {
                    if let Some(ctx) = EGUI_CTX.get() {
                        ctx.request_repaint();
                        ctx.request_repaint_of(ViewportId::from_hash_of("voicetrans_float_sub"));
                    }
                },
            );
        }

        Self {
            shared,
            engine_state,
            models_dir,
            is_capturing_flag,
            audio_rx: None,
            pipeline_audio_tx: None,
            pipeline_event_rx: None,
            capture_handle: None,
            pipeline_handle: None,
            tray,
            settings_visible: true,
            active_tab: MainTab::Transcript,
            export_notice: None,
            float_viewport_id: ViewportId::from_hash_of("voicetrans_float_sub"),
        }
    }

    fn toggle_capture(&mut self) {
        let currently = self.is_capturing_flag.load(Ordering::SeqCst);
        if currently {
            self.is_capturing_flag.store(false, Ordering::SeqCst);
            self.audio_rx = None;
            self.pipeline_audio_tx = None;
            self.pipeline_event_rx = None;
            if let Some(handle) = self.capture_handle.take() {
                let _ = handle.join();
            }
            if let Some(handle) = self.pipeline_handle.take() {
                let _ = handle.join();
            }
            if let Ok(mut st) = self.shared.lock() {
                st.is_capturing = false;
                st.partial = None;
            }
        } else {
            let missing_now = model_manager::check_missing_components(&self.models_dir);
            if !missing_now.is_empty() {
                if let Ok(mut g) = self.engine_state.lock() {
                    *g = EngineState::Missing(missing_now.clone());
                }
                model_manager::spawn_download_missing(
                    self.models_dir.clone(),
                    self.engine_state.clone(),
                    || {
                        if let Some(ctx) = EGUI_CTX.get() {
                            ctx.request_repaint();
                            ctx.request_repaint_of(ViewportId::from_hash_of("voicetrans_float_sub"));
                        }
                    },
                );
                if let Ok(mut st) = self.shared.lock() {
                    st.capture_warning = Some(
                        "Phát hiện thiếu Engine/Model AI — đang tự động tải lại...".into(),
                    );
                }
                return;
            }
            let ready = matches!(
                self.engine_state.lock().map(|g| g.clone()),
                Ok(EngineState::Ready)
            );
            if !ready {
                if let Ok(mut st) = self.shared.lock() {
                    st.capture_warning = Some(
                        "Đang tải Engine & Model AI, vui lòng đợi hoàn tất trước khi thu âm.".into(),
                    );
                }
                return;
            }

            let (tx, rx) = mpsc::channel(64);
            let (pipe_tx, pipe_rx) = mpsc::channel(128);
            let (ev_tx, ev_rx) = std::sync::mpsc::channel();
            let target_lang = self
                .shared
                .lock()
                .map(|s| s.config.target_lang.clone())
                .unwrap_or_else(|_| "vi".to_string());

            self.is_capturing_flag.store(true, Ordering::SeqCst);
            self.audio_rx = Some(rx);
            self.pipeline_audio_tx = Some(pipe_tx);
            self.pipeline_event_rx = Some(ev_rx);
            self.capture_handle = Some(platform::start_audio_capture(
                self.is_capturing_flag.clone(),
                tx,
            ));
            self.pipeline_handle = Some(pipeline::spawn_asr_translate_pipeline(
                self.models_dir.clone(),
                target_lang,
                self.is_capturing_flag.clone(),
                pipe_rx,
                ev_tx,
                || {
                    if let Some(ctx) = EGUI_CTX.get() {
                        ctx.request_repaint();
                        ctx.request_repaint_of(ViewportId::from_hash_of("voicetrans_float_sub"));
                    }
                },
            ));
            if let Ok(mut st) = self.shared.lock() {
                st.capture_warning = None;
                st.is_capturing = true;
                st.partial = Some("Đang lắng nghe âm thanh hệ thống...".into());
            }
        }
    }

    fn load_demo_blocks(&self) {
        if let Ok(mut st) = self.shared.lock() {
            let demo = vec![
                SubtitleBlock {
                    id: 1,
                    kind: UtteranceKind::Speech,
                    source_text: "Welcome to VoiceTrans on Windows!".into(),
                    source_lang: SourceLang::En,
                    target_lang: "vi".into(),
                    translation_text: Some("Chào mừng đến với VoiceTrans trên Windows!".into()),
                    is_newest: false,
                    failed: false,
                },
                SubtitleBlock {
                    id: 2,
                    kind: UtteranceKind::Speech,
                    source_text: "支持多显示器，随时长按拖动字幕。".into(),
                    source_lang: SourceLang::Zh,
                    target_lang: "vi".into(),
                    translation_text: Some(
                        "Hỗ trợ nhiều màn hình, nhấn giữ để kéo phụ đề bất cứ lúc nào.".into(),
                    ),
                    is_newest: true,
                    failed: false,
                },
            ];
            st.blocks = demo.clone();
            st.history = demo;
            st.float_visible = true;
        }
    }

    fn move_to_monitor(&self, monitor_index: usize) {
        if let Ok(mut st) = self.shared.lock() {
            st.monitors = platform::enumerate_monitors();
            let Some(target) = st.monitors.get(monitor_index).cloned() else {
                return;
            };
            let w = 680;
            let h = 130;
            let area = &target.work_area;
            let x = area.left + (area.width() - w) / 2;
            let y = area.bottom - h - 48;
            st.float_visible = true;
            st.pending_position = Some((x, y));
            st.config.overlay_position = MonitorBounds::save_position(x, y, w, h, &st.monitors);
            st.config.save();
        }
    }

    fn set_locked(&self, ctx: &egui::Context, locked: bool) {
        if let Ok(mut st) = self.shared.lock() {
            st.config.is_locked = locked;
            st.config.save();
        }
        platform::set_float_locked(ctx, locked);
    }

    fn set_float_visible(&self, ctx: &egui::Context, visible: bool) {
        let locked = if let Ok(mut st) = self.shared.lock() {
            st.float_visible = visible;
            st.config.is_locked
        } else {
            false
        };
        platform::set_float_locked(ctx, visible && locked);
    }

    fn show_settings(&mut self, ctx: &egui::Context) {
        self.settings_visible = true;
        platform::show_settings_window_native();
        ctx.send_viewport_cmd_to(ViewportId::ROOT, egui::ViewportCommand::Visible(true));
        ctx.send_viewport_cmd_to(ViewportId::ROOT, egui::ViewportCommand::Minimized(false));
        ctx.send_viewport_cmd_to(ViewportId::ROOT, egui::ViewportCommand::Focus);
    }

    fn hide_settings_to_tray(&mut self, ctx: &egui::Context) {
        self.settings_visible = false;
        platform::hide_settings_window_native();
        ctx.send_viewport_cmd_to(ViewportId::ROOT, egui::ViewportCommand::Visible(false));
    }
}

impl eframe::App for VoiceTransApp {
    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        self.is_capturing_flag.store(false, Ordering::SeqCst);
        platform::stop_hooks();
        std::process::exit(0);
    }

    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        static SETTINGS_HWND_INIT: AtomicBool = AtomicBool::new(false);
        if !SETTINGS_HWND_INIT.swap(true, Ordering::SeqCst) {
            platform::init_settings_window("VoiceTrans VI — Cài đặt & Điều khiển");
        }
        if OPEN_SETTINGS_REQUESTED.swap(false, Ordering::SeqCst) {
            self.show_settings(ctx);
        }

        if let Some(action) = self.tray.as_ref().and_then(|t| t.poll_action()) {
            match action {
                TrayAction::ShowSettings => self.show_settings(ctx),
                TrayAction::ToggleFloatSub => {
                    let next = self
                        .shared
                        .lock()
                        .map(|s| !s.float_visible)
                        .unwrap_or(true);
                    self.set_float_visible(ctx, next);
                }
                TrayAction::ToggleLock => {
                    let next = self
                        .shared
                        .lock()
                        .map(|s| !s.config.is_locked)
                        .unwrap_or(false);
                    self.set_locked(ctx, next);
                }
                TrayAction::ToggleCapture => self.toggle_capture(),
                TrayAction::Quit => {
                    platform::stop_hooks();
                    std::process::exit(0);
                }
            }
        }

        if ctx.input(|i| i.viewport().close_requested()) {
            let minimize_to_tray = self
                .shared
                .lock()
                .map(|s| s.config.minimize_to_tray_on_close)
                .unwrap_or(true);
            if minimize_to_tray {
                ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
                self.hide_settings_to_tray(ctx);
            } else {
                platform::stop_hooks();
                std::process::exit(0);
            }
        }

        let capturing = self.is_capturing_flag.load(Ordering::Relaxed);
        let mut audio_updated = false;
        if let Some(rx) = &mut self.audio_rx {
            let mut latest_rms: Option<f32> = None;
            while let Ok(samples) = rx.try_recv() {
                if !samples.is_empty() {
                    let sum_sq: f32 = samples.iter().map(|s| s * s).sum();
                    latest_rms = Some((sum_sq / samples.len() as f32).sqrt());
                    if let Some(pipe_tx) = &self.pipeline_audio_tx {
                        let _ = pipe_tx.try_send(samples);
                    }
                }
            }
            if let Some(rms) = latest_rms {
                audio_updated = true;
                let db = if rms > 1e-6 { 20.0 * rms.log10() } else { -120.0 };
                let norm = ((db + 60.0) / 60.0).clamp(0.0, 1.0);
                if let Ok(mut st) = self.shared.lock() {
                    st.audio_level = norm;
                    st.audio_db = db;
                }
            }
        }

        if let Some(ev_rx) = &self.pipeline_event_rx {
            while let Ok(ev) = ev_rx.try_recv() {
                audio_updated = true;
                if let Ok(mut st) = self.shared.lock() {
                    match ev {
                        PipelineEvent::Partial(p) => {
                            st.partial = Some(p);
                        }
                        PipelineEvent::Warning(w) => {
                            st.capture_warning = Some(w);
                        }
                        PipelineEvent::Utterance(u) => {
                            st.partial = None;
                            st.capture_warning = None;
                            let block = SubtitleBlock {
                                id: u.id,
                                kind: u.kind,
                                source_text: u.source,
                                source_lang: u.source_lang,
                                target_lang: u.target_lang,
                                translation_text: u.translation,
                                is_newest: true,
                                failed: u.translation_failed,
                            };
                            for b in &mut st.blocks {
                                b.is_newest = false;
                            }
                            st.blocks.push(block.clone());
                            if st.blocks.len() > 2 {
                                let drain_n = st.blocks.len() - 2;
                                st.blocks.drain(..drain_n);
                            }
                            st.history.push(block);
                        }
                    }
                }
            }
        }

        if audio_updated {
            ctx.request_repaint_of(self.float_viewport_id);
        }

        if self.settings_visible {
            self.render_settings_ui(ctx);
        }

        let float_visible = self
            .shared
            .lock()
            .map(|s| s.float_visible)
            .unwrap_or(true);
        if float_visible {
            let shared_clone = self.shared.clone();
            let builder = ViewportBuilder::default()
                .with_title("VoiceTrans Subtitle")
                .with_decorations(false)
                .with_transparent(true)
                .with_always_on_top()
                .with_taskbar(false)
                .with_inner_size([680.0, 130.0]);

            ctx.show_viewport_deferred(self.float_viewport_id, builder, move |ctx, _class| {
                render_float_sub_viewport(ctx, &shared_clone);
            });
        }

        let hold_dragging = platform::is_hold_dragging();
        let repaint_ms = if capturing || hold_dragging { 50 } else { 250 };
        ctx.request_repaint_after(std::time::Duration::from_millis(repaint_ms));
    }
}

fn saas_card(ui: &mut egui::Ui, add_contents: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(BG_CARD)
        .rounding(Rounding::same(10.0))
        .stroke(Stroke::new(1.0_f32, BORDER_SUBTLE))
        .inner_margin(egui::Margin::same(14.0))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            add_contents(ui);
        });
}

fn saas_pill_button(ui: &mut egui::Ui, label: &str, selected: bool) -> egui::Response {
    let fill = if selected { ACCENT_PRIMARY } else { BG_ELEVATED };
    let stroke = if selected {
        Stroke::new(1.0_f32, ACCENT_PRIMARY)
    } else {
        Stroke::new(1.0_f32, BORDER_SUBTLE)
    };
    let text_color = if selected { Color32::WHITE } else { TEXT_PRIMARY };
    ui.add(
        egui::Button::new(RichText::new(label).size(12.5).color(text_color))
            .fill(fill)
            .stroke(stroke)
            .rounding(Rounding::same(6.0)),
    )
}

impl VoiceTransApp {
    fn render_settings_ui(&mut self, ctx: &egui::Context) {
        let mut visuals = egui::Visuals::dark();
        visuals.panel_fill = BG_CANVAS;
        visuals.widgets.noninteractive.bg_fill = BG_ELEVATED;
        visuals.widgets.inactive.bg_fill = BG_ELEVATED;
        visuals.widgets.hovered.bg_fill = Color32::from_rgb(35, 42, 58);
        visuals.selection.bg_fill = ACCENT_PRIMARY;
        ctx.set_visuals(visuals);

        let snapshot = self.shared.lock().unwrap().clone();

        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(BG_CANVAS).inner_margin(egui::Margin::same(18.0)))
            .show(ctx, |ui| {
                ui.style_mut().wrap_mode = Some(egui::TextWrapMode::Wrap);
                // Header bar
                ui.horizontal(|ui| {
                    ui.vertical(|ui| {
                        ui.label(
                            RichText::new("VoiceTrans VI")
                                .size(20.0)
                                .strong()
                                .color(TEXT_PRIMARY),
                        );
                        ui.label(
                            RichText::new("Offline Live Subtitle & Translation")
                                .size(12.0)
                                .color(TEXT_SECONDARY),
                        );
                    });

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        let (dot_color, status_text) = if snapshot.is_capturing {
                            (STATUS_GREEN, "Đang thu âm")
                        } else {
                            (TEXT_SECONDARY, "Sẵn sàng")
                        };
                        egui::Frame::none()
                            .fill(BG_CARD)
                            .rounding(Rounding::same(14.0))
                            .stroke(Stroke::new(1.0_f32, BORDER_SUBTLE))
                            .inner_margin(egui::Margin::symmetric(10.0, 5.0))
                            .show(ui, |ui| {
                                ui.horizontal(|ui| {
                                    ui.label(RichText::new("●").size(10.0).color(dot_color));
                                    ui.label(
                                        RichText::new(status_text)
                                            .size(12.0)
                                            .strong()
                                            .color(TEXT_PRIMARY),
                                    );
                                });
                            });
                    });
                });

                ui.add_space(10.0);

                // Engine & Model Download Banner (auto-downloads missing ONNX Runtime / SenseVoice / VAD)
                let eng = self
                    .engine_state
                    .lock()
                    .map(|g| g.clone())
                    .unwrap_or(EngineState::Checking);
                match eng {
                    EngineState::Downloading { progress, detail } => {
                        saas_card(ui, |ui| {
                            ui.label(
                                RichText::new(detail)
                                    .size(12.5)
                                    .strong()
                                    .color(ACCENT_SOFT),
                            );
                            ui.add_space(4.0);
                            ui.add(
                                egui::ProgressBar::new(progress.clamp(0.0, 1.0))
                                    .show_percentage()
                                    .fill(ACCENT_PRIMARY),
                            );
                        });
                        ui.add_space(8.0);
                    }
                    EngineState::Missing(missing_list) => {
                        saas_card(ui, |ui| {
                            ui.horizontal_wrapped(|ui| {
                                ui.label(
                                    RichText::new(format!(
                                        "Thiếu file Engine/Model: {}",
                                        missing_list.join(", ")
                                    ))
                                    .size(12.5)
                                    .color(Color32::from_rgb(252, 165, 165)),
                                );
                                if saas_pill_button(ui, "Tải lại Model ngay", true).clicked() {
                                    model_manager::spawn_download_missing(
                                        self.models_dir.clone(),
                                        self.engine_state.clone(),
                                        || {
                                            if let Some(ctx) = EGUI_CTX.get() {
                                                ctx.request_repaint();
                                            }
                                        },
                                    );
                                }
                            });
                        });
                        ui.add_space(8.0);
                    }
                    EngineState::Error(err) => {
                        saas_card(ui, |ui| {
                            ui.horizontal(|ui| {
                                ui.label(
                                    RichText::new(format!("Lỗi tải Engine: {err}"))
                                        .size(12.5)
                                        .color(Color32::from_rgb(252, 165, 165)),
                                );
                                if saas_pill_button(ui, "Tải lại", false).clicked() {
                                    model_manager::spawn_download_missing(
                                        self.models_dir.clone(),
                                        self.engine_state.clone(),
                                        || {
                                            if let Some(ctx) = EGUI_CTX.get() {
                                                ctx.request_repaint();
                                            }
                                        },
                                    );
                                }
                            });
                        });
                        ui.add_space(8.0);
                    }
                    _ => {}
                }

                // SaaS Navigation Tabs
                ui.horizontal(|ui| {
                    if saas_pill_button(
                        ui,
                        &format!("Cuộc trò chuyện ({})", snapshot.history.len()),
                        self.active_tab == MainTab::Transcript,
                    )
                    .clicked()
                    {
                        self.active_tab = MainTab::Transcript;
                    }
                    if saas_pill_button(ui, "Cài đặt", self.active_tab == MainTab::Settings)
                        .clicked()
                    {
                        self.active_tab = MainTab::Settings;
                        let missing = model_manager::check_missing_components(&self.models_dir);
                        if let Ok(mut g) = self.engine_state.lock() {
                            if !missing.is_empty() && !matches!(*g, EngineState::Downloading { .. }) {
                                *g = EngineState::Missing(missing);
                            } else if missing.is_empty() && matches!(*g, EngineState::Missing(_)) {
                                *g = EngineState::Ready;
                            }
                        }
                    }
                });

                ui.add_space(10.0);

                if self.active_tab == MainTab::Transcript {
                    self.render_transcript_tab(ctx, ui, &snapshot);
                    return;
                }

                egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                // Card 1: Session & Capture
                saas_card(ui, |ui| {
                    ui.label(
                        RichText::new("Phiên thu âm & Phụ đề")
                            .size(13.5)
                            .strong()
                            .color(TEXT_PRIMARY),
                    );
                    ui.add_space(8.0);
                    ui.horizontal_wrapped(|ui| {
                        let (btn_text, btn_fill) = if snapshot.is_capturing {
                            ("Dừng thu âm", DANGER_RED)
                        } else {
                            ("Bắt đầu thu âm", ACCENT_PRIMARY)
                        };
                        if ui
                            .add(
                                egui::Button::new(
                                    RichText::new(btn_text)
                                        .size(13.0)
                                        .strong()
                                        .color(Color32::WHITE),
                                )
                                .fill(btn_fill)
                                .rounding(Rounding::same(6.0))
                                .min_size(egui::vec2(130.0, 32.0)),
                            )
                            .clicked()
                        {
                            self.toggle_capture();
                        }

                        if saas_pill_button(ui, "Nạp phụ đề mẫu", false).clicked() {
                            self.load_demo_blocks();
                        }
                        if saas_pill_button(ui, "Xóa phụ đề", false).clicked() {
                            if let Ok(mut st) = self.shared.lock() {
                                st.blocks.clear();
                                st.history.clear();
                                st.partial = None;
                            }
                        }
                    });
                    if snapshot.is_capturing {
                        ui.add_space(8.0);
                        ui.horizontal(|ui| {
                            ui.label(
                                RichText::new("Mức âm thanh:")
                                    .size(12.0)
                                    .color(TEXT_SECONDARY),
                            );
                            ui.add(
                                egui::ProgressBar::new(snapshot.audio_level)
                                    .desired_width(220.0)
                                    .fill(if snapshot.audio_level > 0.1 {
                                        STATUS_GREEN
                                    } else {
                                        ACCENT_PRIMARY
                                    }),
                            );
                            ui.label(
                                RichText::new(format!("{:.0} dBFS", snapshot.audio_db))
                                    .size(12.0)
                                    .color(TEXT_PRIMARY),
                            );
                        });
                    }
                });

                ui.add_space(10.0);

                // Card 2: Floating Subtitle Settings
                saas_card(ui, |ui| {
                    ui.label(
                        RichText::new("Cấu hình Float Sub (Không nút bấm)")
                            .size(13.5)
                            .strong()
                            .color(TEXT_PRIMARY),
                    );
                    ui.add_space(6.0);

                    let mut show_float = snapshot.float_visible;
                    if ui
                        .checkbox(&mut show_float, "Hiển thị Float Sub trên màn hình")
                        .changed()
                    {
                        self.set_float_visible(ctx, show_float);
                    }

                    let mut locked = snapshot.config.is_locked;
                    if ui
                        .checkbox(
                            &mut locked,
                            "Khóa click-xuyên-qua (nhấn giữ chuột >= 350 ms trên sub để di chuyển)",
                        )
                        .changed()
                    {
                        self.set_locked(ctx, locked);
                    }

                    let mut minimize_to_tray = snapshot.config.minimize_to_tray_on_close;
                    if ui
                        .checkbox(
                            &mut minimize_to_tray,
                            "Thu gọn xuống Tray khi đóng cửa sổ (tắt chế độ này để thoát hoàn toàn khi đóng)",
                        )
                        .changed()
                    {
                        if let Ok(mut st) = self.shared.lock() {
                            st.config.minimize_to_tray_on_close = minimize_to_tray;
                            st.config.save();
                        }
                    }

                    ui.add_space(8.0);
                    ui.horizontal_wrapped(|ui| {
                        ui.label(RichText::new("Chế độ:").size(12.5).color(TEXT_SECONDARY));
                        for mode in [
                            OverlayMode::Bilingual,
                            OverlayMode::TranslationOnly,
                            OverlayMode::SourceOnly,
                        ] {
                            if saas_pill_button(ui, mode.label(), snapshot.config.mode == mode)
                                .clicked()
                            {
                                if let Ok(mut st) = self.shared.lock() {
                                    st.config.mode = mode;
                                    st.config.save();
                                }
                                ctx.request_repaint_of(self.float_viewport_id);
                            }
                        }
                    });

                    ui.add_space(6.0);
                    ui.horizontal_wrapped(|ui| {
                        ui.label(RichText::new("Thứ tự:").size(12.5).color(TEXT_SECONDARY));
                        if saas_pill_button(
                            ui,
                            "Gốc trên / Dịch dưới",
                            snapshot.config.line_order == LineOrder::SourceTop,
                        )
                        .clicked()
                        {
                            if let Ok(mut st) = self.shared.lock() {
                                st.config.line_order = LineOrder::SourceTop;
                                st.config.save();
                            }
                            ctx.request_repaint_of(self.float_viewport_id);
                        }
                        if saas_pill_button(
                            ui,
                            "Dịch trên / Gốc dưới",
                            snapshot.config.line_order == LineOrder::TranslationTop,
                        )
                        .clicked()
                        {
                            if let Ok(mut st) = self.shared.lock() {
                                st.config.line_order = LineOrder::TranslationTop;
                                st.config.save();
                            }
                            ctx.request_repaint_of(self.float_viewport_id);
                        }
                    });

                    ui.add_space(8.0);
                    let mut text_size = snapshot.config.text_size;
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Cỡ chữ:").size(12.5).color(TEXT_SECONDARY));
                        if ui
                            .add(egui::Slider::new(&mut text_size, 14.0..=48.0).suffix(" px"))
                            .changed()
                        {
                            if let Ok(mut st) = self.shared.lock() {
                                st.config.text_size = text_size;
                                st.config.save();
                            }
                            ctx.request_repaint_of(self.float_viewport_id);
                        }
                    });

                    let mut bg_opacity = snapshot.config.bg_opacity;
                    ui.horizontal(|ui| {
                        ui.label(RichText::new("Độ mờ nền:").size(12.5).color(TEXT_SECONDARY));
                        if ui
                            .add(egui::Slider::new(&mut bg_opacity, 0.10..=0.95))
                            .changed()
                        {
                            if let Ok(mut st) = self.shared.lock() {
                                st.config.bg_opacity = bg_opacity;
                                st.config.save();
                            }
                            platform::set_float_opacity(bg_opacity);
                            ctx.request_repaint_of(self.float_viewport_id);
                        }
                    });
                });

                ui.add_space(10.0);

                // Card 3: Engine, Model & Languages
                saas_card(ui, |ui| {
                    ui.horizontal_wrapped(|ui| {
                        ui.label(
                            RichText::new("Engine & Ngôn ngữ dịch")
                                .size(13.5)
                                .strong()
                                .color(TEXT_PRIMARY),
                        );
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if saas_pill_button(ui, "Kiểm tra & Nạp lại Model", false).clicked() {
                                let missing = model_manager::check_missing_components(&self.models_dir);
                                if missing.is_empty() {
                                    if let Ok(mut g) = self.engine_state.lock() {
                                        *g = EngineState::Ready;
                                    }
                                } else {
                                    if let Ok(mut g) = self.engine_state.lock() {
                                        *g = EngineState::Downloading {
                                            progress: 0.0,
                                            detail: "Đang chuẩn bị tải lại model bị thiếu...".into(),
                                        };
                                    }
                                    model_manager::spawn_download_missing(
                                        self.models_dir.clone(),
                                        self.engine_state.clone(),
                                        || {
                                            if let Some(ctx) = EGUI_CTX.get() {
                                                ctx.request_repaint();
                                            }
                                        },
                                    );
                                }
                            }
                        });
                    });
                    ui.add_space(6.0);
                    let ort_ok = model_manager::resolve_ort_lib_path(&self.models_dir).exists();
                    let vad_ok = self.models_dir.join("vad/silero_vad.onnx").exists();
                    let asr_ok = self.models_dir.join("asr-sv/model.int8.onnx").exists()
                        && self.models_dir.join("asr-sv/tokens.txt").exists();

                    ui.horizontal_wrapped(|ui| {
                        for (label, ok) in [
                            ("ONNX Runtime", ort_ok),
                            ("Silero VAD", vad_ok),
                            ("SenseVoice ASR", asr_ok),
                        ] {
                            let color = if ok { STATUS_GREEN } else { DANGER_RED };
                            let state_txt = if ok { "OK" } else { "Thiếu" };
                            ui.label(
                                RichText::new(format!("{label}: {state_txt}"))
                                    .size(12.0)
                                    .color(color),
                            );
                        }
                    });
                    ui.label(
                        RichText::new(format!("Thư mục model: {}", self.models_dir.display()))
                            .size(11.5)
                            .color(TEXT_SECONDARY),
                    );
                    ui.add_space(6.0);
                    ui.horizontal_wrapped(|ui| {
                        ui.label(
                            RichText::new("Dịch sang:")
                                .size(12.5)
                                .color(TEXT_SECONDARY),
                        );
                        for (code, name) in [
                            ("vi", "Tiếng Việt"),
                            ("en", "Tiếng Anh"),
                            ("zh-CN", "Tiếng Trung"),
                            ("ja", "Tiếng Nhật"),
                            ("ko", "Tiếng Hàn"),
                        ] {
                            if saas_pill_button(ui, name, snapshot.config.target_lang == code)
                                .clicked()
                            {
                                if let Ok(mut st) = self.shared.lock() {
                                    st.config.target_lang = code.to_string();
                                    st.config.save();
                                }
                            }
                        }
                    });
                });

                ui.add_space(10.0);

                // Card 4: Multi-Monitor
                saas_card(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.label(
                            RichText::new("Đa màn hình (Multi-Monitor)")
                                .size(13.5)
                                .strong()
                                .color(TEXT_PRIMARY),
                        );
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if saas_pill_button(ui, "Quét lại", false).clicked() {
                                if let Ok(mut st) = self.shared.lock() {
                                    st.monitors = platform::enumerate_monitors();
                                }
                            }
                        });
                    });
                    ui.add_space(6.0);
                    ui.horizontal_wrapped(|ui| {
                        for (idx, mon) in snapshot.monitors.iter().enumerate() {
                            let label = format!(
                                "{} · {}x{}",
                                mon.name,
                                mon.work_area.width(),
                                mon.work_area.height()
                            );
                            if saas_pill_button(ui, &label, false).clicked() {
                                self.move_to_monitor(idx);
                            }
                        }
                    });
                });

                ui.add_space(14.0);

                // Footer action bar
                ui.horizontal_wrapped(|ui| {
                    if ui
                        .add(
                            egui::Button::new(
                                RichText::new("Thu gọn xuống Tray (Chỉ hiện Float Sub)")
                                    .size(13.0)
                                    .strong()
                                    .color(ACCENT_SOFT),
                            )
                            .fill(BG_CARD)
                            .stroke(Stroke::new(1.0_f32, BORDER_SUBTLE))
                            .rounding(Rounding::same(6.0))
                            .min_size(egui::vec2(0.0, 32.0)),
                        )
                        .clicked()
                    {
                        self.set_float_visible(ctx, true);
                        self.hide_settings_to_tray(ctx);
                    }

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui
                            .add(
                                egui::Button::new(
                                    RichText::new("Thoát")
                                        .size(13.0)
                                        .color(Color32::from_rgb(252, 165, 165)),
                                )
                                .fill(Color32::from_rgb(45, 22, 26))
                                .stroke(Stroke::new(1.0_f32, Color32::from_rgb(90, 38, 44)))
                                .rounding(Rounding::same(6.0))
                                .min_size(egui::vec2(84.0, 32.0)),
                            )
                            .clicked()
                        {
                            platform::stop_hooks();
                            std::process::exit(0);
                        }
                    });
                });
                }); // ScrollArea
            });
    }

    fn render_transcript_tab(
        &mut self,
        ctx: &egui::Context,
        ui: &mut egui::Ui,
        snapshot: &SharedOverlayState,
    ) {
        // Toolbar card
        saas_card(ui, |ui| {
            ui.horizontal_wrapped(|ui| {
                let (btn_text, btn_fill) = if snapshot.is_capturing {
                    ("Dừng thu âm", DANGER_RED)
                } else {
                    ("Bắt đầu thu âm", ACCENT_PRIMARY)
                };
                if ui
                    .add(
                        egui::Button::new(
                            RichText::new(btn_text)
                                .size(13.0)
                                .strong()
                                .color(Color32::WHITE),
                        )
                        .fill(btn_fill)
                        .rounding(Rounding::same(6.0))
                        .min_size(egui::vec2(124.0, 30.0)),
                    )
                    .clicked()
                {
                    self.toggle_capture();
                }

                if saas_pill_button(ui, "Nạp mẫu", false).clicked() {
                    self.load_demo_blocks();
                }

                if saas_pill_button(ui, "Sao chép tất cả", false).clicked() {
                    let mut text = String::new();
                    for b in &snapshot.history {
                        text.push_str(&format!("[{}] {}\n", b.source_lang.code(), b.source_text));
                        if let Some(tr) = &b.translation_text {
                            text.push_str(&format!("-> {tr}\n"));
                        }
                        text.push('\n');
                    }
                    ctx.copy_text(text);
                    self.export_notice = Some("Đã sao chép toàn bộ cuộc trò chuyện vào clipboard.".into());
                }

                if saas_pill_button(ui, "Xuất .txt", false).clicked() {
                    let mut text = String::new();
                    for b in &snapshot.history {
                        text.push_str(&format!("[{}] {}\n", b.source_lang.code(), b.source_text));
                        if let Some(tr) = &b.translation_text {
                            text.push_str(&format!("-> {tr}\n"));
                        }
                        text.push('\n');
                    }
                    if let Some(user_dirs) = directories::UserDirs::new() {
                        let out_dir = user_dirs
                            .document_dir()
                            .unwrap_or_else(|| user_dirs.home_dir());
                        let out_path = out_dir.join("VoiceTrans_Transcript.txt");
                        if std::fs::write(&out_path, text).is_ok() {
                            self.export_notice = Some(format!("Đã lưu: {}", out_path.display()));
                        }
                    }
                }

                if saas_pill_button(ui, "Xóa", false).clicked() {
                    if let Ok(mut st) = self.shared.lock() {
                        st.blocks.clear();
                        st.history.clear();
                        st.partial = None;
                    }
                    self.export_notice = None;
                }
            });

            if snapshot.is_capturing {
                ui.add_space(6.0);
                ui.horizontal(|ui| {
                    ui.label(RichText::new("Tín hiệu:").size(12.0).color(TEXT_SECONDARY));
                    ui.add(
                        egui::ProgressBar::new(snapshot.audio_level)
                            .desired_width(220.0)
                            .fill(if snapshot.audio_level > 0.1 {
                                STATUS_GREEN
                            } else {
                                ACCENT_PRIMARY
                            }),
                    );
                    ui.label(
                        RichText::new(format!("{:.0} dBFS", snapshot.audio_db))
                            .size(12.0)
                            .color(TEXT_PRIMARY),
                    );
                });
            }

            if let Some(msg) = &self.export_notice {
                ui.add_space(4.0);
                ui.label(RichText::new(msg).size(12.0).color(ACCENT_SOFT));
            }
        });

        ui.add_space(10.0);

        // Full conversation history area
        let avail_h = (ui.available_height() - 48.0).max(160.0);
        egui::Frame::none()
            .fill(BG_CARD)
            .rounding(Rounding::same(10.0))
            .stroke(Stroke::new(1.0_f32, BORDER_SUBTLE))
            .inner_margin(egui::Margin::same(14.0))
            .show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                ui.set_min_height(avail_h);
                egui::ScrollArea::vertical()
                    .max_height(avail_h)
                    .stick_to_bottom(true)
                    .show(ui, |ui| {
                        if snapshot.history.is_empty() && snapshot.partial.is_none() {
                            ui.label(
                                RichText::new(
                                    "Chưa có nội dung cuộc trò chuyện. Bấm 'Bắt đầu thu âm' hoặc 'Nạp mẫu' để xem toàn bộ lịch sử tại đây.",
                                )
                                .size(13.0)
                                .color(TEXT_SECONDARY),
                            );
                        } else {
                            for block in &snapshot.history {
                                egui::Frame::none()
                                    .fill(BG_ELEVATED)
                                    .rounding(Rounding::same(8.0))
                                    .stroke(Stroke::new(1.0_f32, BORDER_SUBTLE))
                                    .inner_margin(egui::Margin::same(10.0))
                                    .show(ui, |ui| {
                                        ui.set_min_width(ui.available_width());
                                        let mut preview_cfg = snapshot.config.clone();
                                        preview_cfg.text_size = 16.0;
                                        preview_cfg.mode = OverlayMode::Bilingual;
                                        render_block(ui, block, &preview_cfg);
                                    });
                                ui.add_space(6.0);
                            }
                            if let Some(p) = &snapshot.partial {
                                ui.label(
                                    RichText::new(p)
                                        .italics()
                                        .size(13.0)
                                        .color(ACCENT_SOFT),
                                );
                            }
                        }
                    });
            });

        ui.add_space(10.0);
        ui.horizontal(|ui| {
            if ui
                .add(
                    egui::Button::new(
                        RichText::new("Thu gọn xuống Tray (Chỉ hiện Float Sub)")
                            .size(13.0)
                            .strong()
                            .color(ACCENT_SOFT),
                    )
                    .fill(BG_CARD)
                    .stroke(Stroke::new(1.0_f32, BORDER_SUBTLE))
                    .rounding(Rounding::same(6.0))
                    .min_size(egui::vec2(0.0, 32.0)),
                )
                .clicked()
            {
                self.set_float_visible(ctx, true);
                self.hide_settings_to_tray(ctx);
            }
        });
    }
}

/// Pure Float Sub Viewport — ZERO buttons, press-and-hold drag in any mode.
fn render_float_sub_viewport(ctx: &egui::Context, shared: &Arc<Mutex<SharedOverlayState>>) {
    let mut snapshot = shared.lock().unwrap().clone();

    if !snapshot.hwnd_initialized {
        if let Ok(mut st) = shared.lock() {
            st.hwnd_initialized = true;
        }
        platform::init_float_overlay_window(
            ctx,
            "VoiceTrans Subtitle",
            snapshot.config.is_locked,
            snapshot.config.bg_opacity,
        );
    }

    if let Some((px, py)) = snapshot.pending_position.take() {
        if let Ok(mut st) = shared.lock() {
            st.pending_position = None;
        }
        ctx.send_viewport_cmd(egui::ViewportCommand::OuterPosition(egui::pos2(
            px as f32, py as f32,
        )));
    }

    let hold_dragging = platform::is_hold_dragging();
    let bg_alpha = (snapshot.config.bg_opacity.clamp(0.1, 0.95) * 255.0) as u8;
    let border_color = if hold_dragging {
        ACCENT_SOFT
    } else if snapshot.config.is_locked {
        Color32::from_rgba_unmultiplied(255, 255, 255, 24)
    } else {
        Color32::from_rgba_unmultiplied(255, 255, 255, 52)
    };

    let frame_style = egui::Frame::none()
        .fill(Color32::from_rgba_unmultiplied(11, 13, 18, bg_alpha))
        .rounding(Rounding::same(12.0))
        .stroke(Stroke::new(
            if hold_dragging { 2.0_f32 } else { 1.0_f32 },
            border_color,
        ))
        .inner_margin(egui::Margin::same(14.0));

    let mut visuals = egui::Visuals::dark();
    visuals.window_fill = Color32::TRANSPARENT;
    visuals.panel_fill = Color32::TRANSPARENT;
    ctx.set_visuals(visuals);

    egui::CentralPanel::default()
        .frame(egui::Frame::none())
        .show(ctx, |ui| {
            ui.style_mut().wrap_mode = Some(egui::TextWrapMode::Wrap);
            let frame_resp = frame_style.show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                if let Some(warn) = &snapshot.capture_warning {
                    ui.add(
                        egui::Label::new(
                            RichText::new(warn)
                                .size(snapshot.config.text_size * 0.7)
                                .color(Color32::from_rgb(252, 165, 165)),
                        )
                        .wrap(),
                    );
                }

                if snapshot.blocks.is_empty() && snapshot.partial.is_none() {
                    let status = if snapshot.is_capturing {
                        "Đang lắng nghe âm thanh hệ thống..."
                    } else {
                        "VoiceTrans sẵn sàng — nhấn giữ chuột để di chuyển"
                    };
                    ui.add(
                        egui::Label::new(
                            RichText::new(status)
                                .size(snapshot.config.text_size * 0.72)
                                .color(TEXT_SECONDARY),
                        )
                        .wrap(),
                    );
                } else {
                    for block in &snapshot.blocks {
                        render_block(ui, block, &snapshot.config);
                    }
                    if let Some(p) = &snapshot.partial {
                        ui.add(
                            egui::Label::new(
                                RichText::new(p)
                                    .italics()
                                    .size(snapshot.config.text_size * 0.65)
                                    .color(TEXT_SECONDARY),
                            )
                            .wrap(),
                        );
                    }
                }
            });

            // Auto-fit Float Sub window height so wrapped subtitles are never clipped
            let desired_h = (frame_resp.response.rect.height() + 2.0).clamp(86.0, 420.0);
            let current_size = ctx.input(|i| i.screen_rect().size());
            if (desired_h - current_size.y).abs() > 4.0 {
                ctx.send_viewport_cmd(egui::ViewportCommand::InnerSize(egui::vec2(
                    current_size.x.max(680.0),
                    desired_h,
                )));
            }
        });
}

fn render_block(ui: &mut egui::Ui, block: &SubtitleBlock, config: &AppConfig) {
    ui.style_mut().wrap_mode = Some(egui::TextWrapMode::Wrap);
    if block.kind == UtteranceKind::Music {
        ui.add(
            egui::Label::new(
                RichText::new("♪ Đang phát nhạc")
                    .size(config.text_size * 0.75)
                    .color(Color32::from_rgb(196, 168, 255)),
            )
            .wrap(),
        );
        return;
    }

    let show_source = config.mode != OverlayMode::TranslationOnly;
    let show_translation = config.mode != OverlayMode::SourceOnly;

    let render_source = |ui: &mut egui::Ui| {
        ui.horizontal_wrapped(|ui| {
            let badge_bg = match block.source_lang {
                SourceLang::En => ACCENT_PRIMARY,
                SourceLang::Zh => ACCENT_SOFT,
            };
            egui::Frame::none()
                .fill(badge_bg)
                .rounding(Rounding::same(3.0))
                .inner_margin(egui::Margin::symmetric(5.0, 1.0))
                .show(ui, |ui| {
                    ui.label(
                        RichText::new(block.source_lang.code())
                            .font(FontId::proportional((config.text_size * 0.45).max(10.0)))
                            .strong()
                            .color(Color32::WHITE),
                    );
                });
            ui.add(
                egui::Label::new(
                    RichText::new(&block.source_text)
                        .size(config.text_size * 0.65)
                        .color(Color32::from_rgb(180, 190, 210)),
                )
                .wrap(),
            );
        });
    };

    let render_translation = |ui: &mut egui::Ui| {
        let text = if let Some(tr) = &block.translation_text {
            RichText::new(tr)
                .size(config.text_size)
                .color(Color32::from_rgb(235, 240, 248))
        } else if block.failed {
            RichText::new("Lỗi dịch")
                .size(config.text_size * 0.8)
                .color(Color32::from_rgb(252, 165, 165))
        } else {
            RichText::new("…")
                .size(config.text_size)
                .color(TEXT_SECONDARY)
        };
        ui.add(egui::Label::new(text).wrap());
    };

    match config.line_order {
        LineOrder::SourceTop => {
            if show_source {
                render_source(ui);
            }
            if show_translation {
                render_translation(ui);
            }
        }
        LineOrder::TranslationTop => {
            if show_translation {
                render_translation(ui);
            }
            if show_source {
                render_source(ui);
            }
        }
    }
}

#[allow(dead_code)]
fn _keep_bilingual_lines_referenced() {
    let _ = BilingualLines::blocks(&[], 2);
}
