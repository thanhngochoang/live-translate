package io.github.thanhngochoang.voicetrans.feature.asr

import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.WavReader
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import android.os.Build
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AsrEngineTest {

    private fun isEmulator() = Build.FINGERPRINT.contains("generic") ||
        Build.HARDWARE.contains("ranchu") || Build.PRODUCT.contains("sdk")

    private lateinit var engine: SherpaAsrEngine

    @Before
    fun setUp() {
        engine = SherpaAsrEngine(InstrumentationRegistry.getInstrumentation().targetContext.assets)
    }

    @After
    fun tearDown() = engine.close()

    private fun wav(name: String): FloatArray = WavReader.toFloats(
        InstrumentationRegistry.getInstrumentation().context.assets.open("wav/$name").use { it.readBytes() },
    )

    private fun zeros(seconds: Float) = WavReader.chunks(FloatArray((16000 * seconds).toInt()))

    @Test
    fun english_emitsFinalContainingMorning() = runBlocking {
        val frames = WavReader.chunks(wav("en_good_morning.wav")) + zeros(1f)
        val events = engine.transcribe(flowOf(*frames.toTypedArray())).toList()
        val finals = events.filterIsInstance<AsrEvent.Final>()
        assertEquals("events: $events", 1, finals.size)
        assertTrue("got: $events", finals[0].text.lowercase().contains("morning"))
        assertTrue("no Partial before Final: $events", events.first() is AsrEvent.Partial)
    }

    @Test
    fun chinese_emitsFinal() = runBlocking {
        val frames = WavReader.chunks(wav("zh_zaoshanghao.wav")) + zeros(1f)
        val events = engine.transcribe(flowOf(*frames.toTypedArray())).toList()
        val finals = events.filterIsInstance<AsrEvent.Final>()
        assertTrue("got: $events", finals.any { it.text.contains("早上好") })
    }

    @Test
    fun silenceOnly_emitsNothing() = runBlocking {
        val events = engine.transcribe(flowOf(*zeros(5f).toTypedArray())).toList()
        assertTrue("got: $events", events.isEmpty())
    }

    @Test
    fun continuousSpeech_emitsFinalBeforeEnd() = runBlocking {
        val one = wav("en_good_morning.wav")
        val repeats = (16000 * 25 / one.size) + 1
        val chunks = WavReader.chunks(FloatArray(one.size * repeats) { one[it % one.size] }).take(250)
        val source = flow { for (c in chunks) emit(c) }
        val inputMs = chunks.size * 100L
        var firstFinalEndMs = -1L
        engine.transcribe(source).collect {
            if (it is AsrEvent.Final && firstFinalEndMs < 0) firstFinalEndMs = it.endMs
        }
        // rule3 forces a Final at ~20 s of stream time; the end-of-input flush would report endMs == inputMs.
        assertTrue("no Final before the input ended (first Final.endMs=$firstFinalEndMs of $inputMs ms)",
            firstFinalEndMs in 1 until inputMs - 1000)
    }

    /**
     * Real-capture model: AudioRecord delivers 100 ms chunks on its own clock, so speech AND silence are emitted
     * on one absolute schedule (chunk i at t0 + i*100 ms) behind an unlimited buffer. Decoding then overlaps with
     * capture, and decode time does not stretch the schedule. Feeding speech instantly would instead measure the
     * decode backlog of a slow device. Latency = last speech chunk emitted -> Final received.
     * Emulator API 35 runs ~0.9x real time; re-measure on the Android 17 phone in Task 11.
     */
    @Test
    fun endpointLatency_under900ms() = runBlocking {
        assumeFalse("emulator timing not representative; measured on the phone in Task 11", isEmulator())
        val speech = WavReader.chunks(wav("en_good_morning.wav"))
        var lastSpeechAt = 0L
        val source: Flow<FloatArray> = flow {
            val t0Ms = System.nanoTime() / 1_000_000
            var i = 0
            suspend fun emitAt(chunk: FloatArray) {
                delay(maxOf(0L, t0Ms + i * 100L - System.nanoTime() / 1_000_000))
                i++
                emit(chunk)
            }
            for (c in speech) emitAt(c)
            lastSpeechAt = System.nanoTime()
            repeat(25) { emitAt(FloatArray(1600)) }
        }.buffer(Channel.UNLIMITED) // capture and decoding overlap
        var latencyMs = -1L
        var finalEndMs = -1L
        engine.transcribe(source).collect {
            if (it is AsrEvent.Final && latencyMs < 0) {
                latencyMs = (System.nanoTime() - lastSpeechAt) / 1_000_000
                finalEndMs = it.endMs
            }
        }
        assertTrue("no Final received", latencyMs >= 0)
        assertTrue("endpoint latency ${latencyMs}ms wall; Final.endMs=$finalEndMs, speech wav ends at ${speech.sumOf { it.size } * 1000L / 16000}ms (emulator API 35)", latencyMs < 900)
    }
}
