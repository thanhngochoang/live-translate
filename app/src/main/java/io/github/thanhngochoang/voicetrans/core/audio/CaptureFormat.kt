package io.github.thanhngochoang.voicetrans.core.audio

/** What AudioRecord is asked for. The emulator (API 35) accepted [MONO_16K]; [STEREO_48K] is the fallback. */
enum class CaptureFormat(val sampleRate: Int, val channels: Int) {
    MONO_16K(16_000, 1),
    STEREO_48K(48_000, 2),
    ;

    /** Shorts in 100 ms of captured audio. */
    val shortsPer100ms: Int get() = sampleRate / 10 * channels
}

/**
 * Tries [MONO_16K] first, then [STEREO_48K]. [open] returns null (or throws) when that format cannot be initialised.
 * Throws [AudioUnavailableException] when neither works.
 */
internal fun <T : Any> openWithFallback(open: (CaptureFormat) -> T?): Pair<CaptureFormat, T> {
    var last: Throwable? = null
    for (format in CaptureFormat.entries) {
        try {
            open(format)?.let { return format to it }
        } catch (t: Exception) {
            last = t
        }
    }
    throw AudioUnavailableException("AudioRecord could not be initialised for 16 kHz mono or 48 kHz stereo", last)
}
