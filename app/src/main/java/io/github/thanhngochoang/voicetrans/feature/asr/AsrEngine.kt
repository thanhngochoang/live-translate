package io.github.thanhngochoang.voicetrans.feature.asr

import java.io.Closeable
import kotlinx.coroutines.flow.Flow

interface AsrEngine : Closeable {
    /** [frames] are 16 kHz mono float samples in [-1, 1]. Collect once at a time per engine. */
    fun transcribe(frames: Flow<FloatArray>): Flow<AsrEvent>
}
