use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::Arc;
use tokio::sync::mpsc;
use voicetrans_core::overlay::MonitorInfo;
use windows::core::PCWSTR;
use windows::Win32::Foundation::{
    CloseHandle, COLORREF, GetLastError, ERROR_ALREADY_EXISTS, HANDLE, HWND, WAIT_OBJECT_0,
};
use windows::Win32::System::Threading::{
    CreateEventW, CreateMutexW, SetEvent, WaitForSingleObject, INFINITE,
};
use windows::Win32::UI::WindowsAndMessaging::{
    FindWindowW, GetWindowLongPtrW, SetForegroundWindow, SetLayeredWindowAttributes,
    SetWindowLongPtrW, ShowWindow, ShowWindowAsync, GWL_EXSTYLE, LWA_ALPHA, SW_HIDE, SW_RESTORE,
    SW_SHOW, WS_EX_LAYERED, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST,
};

static SETTINGS_HWND: AtomicIsize = AtomicIsize::new(0);

use crate::audio_wasapi;
use crate::hold_drag_hook;
use crate::monitors;

pub struct SingleInstanceGuard {
    mutex: HANDLE,
}

impl Drop for SingleInstanceGuard {
    fn drop(&mut self) {
        unsafe {
            if !self.mutex.is_invalid() {
                let _ = CloseHandle(self.mutex);
            }
        }
    }
}

pub fn acquire_single_instance(on_activate: impl Fn() + Send + 'static) -> Option<SingleInstanceGuard> {
    unsafe {
        let mutex_name: Vec<u16> = "Local\\VoiceTrans_SingleInstance_Mutex\0"
            .encode_utf16()
            .collect();
        let event_name: Vec<u16> = "Local\\VoiceTrans_ShowSettings_Event\0"
            .encode_utf16()
            .collect();

        let mutex = CreateMutexW(None, false, PCWSTR(mutex_name.as_ptr())).ok()?;
        let already_running = GetLastError() == ERROR_ALREADY_EXISTS;

        let event = CreateEventW(None, false, false, PCWSTR(event_name.as_ptr())).ok()?;

        if already_running {
            let _ = SetEvent(event);
            let _ = CloseHandle(event);
            let _ = CloseHandle(mutex);
            return None;
        }

        let event_raw = event.0 as isize;
        std::thread::spawn(move || {
            let ev = HANDLE(event_raw as *mut _);
            loop {
                if WaitForSingleObject(ev, INFINITE) == WAIT_OBJECT_0 {
                    on_activate();
                } else {
                    break;
                }
            }
        });

        Some(SingleInstanceGuard { mutex })
    }
}

pub fn enable_dpi_awareness() {
    monitors::enable_per_monitor_v2_dpi();
}

pub fn enumerate_monitors() -> Vec<MonitorInfo> {
    monitors::enumerate_monitors()
}

pub fn configure_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    let windir = std::env::var("WINDIR").unwrap_or_else(|_| "C:\\Windows".to_string());
    let fonts_dir = std::path::Path::new(&windir).join("Fonts");

    let candidates: &[(&str, &str, bool)] = &[
        ("segoeui", "segoeui.ttf", true),
        ("msyh", "msyh.ttc", false),
        ("seguisym", "seguisym.ttf", false),
    ];

    for &(name, filename, primary) in candidates {
        let path = fonts_dir.join(filename);
        if let Ok(bytes) = std::fs::read(&path) {
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
            if let Some(mono) = fonts.families.get_mut(&egui::FontFamily::Monospace) {
                if primary {
                    mono.insert(0, name.to_owned());
                } else {
                    mono.push(name.to_owned());
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
    audio_wasapi::start_wasapi_loopback(running, tx)
}

pub fn init_float_overlay_window(_ctx: &egui::Context, title: &str, locked: bool, opacity: f32) {
    unsafe {
        let wide: Vec<u16> = format!("{title}\0").encode_utf16().collect();
        if let Ok(hwnd) = FindWindowW(None, PCWSTR(wide.as_ptr())) {
            if !hwnd.is_invalid() {
                let ex = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
                let extra = (WS_EX_NOACTIVATE.0
                    | WS_EX_TOOLWINDOW.0
                    | WS_EX_TOPMOST.0
                    | WS_EX_LAYERED.0) as isize;
                SetWindowLongPtrW(hwnd, GWL_EXSTYLE, ex | extra);
                hold_drag_hook::set_overlay_hwnd(hwnd.0 as isize);
                hold_drag_hook::set_locked_state(locked);
                set_float_opacity(opacity);
            }
        }
    }
}

pub fn set_float_opacity(opacity: f32) {
    let raw = hold_drag_hook::overlay_hwnd();
    if raw != 0 {
        let alpha = (opacity.clamp(0.10, 1.0) * 255.0).round() as u8;
        unsafe {
            let _ = SetLayeredWindowAttributes(HWND(raw as *mut _), COLORREF(0), alpha, LWA_ALPHA);
        }
    }
}

pub fn set_float_locked(_ctx: &egui::Context, locked: bool) {
    hold_drag_hook::set_locked_state(locked);
}

pub fn is_hold_dragging() -> bool {
    hold_drag_hook::is_hold_dragging()
}

pub fn stop_hooks() {
    hold_drag_hook::stop_hold_drag_hook();
}

pub fn init_settings_window(title: &str) {
    unsafe {
        let wide: Vec<u16> = format!("{title}\0").encode_utf16().collect();
        if let Ok(hwnd) = FindWindowW(None, PCWSTR(wide.as_ptr())) {
            if !hwnd.is_invalid() {
                SETTINGS_HWND.store(hwnd.0 as isize, Ordering::SeqCst);
            }
        }
    }
}

pub fn show_settings_window_native() {
    let raw = SETTINGS_HWND.load(Ordering::SeqCst);
    if raw != 0 {
        unsafe {
            let hwnd = HWND(raw as *mut _);
            let _ = ShowWindowAsync(hwnd, SW_SHOW);
            let _ = ShowWindowAsync(hwnd, SW_RESTORE);
            let _ = SetForegroundWindow(hwnd);
        }
    }
}

pub fn hide_settings_window_native() {
    let raw = SETTINGS_HWND.load(Ordering::SeqCst);
    if raw != 0 {
        unsafe {
            let _ = ShowWindow(HWND(raw as *mut _), SW_HIDE);
        }
    }
}
