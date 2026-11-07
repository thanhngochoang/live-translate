package io.github.thanhngochoang.voicetrans.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {
    @Test fun toFloatPcm_scalesBy32768() =
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f), shortArrayOf(0, 16384, -32768).toFloatPcm(), 0f)

    @Test fun toFloatPcm_respectsLength() = assertEquals(2, shortArrayOf(1, 2, 3).toFloatPcm(2).size)

    @Test fun downmix_averagesChannelsAndDecimatesBy3() {
        // 3 stereo frames (L,R): mono = 100, 200, 300 -> mean 200
        val out = downmixDecimate48kStereoTo16kMono(shortArrayOf(100, 100, 200, 200, 300, 300), 6)
        assertArrayEquals(shortArrayOf(200), out)
    }

    @Test fun downmix_oppositeChannelsCancel() =
        assertArrayEquals(shortArrayOf(0, 0), downmixDecimate48kStereoTo16kMono(ShortArray(12) { if (it % 2 == 0) 1000 else -1000 }, 12))

    @Test fun downmix_100msBlockGives1600Samples() =
        assertEquals(1600, downmixDecimate48kStereoTo16kMono(ShortArray(9600), 9600).size)

    @Test fun downmix_ignoresIncompleteTail() =
        assertEquals(1, downmixDecimate48kStereoTo16kMono(shortArrayOf(1, 1, 1, 1, 1, 1, 9, 9), 8).size)

    @Test fun downmix_keepsToneAmplitude() {
        // 1 kHz tone at 48 kHz stereo: should survive decimation at ~full amplitude
        val n = 4800
        val stereo = ShortArray(n * 2)
        for (i in 0 until n) { val v = (10000 * Math.sin(2 * Math.PI * 1000 * i / 48000)).toInt().toShort(); stereo[2 * i] = v; stereo[2 * i + 1] = v }
        val out = downmixDecimate48kStereoTo16kMono(stereo, stereo.size)
        val peak = out.maxOf { kotlin.math.abs(it.toInt()) }
        assert(peak in 8500..10000) { "peak $peak" }
    }
}
