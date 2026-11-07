package io.github.thanhngochoang.voicetrans.feature.asr

data class AsrConfig(
    val numThreads: Int = 2,
    /** Endpoint rule2: trailing silence that ends an utterance. 1.2 s (sherpa default) would break the 1 s budget. */
    val rule2TrailingSilenceS: Float = 0.6f,
    /** Endpoint rule3: longest utterance before a Final is forced. */
    val rule3MaxUtteranceS: Float = 20f,
    /** Audio kept from before the VAD fires, so the first syllable is not cut off. */
    val preRollMs: Int = 300,
)
