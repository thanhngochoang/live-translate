package io.github.thanhngochoang.voicetrans.feature.asr

enum class AudioClass { SPEECH, SINGING, MUSIC, OTHER }

/**
 * Tuned for the bundled ced_tiny int8 tagger on 1 s windows (docs/spikes/audio-classification.md).
 * Plan starting points were SPEECH 0.40, SINGING 0.40, MUSIC 0.50; ced_tiny scores singing and
 * speech-over-music much lower than that, so the minimums are lower and confident speech is checked first.
 */
object ClassThresholds {
    /** Speech this strong is always SPEECH, even if a singing label scores higher, so confident speech is never dropped. */
    const val SPEECH_STRONG = 0.50f
    const val SINGING_MIN = 0.05f
    const val SPEECH_MIN = 0.20f
    const val MUSIC_MIN = 0.40f
    const val MUSIC_MAX_SPEECH = 0.30f
}

private val SPEECH_LABELS = setOf(
    "speech", "male speech", "female speech", "child speech", "conversation", "narration", "monologue",
    "speech synthesizer",
)
private val SINGING_LABELS = setOf(
    "singing", "choir", "rapping", "chant", "song", "male singing", "female singing", "child singing",
    "synthetic singing", "opera", "a capella", "vocal music", "yodeling",
)
private val MUSIC_LABELS = setOf("music")

private fun family(scores: Map<String, Float>, members: Set<String>): Float {
    var best = 0f
    for ((label, p) in scores) {
        val l = label.lowercase()
        if ((l in members || l.substringBefore(',').trim() in members) && p > best) best = p
    }
    return best
}

/** Maps AudioSet label scores (display name to probability) to a coarse class. */
fun classify(scores: Map<String, Float>): AudioClass {
    val speech = family(scores, SPEECH_LABELS)
    val singing = family(scores, SINGING_LABELS)
    val music = family(scores, MUSIC_LABELS)
    return when {
        speech >= ClassThresholds.SPEECH_STRONG -> AudioClass.SPEECH
        singing >= ClassThresholds.SINGING_MIN && singing > speech -> AudioClass.SINGING
        speech >= ClassThresholds.SPEECH_MIN -> AudioClass.SPEECH
        music >= ClassThresholds.MUSIC_MIN && speech < ClassThresholds.MUSIC_MAX_SPEECH -> AudioClass.MUSIC
        else -> AudioClass.OTHER
    }
}
