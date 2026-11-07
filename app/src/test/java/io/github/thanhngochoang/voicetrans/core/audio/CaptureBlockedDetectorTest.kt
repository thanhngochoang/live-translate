package io.github.thanhngochoang.voicetrans.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureBlockedDetectorTest {
    private val zero = FloatArray(1600)
    private val sound = FloatArray(1600).also { it[0] = 0.01f }
    private val playing = PlaybackState(active = true, blocksCapture = true)
    private val idle = PlaybackState(active = false, blocksCapture = false)

    @Test fun zerosWhileNothingPlays_neverFires() {
        val d = CaptureBlockedDetector()
        repeat(200) { assertFalse(d.onFrame(zero, idle)) }
    }

    @Test fun zerosWhilePlaying_firesAtFiveSeconds() {
        val d = CaptureBlockedDetector()
        for (i in 1..49) assertFalse("frame $i", d.onFrame(zero, playing))
        assertTrue(d.onFrame(zero, playing))
    }

    @Test fun soundResets() {
        val d = CaptureBlockedDetector()
        repeat(40) { assertFalse(d.onFrame(zero, playing)) }
        assertFalse(d.onFrame(sound, playing))
        repeat(49) { assertFalse(d.onFrame(zero, playing)) }
    }

    @Test fun firesOnceUntilSound() {
        val d = CaptureBlockedDetector()
        repeat(49) { d.onFrame(zero, playing) }
        assertTrue(d.onFrame(zero, playing))
        assertFalse(d.onFrame(zero, playing)) // frame 51
        d.onFrame(sound, playing)
        repeat(49) { assertFalse(d.onFrame(zero, playing)) }
        assertTrue(d.onFrame(zero, playing))
    }

    @Test fun pausedPlayback_resetsCounter() {
        val d = CaptureBlockedDetector()
        repeat(40) { d.onFrame(zero, playing) }
        d.onFrame(zero, idle)
        repeat(49) { assertFalse(d.onFrame(zero, playing)) }
        assertEquals(true, d.onFrame(zero, playing))
    }
}
