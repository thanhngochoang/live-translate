package io.github.thanhngochoang.voicetrans.core.audio

/**
 * Fires once when consecutive all-zero frames reach [thresholdMs] while something is playing (the playing app
 * probably blocks capture). Silence while nothing plays (a paused video) never counts. Any non-zero sample, or
 * playback stopping, resets it.
 */
class CaptureBlockedDetector(thresholdMs: Long = 5_000, frameMs: Long = 100) {
    private val framesNeeded = (thresholdMs / frameMs).toInt()
    private var zeroFrames = 0

    fun onFrame(frame: FloatArray, playback: PlaybackState): Boolean {
        if (!playback.active || frame.any { it != 0f }) {
            zeroFrames = 0
            return false
        }
        zeroFrames++
        return zeroFrames == framesNeeded
    }
}
