package io.github.thanhngochoang.voicetrans.feature.asr

import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.WavReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AsrEngineTest {

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
        var fed = 0
        val source = flow { for (c in chunks) { fed++; emit(c) } }
        var fedAtFirstFinal = -1
        engine.transcribe(source).collect {
            if (it is AsrEvent.Final && fedAtFirstFinal < 0) fedAtFirstFinal = fed
        }
        assertTrue("no Final before the input ended (fed at first Final: $fedAtFirstFinal of ${chunks.size})",
            fedAtFirstFinal in 1 until chunks.size)
    }

    @Test
    fun endpointLatency_under900ms() = runBlocking {
        val speech = WavReader.chunks(wav("en_good_morning.wav"))
        var lastSpeechAt = 0L
        val source: Flow<FloatArray> = flow {
            for (c in speech) emit(c)
            lastSpeechAt = System.nanoTime()
            repeat(25) { delay(100); emit(FloatArray(1600)) } // real-time silence
        }
        var latencyMs = -1L
        engine.transcribe(source).collect {
            if (it is AsrEvent.Final && latencyMs < 0) latencyMs = (System.nanoTime() - lastSpeechAt) / 1_000_000
        }
        assertTrue("no Final received", latencyMs >= 0)
        assertTrue("endpoint latency ${latencyMs}ms (emulator API 35)", latencyMs < 900)
    }
}
