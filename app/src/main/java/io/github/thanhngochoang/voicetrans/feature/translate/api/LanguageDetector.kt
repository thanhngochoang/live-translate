package io.github.thanhngochoang.voicetrans.feature.translate.api

interface LanguageDetector {
    fun detect(text: String): SourceLang
}
