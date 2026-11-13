package io.github.thanhngochoang.voicetrans.feature.asr

data class AsrConfig(
    val numThreads: Int = 2,
    val vadMinSilenceS: Float = 0.5f,
    val vadMaxSpeechS: Float = 8f,
    val partialEveryMs: Long = 800,
    val preRollMs: Int = 300,
)
