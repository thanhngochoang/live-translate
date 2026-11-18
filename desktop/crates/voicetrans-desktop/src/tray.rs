use std::sync::mpsc;
use tray_icon::{
    menu::{Menu, MenuEvent, MenuId, MenuItem, PredefinedMenuItem},
    Icon, MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrayAction {
    ShowSettings,
    ToggleFloatSub,
    ToggleLock,
    ToggleCapture,
    Quit,
}

pub struct AppTray {
    _tray: TrayIcon,
    rx: mpsc::Receiver<TrayAction>,
}

impl AppTray {
    pub fn new(on_wake: impl Fn(TrayAction) + Send + Sync + Clone + 'static) -> Option<Self> {
        let menu = Menu::new();
        let item_settings = MenuItem::new("Mở bảng điều khiển", true, None);
        let item_float = MenuItem::new("Hiện / Ẩn Float Sub", true, None);
        let item_lock = MenuItem::new("Khóa / Mở khóa Float Sub", true, None);
        let item_capture = MenuItem::new("Bắt đầu / Dừng thu âm", true, None);
        let item_quit = MenuItem::new("Thoát VoiceTrans VI", true, None);

        let _ = menu.append(&item_settings);
        let _ = menu.append(&item_float);
        let _ = menu.append(&item_lock);
        let _ = menu.append(&item_capture);
        let _ = menu.append(&PredefinedMenuItem::separator());
        let _ = menu.append(&item_quit);

        let icon = match build_default_icon() {
            Ok(i) => i,
            Err(e) => {
                eprintln!("[tray] build_default_icon failed: {e}");
                return None;
            }
        };
        let tray = match TrayIconBuilder::new()
            .with_tooltip("VoiceTrans VI — Dịch phụ đề trực tiếp")
            .with_icon(icon)
            .with_menu(Box::new(menu))
            .build()
        {
            Ok(t) => t,
            Err(e) => {
                eprintln!("[tray] TrayIconBuilder::build failed: {e}");
                return None;
            }
        };

        let (tx, rx) = mpsc::channel::<TrayAction>();

        let tx_tray = tx.clone();
        let wake_tray = on_wake.clone();
        TrayIconEvent::set_event_handler(Some(move |ev: TrayIconEvent| {
            let should_open = matches!(
                ev,
                TrayIconEvent::DoubleClick {
                    button: MouseButton::Left,
                    ..
                } | TrayIconEvent::Click {
                    button: MouseButton::Left,
                    button_state: MouseButtonState::Up,
                    ..
                }
            );
            if should_open {
                let _ = tx_tray.send(TrayAction::ShowSettings);
                wake_tray(TrayAction::ShowSettings);
            }
        }));

        let id_settings: MenuId = item_settings.id().clone();
        let id_float: MenuId = item_float.id().clone();
        let id_lock: MenuId = item_lock.id().clone();
        let id_capture: MenuId = item_capture.id().clone();
        let id_quit: MenuId = item_quit.id().clone();

        MenuEvent::set_event_handler(Some(move |ev: MenuEvent| {
            let action = if ev.id == id_settings {
                Some(TrayAction::ShowSettings)
            } else if ev.id == id_float {
                Some(TrayAction::ToggleFloatSub)
            } else if ev.id == id_lock {
                Some(TrayAction::ToggleLock)
            } else if ev.id == id_capture {
                Some(TrayAction::ToggleCapture)
            } else if ev.id == id_quit {
                Some(TrayAction::Quit)
            } else {
                None
            };
            if let Some(act) = action {
                let _ = tx.send(act);
                on_wake(act);
            }
        }));

        Some(Self { _tray: tray, rx })
    }

    pub fn poll_action(&self) -> Option<TrayAction> {
        self.rx.try_recv().ok()
    }
}

fn build_default_icon() -> Result<Icon, tray_icon::BadIcon> {
    if let Ok(app_ico) = eframe::icon_data::from_png_bytes(include_bytes!(
        "../../../../app/src/main/res/mipmap-mdpi/ic_launcher.png"
    )) {
        return Icon::from_rgba(app_ico.rgba, app_ico.width, app_ico.height);
    }
    let width = 32u32;
    let height = 32u32;
    let rgba = vec![255u8; (width * height * 4) as usize];
    Icon::from_rgba(rgba, width, height)
}
