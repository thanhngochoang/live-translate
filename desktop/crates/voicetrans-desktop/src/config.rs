use directories::ProjectDirs;
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;
use voicetrans_core::overlay::{LineOrder, OverlayMode, SavedOverlayPosition};

fn default_minimize_to_tray() -> bool {
    true
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppConfig {
    pub mode: OverlayMode,
    pub line_order: LineOrder,
    pub is_locked: bool,
    pub text_size: f32,
    pub bg_opacity: f32,
    pub target_lang: String,
    pub overlay_position: Option<SavedOverlayPosition>,
    #[serde(default = "default_minimize_to_tray")]
    pub minimize_to_tray_on_close: bool,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            mode: OverlayMode::Bilingual,
            line_order: LineOrder::SourceTop,
            is_locked: false,
            text_size: 24.0,
            bg_opacity: 0.55,
            target_lang: "vi".to_string(),
            overlay_position: None,
            minimize_to_tray_on_close: true,
        }
    }
}

impl AppConfig {
    pub fn config_path() -> Option<PathBuf> {
        ProjectDirs::from("io.github", "thanhngochoang", "VoiceTrans")
            .map(|dirs| dirs.config_dir().join("config.json"))
    }

    pub fn load() -> Self {
        let Some(path) = Self::config_path() else {
            return Self::default();
        };
        fs::read_to_string(path)
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    pub fn save(&self) {
        let Some(path) = Self::config_path() else {
            return;
        };
        if let Some(parent) = path.parent() {
            let _ = fs::create_dir_all(parent);
        }
        if let Ok(json) = serde_json::to_string_pretty(self) {
            let _ = fs::write(path, json);
        }
    }
}
