package io.github.thanhngochoang.voicetrans.feature.translate.api

import javax.inject.Inject

class CjkRatioLanguageDetector @Inject constructor() : LanguageDetector {
    override fun detect(text: String): SourceLang {
        var nonWhitespaceCount = 0
        var cjkCount = 0
        for (ch in text) {
            if (!ch.isWhitespace()) {
                nonWhitespaceCount++
                if (ch in '\u4E00'..'\u9FFF') {
                    cjkCount++
                }
            }
        }
        if (nonWhitespaceCount == 0) {
            return SourceLang.EN
        }
        val ratio = cjkCount.toFloat() / nonWhitespaceCount.toFloat()
        return if (ratio >= 0.30f) SourceLang.ZH else SourceLang.EN
    }
}
