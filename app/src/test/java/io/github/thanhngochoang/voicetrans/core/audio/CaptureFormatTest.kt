package io.github.thanhngochoang.voicetrans.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureFormatTest {
    @Test fun prefers16kMono_whenItInitialises() {
        val tried = mutableListOf<CaptureFormat>()
        val (format, handle) = openWithFallback { f -> tried += f; "rec-${f.sampleRate}" }
        assertEquals(CaptureFormat.MONO_16K, format)
        assertEquals("rec-16000", handle)
        assertEquals(listOf(CaptureFormat.MONO_16K), tried)
    }

    @Test fun fallsBackTo48kStereo_when16kFails() {
        val (format, _) = openWithFallback { f -> if (f == CaptureFormat.MONO_16K) null else "ok" }
        assertEquals(CaptureFormat.STEREO_48K, format)
    }

    @Test fun fallsBack_whenOpeningThrows() {
        val (format, _) = openWithFallback { f -> if (f == CaptureFormat.MONO_16K) throw IllegalArgumentException("bad") else "ok" }
        assertEquals(CaptureFormat.STEREO_48K, format)
    }

    @Test fun throwsAudioUnavailable_whenNoFormatWorks() {
        assertThrows(AudioUnavailableException::class.java) { openWithFallback<String> { null } }
    }
}
