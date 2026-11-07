package io.github.thanhngochoang.voicetrans.feature.translate.api

import java.io.Closeable

interface Translator : Closeable {
    /** [to] is an ML Kit language code. When the source language equals [to] the text is returned unchanged. */
    suspend fun translate(text: String, from: SourceLang, to: String): Result<String>
}
