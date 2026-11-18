use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum AsrEvent {
    Partial { text: String },
    Final { text: String, start_ms: u64, end_ms: u64 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum AudioClass {
    Speech,
    Singing,
    Music,
    Other,
}

pub struct ClassRules;

impl ClassRules {
    /// Simple threshold gate matching Android/macOS pipeline.
    pub fn classify(speech_score: f32, singing_score: f32, music_score: f32) -> AudioClass {
        if speech_score >= 0.35 && speech_score >= music_score {
            AudioClass::Speech
        } else if singing_score >= 0.40 {
            AudioClass::Singing
        } else if music_score >= 0.45 {
            AudioClass::Music
        } else {
            AudioClass::Other
        }
    }
}
