use serde::{Deserialize, Serialize};

use crate::session::{Utterance, UtteranceKind};
use crate::translate::SourceLang;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum OverlayMode {
    Bilingual,
    TranslationOnly,
    SourceOnly,
}

impl OverlayMode {
    pub fn cycle(self) -> Self {
        match self {
            OverlayMode::Bilingual => OverlayMode::TranslationOnly,
            OverlayMode::TranslationOnly => OverlayMode::SourceOnly,
            OverlayMode::SourceOnly => OverlayMode::Bilingual,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            OverlayMode::Bilingual => "Song ngữ",
            OverlayMode::TranslationOnly => "Chỉ dịch",
            OverlayMode::SourceOnly => "Chỉ gốc",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum LineOrder {
    SourceTop,
    TranslationTop,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SubtitleBlock {
    pub id: i64,
    pub kind: UtteranceKind,
    pub source_text: String,
    pub source_lang: SourceLang,
    pub target_lang: String,
    pub translation_text: Option<String>,
    pub is_newest: bool,
    pub failed: bool,
}

pub struct BilingualLines;

impl BilingualLines {
    pub fn blocks(utterances: &[Utterance], max_blocks: usize) -> Vec<SubtitleBlock> {
        let cap = max_blocks.max(1);
        let start = utterances.len().saturating_sub(cap);
        let slice = &utterances[start..];
        let Some(last) = slice.last() else {
            return Vec::new();
        };
        slice
            .iter()
            .map(|u| SubtitleBlock {
                id: u.id,
                kind: u.kind,
                source_text: u.source.clone(),
                source_lang: u.source_lang,
                target_lang: u.target_lang.clone(),
                translation_text: u.translation.clone(),
                is_newest: u.id == last.id,
                failed: u.translation_failed,
            })
            .collect()
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MonitorRect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl MonitorRect {
    pub fn width(&self) -> i32 {
        (self.right - self.left).max(1)
    }

    pub fn height(&self) -> i32 {
        (self.bottom - self.top).max(1)
    }

    pub fn contains(&self, x: i32, y: i32) -> bool {
        x >= self.left && x < self.right && y >= self.top && y < self.bottom
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct MonitorInfo {
    pub id: String,
    pub name: String,
    pub work_area: MonitorRect,
    pub dpi: u32,
    pub is_primary: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SavedOverlayPosition {
    pub monitor_id: String,
    /// Normalized 0.0..=1.0 across free horizontal travel inside the monitor work area.
    pub norm_x: f32,
    /// Normalized 0.0..=1.0 across free vertical travel inside the monitor work area.
    pub norm_y: f32,
}

pub struct MonitorBounds;

impl MonitorBounds {
    /// Resolves `(x, y)` in virtual-screen physical pixels for a saved position,
    /// falling back to the primary monitor (or first monitor) if the saved monitor is disconnected.
    pub fn resolve_position(
        saved: Option<&SavedOverlayPosition>,
        monitors: &[MonitorInfo],
        overlay_width: i32,
        overlay_height: i32,
    ) -> (i32, i32) {
        let Some(target) = saved
            .and_then(|s| monitors.iter().find(|m| m.id == s.monitor_id))
            .or_else(|| monitors.iter().find(|m| m.is_primary))
            .or_else(|| monitors.first())
        else {
            return (100, 100);
        };

        let area = &target.work_area;
        let free_w = (area.width() - overlay_width).max(0);
        let free_h = (area.height() - overlay_height).max(0);
        let nx = saved.map(|s| s.norm_x.clamp(0.0, 1.0)).unwrap_or(0.5);
        let ny = saved.map(|s| s.norm_y.clamp(0.0, 1.0)).unwrap_or(0.92);

        let x = area.left + (nx * free_w as f32).round() as i32;
        let y = area.top + (ny * free_h as f32).round() as i32;
        (x, y)
    }

    /// Captures a normalized position on the monitor containing the overlay's center.
    pub fn save_position(
        x: i32,
        y: i32,
        overlay_width: i32,
        overlay_height: i32,
        monitors: &[MonitorInfo],
    ) -> Option<SavedOverlayPosition> {
        let cx = x + overlay_width / 2;
        let cy = y + overlay_height / 2;
        let mon = monitors
            .iter()
            .find(|m| m.work_area.contains(cx, cy))
            .or_else(|| monitors.iter().find(|m| m.is_primary))
            .or_else(|| monitors.first())?;

        let area = &mon.work_area;
        let free_w = (area.width() - overlay_width).max(1);
        let free_h = (area.height() - overlay_height).max(1);
        let norm_x = ((x - area.left) as f32 / free_w as f32).clamp(0.0, 1.0);
        let norm_y = ((y - area.top) as f32 / free_h as f32).clamp(0.0, 1.0);
        Some(SavedOverlayPosition {
            monitor_id: mon.id.clone(),
            norm_x,
            norm_y,
        })
    }

    /// Clamps `(x, y)` inside the union or nearest monitor work area so the overlay cannot be lost offscreen.
    pub fn clamp_to_monitor(
        x: i32,
        y: i32,
        overlay_width: i32,
        overlay_height: i32,
        monitor: &MonitorInfo,
    ) -> (i32, i32) {
        let a = &monitor.work_area;
        let max_x = (a.right - overlay_width).max(a.left);
        let max_y = (a.bottom - overlay_height).max(a.top);
        (x.clamp(a.left, max_x), y.clamp(a.top, max_y))
    }
}

/// Pure state machine for press-and-hold dragging in locked (click-through) mode.
///
/// - When `is_locked == false`: dragging starts immediately on move.
/// - When `is_locked == true`: quick clicks or swipes pass through; holding within `tolerance_px`
///   for `hold_ms` transitions into `Dragging`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HoldDragState {
    Idle,
    Pressed,
    Dragging,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HoldDragAction {
    None,
    BeginDrag,
    MoveTo { x: i32, y: i32 },
    EndDrag,
}

pub struct HoldDragStateMachine {
    pub hold_ms: u64,
    pub tolerance_px: f32,
    state: HoldDragState,
    down_x: i32,
    down_y: i32,
    down_time_ms: u64,
    win_start_x: i32,
    win_start_y: i32,
}

impl HoldDragStateMachine {
    pub fn new(hold_ms: u64, tolerance_px: f32) -> Self {
        Self {
            hold_ms,
            tolerance_px,
            state: HoldDragState::Idle,
            down_x: 0,
            down_y: 0,
            down_time_ms: 0,
            win_start_x: 0,
            win_start_y: 0,
        }
    }

    pub fn state(&self) -> HoldDragState {
        self.state
    }

    pub fn on_down(&mut self, cursor_x: i32, cursor_y: i32, win_x: i32, win_y: i32, now_ms: u64) {
        self.state = HoldDragState::Pressed;
        self.down_x = cursor_x;
        self.down_y = cursor_y;
        self.win_start_x = win_x;
        self.win_start_y = win_y;
        self.down_time_ms = now_ms;
    }

    pub fn on_tick(&mut self, now_ms: u64) -> HoldDragAction {
        if self.state == HoldDragState::Pressed
            && now_ms.saturating_sub(self.down_time_ms) >= self.hold_ms
        {
            self.state = HoldDragState::Dragging;
            return HoldDragAction::BeginDrag;
        }
        HoldDragAction::None
    }

    pub fn on_move(&mut self, cursor_x: i32, cursor_y: i32, now_ms: u64, is_locked: bool) -> HoldDragAction {
        match self.state {
            HoldDragState::Idle => HoldDragAction::None,
            HoldDragState::Pressed => {
                let dx = (cursor_x - self.down_x) as f32;
                let dy = (cursor_y - self.down_y) as f32;
                let dist = (dx * dx + dy * dy).sqrt();
                if is_locked {
                    if now_ms.saturating_sub(self.down_time_ms) >= self.hold_ms {
                        self.state = HoldDragState::Dragging;
                        return HoldDragAction::BeginDrag;
                    }
                    if dist > self.tolerance_px {
                        self.state = HoldDragState::Idle;
                    }
                    HoldDragAction::None
                } else if dist > self.tolerance_px {
                    self.state = HoldDragState::Dragging;
                    HoldDragAction::MoveTo {
                        x: self.win_start_x + (cursor_x - self.down_x),
                        y: self.win_start_y + (cursor_y - self.down_y),
                    }
                } else {
                    HoldDragAction::None
                }
            }
            HoldDragState::Dragging => HoldDragAction::MoveTo {
                x: self.win_start_x + (cursor_x - self.down_x),
                y: self.win_start_y + (cursor_y - self.down_y),
            },
        }
    }

    pub fn on_up(&mut self) -> HoldDragAction {
        let was_dragging = self.state == HoldDragState::Dragging;
        self.state = HoldDragState::Idle;
        if was_dragging {
            HoldDragAction::EndDrag
        } else {
            HoldDragAction::None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_monitors() -> Vec<MonitorInfo> {
        vec![
            MonitorInfo {
                id: "DISPLAY1".into(),
                name: "Primary".into(),
                work_area: MonitorRect {
                    left: 0,
                    top: 0,
                    right: 1920,
                    bottom: 1040,
                },
                dpi: 96,
                is_primary: true,
            },
            MonitorInfo {
                id: "DISPLAY2".into(),
                name: "Secondary 4K".into(),
                work_area: MonitorRect {
                    left: 1920,
                    top: 0,
                    right: 5760,
                    bottom: 2120,
                },
                dpi: 144,
                is_primary: false,
            },
        ]
    }

    #[test]
    fn saves_and_restores_position_on_secondary_monitor() {
        let monitors = sample_monitors();
        let saved = MonitorBounds::save_position(3000, 1800, 600, 120, &monitors).unwrap();
        assert_eq!(saved.monitor_id, "DISPLAY2");
        let (x, y) = MonitorBounds::resolve_position(Some(&saved), &monitors, 600, 120);
        assert!((x - 3000).abs() <= 1);
        assert!((y - 1800).abs() <= 1);
    }

    #[test]
    fn falls_back_to_primary_when_saved_monitor_disconnected() {
        let monitors = vec![sample_monitors().remove(0)];
        let saved = SavedOverlayPosition {
            monitor_id: "DISPLAY2".into(),
            norm_x: 0.5,
            norm_y: 0.9,
        };
        let (x, y) = MonitorBounds::resolve_position(Some(&saved), &monitors, 600, 120);
        assert!(x >= 0 && x <= 1320);
        assert!(y >= 0 && y <= 920);
    }

    #[test]
    fn locked_mode_quick_click_and_swipe_do_not_drag() {
        let mut sm = HoldDragStateMachine::new(350, 6.0);
        sm.on_down(500, 500, 400, 450, 1000);
        assert_eq!(sm.on_move(520, 500, 1050, true), HoldDragAction::None);
        assert_eq!(sm.state(), HoldDragState::Idle);
        assert_eq!(sm.on_up(), HoldDragAction::None);
    }

    #[test]
    fn locked_mode_press_and_hold_begins_drag_and_moves_across_monitors() {
        let mut sm = HoldDragStateMachine::new(350, 6.0);
        sm.on_down(500, 500, 400, 450, 1000);
        assert_eq!(sm.on_tick(1200), HoldDragAction::None);
        assert_eq!(sm.on_tick(1350), HoldDragAction::BeginDrag);
        assert_eq!(
            sm.on_move(2200, 600, 1400, true),
            HoldDragAction::MoveTo { x: 2100, y: 550 }
        );
        assert_eq!(sm.on_up(), HoldDragAction::EndDrag);
    }
}
