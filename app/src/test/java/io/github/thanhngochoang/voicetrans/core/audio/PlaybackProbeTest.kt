package io.github.thanhngochoang.voicetrans.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackProbeTest {
    private fun probe(vararg attrs: AudioAttributes): PlaybackProbe {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadowOf(am).setActivePlaybackConfigurationsFor(attrs.toList(), false)
        return AudioManagerPlaybackProbe(am)
    }

    private fun config(usage: Int, policy: Int): AudioAttributes =
        AudioAttributes.Builder().setUsage(usage).setAllowedCapturePolicy(policy).build()

    @Test fun mediaBlockedByNone_isActiveAndBlocking() =
        assertEquals(
            PlaybackState(true, true),
            probe(config(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_NONE)).state(),
        )

    @Test fun mediaAllowed_isActiveNotBlocking() =
        assertEquals(
            PlaybackState(true, false),
            probe(config(AudioAttributes.USAGE_MEDIA, AudioAttributes.ALLOW_CAPTURE_BY_ALL)).state(),
        )

    @Test fun voiceCommunication_isIgnored() =
        assertEquals(
            PlaybackState(false, false),
            probe(config(AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.ALLOW_CAPTURE_BY_NONE)).state(),
        )

    @Test fun nothingPlaying_isInactive() = assertEquals(PlaybackState(false, false), probe().state())
}
