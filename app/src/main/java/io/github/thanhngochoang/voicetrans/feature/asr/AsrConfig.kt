package io.github.thanhngochoang.voicetrans.feature.asr

data class AsrConfig(
    val numThreads: Int = 2,
    /** Streaming endpoint rule2: trailing silence that ends an utterance. 1.2 s (sherpa default) would break the 1 s budget. */
    val rule2TrailingSilenceS: Float = 0.6f,
    /** Streaming endpoint rule3: longest utterance before a Final is forced (12 s keeps continuous audio readable). */
    val rule3MaxUtteranceS: Float = 12f,
    val vadMinSilenceS: Float = 0.5f,
    val vadMaxSpeechS: Float = 8f,
    val partialEveryMs: Long = 800,
    val preRollMs: Int = 300,
)
