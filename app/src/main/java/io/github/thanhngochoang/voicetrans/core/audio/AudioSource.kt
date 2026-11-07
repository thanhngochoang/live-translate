package io.github.thanhngochoang.voicetrans.core.audio

import kotlinx.coroutines.flow.Flow

interface AudioSource {
    /** Cold flow of 100 ms frames: 16 kHz mono floats in [-1, 1]. Collection starts capture; cancelling releases it. */
    fun frames(): Flow<FloatArray>
}

class AudioUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
