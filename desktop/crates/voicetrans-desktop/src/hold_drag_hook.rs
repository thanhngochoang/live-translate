use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};
use voicetrans_core::overlay::{HoldDragAction, HoldDragState, HoldDragStateMachine};
use windows::Win32::Foundation::{HINSTANCE, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
use windows::Win32::UI::WindowsAndMessaging::{
    CallNextHookEx, DispatchMessageW, GetMessageW, GetWindowLongPtrW, GetWindowRect,
    PostThreadMessageW, SetWindowLongPtrW, SetWindowPos, SetWindowsHookExW, TranslateMessage,
    UnhookWindowsHookEx, GWL_EXSTYLE, HHOOK, MSG, MSLLHOOKSTRUCT, SWP_NOACTIVATE, SWP_NOSIZE,
    SWP_NOZORDER, WH_MOUSE_LL, WM_LBUTTONDOWN, WM_LBUTTONUP, WM_MOUSEMOVE, WM_QUIT,
    WS_EX_TRANSPARENT,
};

static TARGET_HWND: AtomicIsize = AtomicIsize::new(0);
static IS_LOCKED: AtomicBool = AtomicBool::new(false);
static IS_PRESS_ACTIVE: AtomicBool = AtomicBool::new(false);
static IS_HOLD_DRAGGING: AtomicBool = AtomicBool::new(false);
static HOOK_THREAD_ID: AtomicIsize = AtomicIsize::new(0);
static START_INSTANT: OnceLock<Instant> = OnceLock::new();
static STATE_MACHINE: OnceLock<Mutex<HoldDragStateMachine>> = OnceLock::new();

fn now_ms() -> u64 {
    START_INSTANT.get_or_init(Instant::now).elapsed().as_millis() as u64
}

fn state_machine() -> &'static Mutex<HoldDragStateMachine> {
    STATE_MACHINE.get_or_init(|| Mutex::new(HoldDragStateMachine::new(350, 6.0)))
}

pub fn set_overlay_hwnd(hwnd: isize) {
    TARGET_HWND.store(hwnd, Ordering::SeqCst);
}

pub fn overlay_hwnd() -> isize {
    TARGET_HWND.load(Ordering::SeqCst)
}

pub fn set_locked_state(locked: bool) {
    IS_LOCKED.store(locked, Ordering::SeqCst);
    IS_PRESS_ACTIVE.store(false, Ordering::SeqCst);
    IS_HOLD_DRAGGING.store(false, Ordering::SeqCst);
    start_hold_drag_hook();
    let raw = TARGET_HWND.load(Ordering::SeqCst);
    if raw != 0 {
        unsafe {
            apply_click_through(
                HWND(raw as *mut _),
                locked && !IS_HOLD_DRAGGING.load(Ordering::SeqCst),
            );
        }
    }
}

pub fn is_hold_dragging() -> bool {
    IS_HOLD_DRAGGING.load(Ordering::Relaxed)
}

unsafe fn apply_click_through(hwnd: HWND, click_through: bool) {
    let style = GetWindowLongPtrW(hwnd, GWL_EXSTYLE);
    let transparent = WS_EX_TRANSPARENT.0 as isize;
    let new_style = if click_through {
        style | transparent
    } else {
        style & !transparent
    };
    if new_style != style {
        SetWindowLongPtrW(hwnd, GWL_EXSTYLE, new_style);
    }
}

pub fn start_hold_drag_hook() {
    if HOOK_THREAD_ID.load(Ordering::SeqCst) != 0 {
        return;
    }
    let _ = START_INSTANT.get_or_init(Instant::now);
    std::thread::spawn(|| unsafe {
        let tid = windows::Win32::System::Threading::GetCurrentThreadId();
        HOOK_THREAD_ID.store(tid as isize, Ordering::SeqCst);

        let hook: HHOOK =
            SetWindowsHookExW(WH_MOUSE_LL, Some(low_level_mouse_proc), HINSTANCE::default(), 0)
                .unwrap_or_default();

        // Background ticker to promote Pressed -> Dragging at 350 ms only while a press is active.
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_millis(30));
            if HOOK_THREAD_ID.load(Ordering::Relaxed) != tid as isize {
                break;
            }
            if !IS_PRESS_ACTIVE.load(Ordering::Relaxed) || !IS_LOCKED.load(Ordering::Relaxed) {
                continue;
            }
            let raw = TARGET_HWND.load(Ordering::Relaxed);
            if raw == 0 {
                continue;
            }
            if let Ok(mut sm) = state_machine().lock() {
                if sm.on_tick(now_ms()) == HoldDragAction::BeginDrag {
                    IS_HOLD_DRAGGING.store(true, Ordering::SeqCst);
                    apply_click_through(HWND(raw as *mut _), false);
                }
            }
        });

        let mut msg = MSG::default();
        while GetMessageW(&mut msg, HWND::default(), 0, 0).into() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }

        if !hook.is_invalid() {
            let _ = UnhookWindowsHookEx(hook);
        }
        let _ = HOOK_THREAD_ID.compare_exchange(
            tid as isize,
            0,
            Ordering::SeqCst,
            Ordering::SeqCst,
        );
    });
}

pub fn stop_hold_drag_hook() {
    let tid = HOOK_THREAD_ID.swap(0, Ordering::SeqCst);
    IS_PRESS_ACTIVE.store(false, Ordering::SeqCst);
    IS_HOLD_DRAGGING.store(false, Ordering::SeqCst);
    if tid != 0 {
        unsafe {
            let _ = PostThreadMessageW(tid as u32, WM_QUIT, WPARAM(0), LPARAM(0));
        }
    }
}

unsafe extern "system" fn low_level_mouse_proc(
    n_code: i32,
    w_param: WPARAM,
    l_param: LPARAM,
) -> LRESULT {
    if n_code >= 0 {
        let msg = w_param.0 as u32;

        // Fast O(1) lock-free exit for global mouse movements when no press inside the overlay is active.
        if msg != WM_LBUTTONDOWN && !IS_PRESS_ACTIVE.load(Ordering::Relaxed) {
            return CallNextHookEx(HHOOK::default(), n_code, w_param, l_param);
        }

        let locked = IS_LOCKED.load(Ordering::Relaxed);
        let raw_hwnd = TARGET_HWND.load(Ordering::Relaxed);
        if raw_hwnd != 0 {
            let hwnd = HWND(raw_hwnd as *mut _);
            let info = &*(l_param.0 as *const MSLLHOOKSTRUCT);
            let pt: POINT = info.pt;

            match msg {
                WM_LBUTTONDOWN => {
                    let mut rect = RECT::default();
                    if GetWindowRect(hwnd, &mut rect).is_ok() {
                        let inside = pt.x >= rect.left
                            && pt.x < rect.right
                            && pt.y >= rect.top
                            && pt.y < rect.bottom;
                        if inside {
                            if let Ok(mut sm) = state_machine().lock() {
                                sm.on_down(pt.x, pt.y, rect.left, rect.top, now_ms());
                                IS_PRESS_ACTIVE.store(true, Ordering::SeqCst);
                            }
                        }
                    }
                }
                WM_MOUSEMOVE => {
                    if let Ok(mut sm) = state_machine().lock() {
                        match sm.on_move(pt.x, pt.y, now_ms(), locked) {
                            HoldDragAction::BeginDrag => {
                                IS_HOLD_DRAGGING.store(true, Ordering::SeqCst);
                                if locked {
                                    apply_click_through(hwnd, false);
                                }
                            }
                            HoldDragAction::MoveTo { x, y } => {
                                IS_HOLD_DRAGGING.store(true, Ordering::SeqCst);
                                let _ = SetWindowPos(
                                    hwnd,
                                    HWND::default(),
                                    x,
                                    y,
                                    0,
                                    0,
                                    SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE,
                                );
                            }
                            HoldDragAction::None => {
                                if sm.state() == HoldDragState::Idle {
                                    IS_PRESS_ACTIVE.store(false, Ordering::SeqCst);
                                }
                            }
                            _ => {}
                        }
                    }
                }
                WM_LBUTTONUP => {
                    IS_PRESS_ACTIVE.store(false, Ordering::SeqCst);
                    if let Ok(mut sm) = state_machine().lock() {
                        if sm.on_up() == HoldDragAction::EndDrag {
                            IS_HOLD_DRAGGING.store(false, Ordering::SeqCst);
                            if locked {
                                apply_click_through(hwnd, true);
                            }
                        }
                    }
                }
                _ => {}
            }
        }
    }
    CallNextHookEx(HHOOK::default(), n_code, w_param, l_param)
}
