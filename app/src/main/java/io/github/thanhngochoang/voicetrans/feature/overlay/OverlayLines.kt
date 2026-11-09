package io.github.thanhngochoang.voicetrans.feature.overlay

import io.github.thanhngochoang.voicetrans.feature.asr.AudioClass
import io.github.thanhngochoang.voicetrans.feature.session.CaptureWarning
import io.github.thanhngochoang.voicetrans.feature.session.Utterance
import io.github.thanhngochoang.voicetrans.feature.session.displayCase
import io.github.thanhngochoang.voicetrans.feature.session.UtteranceKind
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter

data class OverlayLine(val id: Long, val text: String, val isSource: Boolean, val isNewest: Boolean)

/**
 * The newest [maxLines] translated sentences, oldest first. Rows that are still translating, failed, or are notes are
 * skipped. With [showSource] each translation gets its source line above it, except when source equals translation
 * or when the source contains Chinese characters (Hanzi is never displayed).
 */
fun overlayLines(utterances: List<Utterance>, maxLines: Int, showSource: Boolean): List<OverlayLine> {
    val translated = utterances.filter { it.kind == UtteranceKind.SPEECH && !it.translationFailed && it.translation != null }
        .takeLast(maxLines)
    val result = ArrayList<OverlayLine>()
    translated.forEachIndexed { i, u ->
        val translation = u.translation!!
        if (showSource && translation != u.source && !CjkFilter.hasCjk(u.source)) {
            result += OverlayLine(u.id, displayCase(u.source, u.sourceLang), isSource = true, isNewest = false)
        }
        val cleanTranslation = CjkFilter.purgeCjk(translation)
        result += OverlayLine(u.id, cleanTranslation.ifBlank { translation }, isSource = false, isNewest = i == translated.lastIndex)
    }
    return result
}

enum class OverlayNotice { MUSIC, CAPTURE_BLOCKED }

/** CAPTURE_BLOCKED wins. MUSIC applies for music or singing once no speech was translated for at least 2 s. */
fun overlayNotice(audioClass: AudioClass, warning: CaptureWarning?, lastSpeechAgoMs: Long): OverlayNotice? = when {
    warning == CaptureWarning.BLOCKED_BY_APP -> OverlayNotice.CAPTURE_BLOCKED
    (audioClass == AudioClass.MUSIC || audioClass == AudioClass.SINGING) && lastSpeechAgoMs >= 2_000 -> OverlayNotice.MUSIC
    else -> null
}
