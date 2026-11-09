package io.github.thanhngochoang.voicetrans.feature.translate.api

import java.io.Closeable

data class UtteranceContext(
    val source: String,
    val translation: String,
    val sourceLang: SourceLang,
)

interface Translator : Closeable {
    /** [to] is an ML Kit language code. When the source language equals [to] the text is returned unchanged. */
    suspend fun translate(text: String, from: SourceLang, to: String): Result<String>

    /** Context-aware translation overload taking preceding conversational pairs. */
    suspend fun translate(
        text: String,
        from: SourceLang,
        to: String,
        context: List<UtteranceContext>,
    ): Result<String> = translate(text, from, to)
}
