use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::mpsc;

use crate::translate::{SourceLang, Translator};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum UtteranceKind {
    Speech,
    Music,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Utterance {
    pub id: i64,
    pub kind: UtteranceKind,
    pub source: String,
    pub source_lang: SourceLang,
    pub target_lang: String,
    pub translation: Option<String>,
    pub translation_failed: bool,
    pub timestamp_ms: u64,
    pub end_ms: u64,
}

#[derive(Debug, Clone)]
pub struct TranslationRequest {
    pub utterance_id: i64,
    pub text: String,
    pub source_lang: SourceLang,
    pub target_lang: String,
}

#[derive(Debug, Clone)]
pub struct TranslationResult {
    pub utterance_id: i64,
    pub translation: Result<String, String>,
}

/// Spawns a sequential translation worker so translations never run in parallel.
pub fn spawn_translation_queue(
    translator: Arc<dyn Translator>,
    mut rx: mpsc::Receiver<TranslationRequest>,
    tx_out: mpsc::Sender<TranslationResult>,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        while let Some(req) = rx.recv().await {
            let tr = translator.clone();
            let text = req.text.clone();
            let src = req.source_lang;
            let tgt = req.target_lang.clone();
            let res = tokio::task::spawn_blocking(move || tr.translate(&text, src, &tgt))
                .await
                .map_err(|e| e.to_string())
                .and_then(|r| r.map_err(|e| e.to_string()));

            if tx_out
                .send(TranslationResult {
                    utterance_id: req.utterance_id,
                    translation: res,
                })
                .await
                .is_err()
            {
                break;
            }
        }
    })
}
