package io.github.thanhngochoang.voicetrans.feature.session

import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang

fun shouldDraft(text: String, lastDraftedText: String?, lang: SourceLang): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false

    return when (lang) {
        SourceLang.EN -> {
            val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (lastDraftedText == null) {
                words.size >= 3
            } else {
                val lastWords = lastDraftedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                words.size - lastWords.size >= 2
            }
        }
        SourceLang.ZH -> {
            val chars = trimmed.filter { !it.isWhitespace() }
            if (lastDraftedText == null) {
                chars.length >= 4
            } else {
                val lastChars = lastDraftedText.trim().filter { !it.isWhitespace() }
                chars.length - lastChars.length >= 3
            }
        }
    }
}
