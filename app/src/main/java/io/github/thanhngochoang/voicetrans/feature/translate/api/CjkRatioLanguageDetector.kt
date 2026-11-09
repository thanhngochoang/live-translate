package io.github.thanhngochoang.voicetrans.feature.translate.api

import javax.inject.Inject

class CjkRatioLanguageDetector @Inject constructor() : LanguageDetector {
    override fun detect(text: String): SourceLang {
        var nonWhitespaceCount = 0
        var cjkCount = 0
        for (ch in text) {
            if (!ch.isWhitespace()) {
                nonWhitespaceCount++
                if (ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch in '\uF900'..'\uFAFF') {
                    cjkCount++
                }
            }
        }
        if (nonWhitespaceCount == 0) {
            return SourceLang.EN
        }
        val ratio = cjkCount.toFloat() / nonWhitespaceCount.toFloat()
        // Require at least 40% CJK characters to be classified as Chinese.
        // This prevents short English phrases with a stray hallucinated Chinese token from being misclassified as ZH.
        return if (ratio >= 0.40f) SourceLang.ZH else SourceLang.EN
    }
}
