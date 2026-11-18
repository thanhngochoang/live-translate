use std::mem::size_of;
use voicetrans_core::overlay::{MonitorInfo, MonitorRect};
use windows::Win32::Foundation::{BOOL, LPARAM, RECT};
use windows::Win32::Graphics::Gdi::{
    EnumDisplayMonitors, GetMonitorInfoW, HDC, HMONITOR, MONITORINFOEXW,
};
use windows::Win32::UI::HiDpi::{
    GetDpiForMonitor, MDT_EFFECTIVE_DPI, SetProcessDpiAwarenessContext,
    DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2,
};

const MONITORINFOF_PRIMARY: u32 = 1;

pub fn enable_per_monitor_v2_dpi() {
    unsafe {
        let _ = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
}

pub fn enumerate_monitors() -> Vec<MonitorInfo> {
    let mut monitors: Vec<MonitorInfo> = Vec::new();
    unsafe {
        let ptr = &mut monitors as *mut Vec<MonitorInfo>;
        let _ = EnumDisplayMonitors(
            HDC::default(),
            None,
            Some(enum_monitor_proc),
            LPARAM(ptr as isize),
        );
    }
    monitors
}

unsafe extern "system" fn enum_monitor_proc(
    hmonitor: HMONITOR,
    _hdc: HDC,
    _rect: *mut RECT,
    lparam: LPARAM,
) -> BOOL {
    let monitors = &mut *(lparam.0 as *mut Vec<MonitorInfo>);
    let mut info = MONITORINFOEXW::default();
    info.monitorInfo.cbSize = size_of::<MONITORINFOEXW>() as u32;
    if GetMonitorInfoW(hmonitor, &mut info.monitorInfo).as_bool() {
        let end = info
            .szDevice
            .iter()
            .position(|&c| c == 0)
            .unwrap_or(info.szDevice.len());
        let id = String::from_utf16_lossy(&info.szDevice[..end]);
        let mut dpi_x = 96u32;
        let mut dpi_y = 96u32;
        let _ = GetDpiForMonitor(hmonitor, MDT_EFFECTIVE_DPI, &mut dpi_x, &mut dpi_y);

        let rc = info.monitorInfo.rcWork;
        let is_primary = (info.monitorInfo.dwFlags & MONITORINFOF_PRIMARY) != 0;
        let index = monitors.len() + 1;
        let name = if is_primary {
            format!("Màn hình {index} (Chính)")
        } else {
            format!("Màn hình {index}")
        };

        monitors.push(MonitorInfo {
            id,
            name,
            work_area: MonitorRect {
                left: rc.left,
                top: rc.top,
                right: rc.right,
                bottom: rc.bottom,
            },
            dpi: dpi_x,
            is_primary,
        });
    }
    BOOL(1)
}
