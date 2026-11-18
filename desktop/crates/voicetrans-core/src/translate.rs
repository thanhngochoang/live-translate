use serde::{Deserialize, Serialize};
use thiserror::Error;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum SourceLang {
    En,
    Zh,
}

impl SourceLang {
    pub fn code(self) -> &'static str {
        match self {
            SourceLang::En => "EN",
            SourceLang::Zh => "ZH",
        }
    }
}

pub trait LanguageDetector: Send + Sync {
    fn detect(&self, text: &str) -> SourceLang;
}

/// Heuristic detector: >= 30% CJK Unified Ideographs (`\u{4E00}..=\u{9FFF}`) -> `Zh`, else `En`.
#[derive(Debug, Default, Clone, Copy)]
pub struct CjkRatioLanguageDetector;

impl LanguageDetector for CjkRatioLanguageDetector {
    fn detect(&self, text: &str) -> SourceLang {
        let mut non_ws = 0usize;
        let mut cjk = 0usize;
        for ch in text.chars() {
            if !ch.is_whitespace() {
                non_ws += 1;
                if ('\u{4E00}'..='\u{9FFF}').contains(&ch) {
                    cjk += 1;
                }
            }
        }
        if non_ws == 0 {
            return SourceLang::En;
        }
        if (cjk as f32 / non_ws as f32) >= 0.30 {
            SourceLang::Zh
        } else {
            SourceLang::En
        }
    }
}

#[derive(Debug, Error)]
pub enum TranslateError {
    #[error("model not loaded: {0}")]
    ModelNotLoaded(String),
    #[error("inference failed: {0}")]
    Inference(String),
}

pub trait Translator: Send + Sync {
    fn translate(
        &self,
        text: &str,
        source: SourceLang,
        target_lang: &str,
    ) -> Result<String, TranslateError>;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_english_when_below_cjk_threshold() {
        let det = CjkRatioLanguageDetector;
        assert_eq!(det.detect("Hello world, this is a test."), SourceLang::En);
        assert_eq!(det.detect(""), SourceLang::En);
    }

    #[test]
    fn detects_chinese_at_or_above_30_percent_cjk() {
        let det = CjkRatioLanguageDetector;
        assert_eq!(det.detect("你好世界"), SourceLang::Zh);
        assert_eq!(det.detect("你好ab"), SourceLang::Zh); // 2/4 = 50%
        assert_eq!(det.detect("你abcdef"), SourceLang::En); // 1/7 = 14%
    }
}
