package io.github.thanhngochoang.voicetrans.feature.asr

import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.WavReader
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

    private lateinit var engine: SenseVoiceAsrEngine

    @Before
    fun setUp() {
        engine = SenseVoiceAsrEngine(InstrumentationRegistry.getInstrumentation().targetContext.assets)
    }

    @After
    fun tearDown() = engine.close()

    private fun wav(name: String): FloatArray = WavReader.toFloats(
        InstrumentationRegistry.getInstrumentation().context.assets.open("wav/$name").use { it.readBytes() },
    )

    private fun zeros(seconds: Float) = WavReader.chunks(FloatArray((16000 * seconds).toInt()))

    @Test
    fun english_finalIsCasedAndContainsMorning() = runBlocking {
        val frames = WavReader.chunks(wav("en_good_morning.wav")) + zeros(1f)
        val events = engine.transcribe(flowOf(*frames.toTypedArray())).toList()
        val finals = events.filterIsInstance<AsrEvent.Final>()
        assertEquals("events: $events", 1, finals.size)
        val text = finals[0].text
        assertTrue("expected 'morning' in $text", text.lowercase().contains("morning"))
        assertTrue("expected at least one lowercase letter in $text", text.any { it.isLowerCase() })
    }

    @Test
    fun chinese_emitsFinal() = runBlocking {
        val frames = WavReader.chunks(wav("zh_zaoshanghao.wav")) + zeros(1f)
        val events = engine.transcribe(flowOf(*frames.toTypedArray())).toList()
        val finals = events.filterIsInstance<AsrEvent.Final>()
        assertTrue("got: $events", finals.any { it.text.contains("早上好") })
    }

    @Test
    fun partialsEmittedBeforeFinal() = runBlocking {
        val one = wav("en_good_morning.wav")
        val repeats = (16000 * 4 / one.size) + 1
        val chunks = WavReader.chunks(FloatArray(one.size * repeats) { one[it % one.size] }).take(40) + zeros(1f)
        val events = engine.transcribe(flowOf(*chunks.toTypedArray())).toList()
        val firstFinalIdx = events.indexOfFirst { it is AsrEvent.Final }
        assertTrue("expected a Final in $events", firstFinalIdx >= 0)
        val partialsBeforeFinal = events.subList(0, firstFinalIdx).filterIsInstance<AsrEvent.Partial>()
        assertTrue("expected at least 2 Partials before Final, got ${partialsBeforeFinal.size} in $events", partialsBeforeFinal.size >= 2)
    }

    @Test
    fun silenceOnly_emitsNothing() = runBlocking {
        val events = engine.transcribe(flowOf(*zeros(5f).toTypedArray())).toList()
        assertTrue("got: $events", events.isEmpty())
    }

    @Test
    fun continuousSpeech_finalByMaxSpeech() = runBlocking {
        val one = wav("en_good_morning.wav")
        val repeats = (16000 * 25 / one.size) + 1
        val chunks = WavReader.chunks(FloatArray(one.size * repeats) { one[it % one.size] }).take(250)
        val source = flow { for (c in chunks) emit(c) }
        var firstFinalEndMs = -1L
        engine.transcribe(source).collect {
            if (it is AsrEvent.Final && firstFinalEndMs < 0) firstFinalEndMs = it.endMs
        }
        assertTrue("first Final.endMs=$firstFinalEndMs, expected <= 8500", firstFinalEndMs in 1..8_500)
    }
}
