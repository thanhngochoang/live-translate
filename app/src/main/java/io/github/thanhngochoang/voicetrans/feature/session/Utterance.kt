package io.github.thanhngochoang.voicetrans.feature.session

import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang

enum class UtteranceKind { SPEECH, MUSIC }

/**
 * One subtitle row. A MUSIC row has an empty [source] and a null [translation]; consecutive MUSIC rows are merged by
 * extending [endMs]. [translation] is null while translating (or when [translationFailed]). When source and target
 * language are the same, [translation] equals [source] and the UI hides the second line.
 */
data class Utterance(
    val id: Long,
    val kind: UtteranceKind,
    val source: String,
    val sourceLang: SourceLang,
    val targetLang: String,
    val translation: String?,
    val timestampMs: Long,
    val endMs: Long,
    val translationFailed: Boolean = false,
)
