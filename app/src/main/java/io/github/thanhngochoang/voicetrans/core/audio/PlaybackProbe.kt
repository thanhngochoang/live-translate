package io.github.thanhngochoang.voicetrans.core.audio

import android.media.AudioAttributes
import android.media.AudioManager

data class PlaybackState(val active: Boolean, val blocksCapture: Boolean)

fun interface PlaybackProbe {
    fun state(): PlaybackState
}

/** Reads the active playbacks, filtered to the usages we capture (MEDIA, GAME, UNKNOWN). Calls are not captured. */
class AudioManagerPlaybackProbe(private val audioManager: AudioManager) : PlaybackProbe {
    override fun state(): PlaybackState {
        val relevant = audioManager.activePlaybackConfigurations.filter {
            it.audioAttributes.usage in CAPTURED_USAGES
        }
        return PlaybackState(
            active = relevant.isNotEmpty(),
            blocksCapture = relevant.any { it.audioAttributes.allowedCapturePolicy != AudioAttributes.ALLOW_CAPTURE_BY_ALL },
        )
    }

    private companion object {
        val CAPTURED_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
    }
}
