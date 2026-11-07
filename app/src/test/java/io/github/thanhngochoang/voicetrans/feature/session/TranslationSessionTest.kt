package io.github.thanhngochoang.voicetrans.feature.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.thanhngochoang.voicetrans.core.audio.AudioSource
import io.github.thanhngochoang.voicetrans.core.audio.AudioUnavailableException
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackProbe
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackState
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEngine
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEvent
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClassifier
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkRatioLanguageDetector
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

private val NOISE get() = FloatArray(1600) { 0.1f }
private val ZEROS get() = FloatArray(1600)

private class FakeAudio(private val body: suspend FlowCollector<FloatArray>.() -> Unit) : AudioSource {
    override fun frames(): Flow<FloatArray> = flow { body() }
}

/** Emits [events] after the n-th frame (1-based). */
private class FakeAsr(private val events: Map<Int, List<AsrEvent>>) : AsrEngine {
    override fun transcribe(frames: Flow<FloatArray>): Flow<AsrEvent> = flow {
        var n = 0
        frames.collect { n++; events[n]?.forEach { emit(it) } }
    }
    override fun close() {}
}

private class FakeClassifier(private val sleepMs: Long = 0, private val scores: (Int) -> Map<String, Float>) : AudioClassifier {
    private var n = 0
    override fun classify(window: FloatArray): Map<String, Float> {
        if (sleepMs > 0) Thread.sleep(sleepMs)
        return scores(n++)
    }
    override fun close() {}
}

private val SPEECH_SCORES = mapOf("Speech" to 0.6f, "Music" to 0.8f)
private val MUSIC_SCORES = mapOf("Music" to 0.9f)
private val SINGING_SCORES = mapOf("Singing" to 0.6f, "Music" to 0.8f)

private class FakeTranslator(
    private val gate: CompletableDeferred<Unit>? = null,
    private val result: (Int, String, String) -> Result<String> = { _, text, to -> Result.success("T($to):$text") },
) : Translator {
    val calls = mutableListOf<Triple<String, SourceLang, String>>()
    var maxConcurrent = 0
    private var running = 0
    override suspend fun translate(text: String, from: SourceLang, to: String): Result<String> {
        calls += Triple(text, from, to)
        running++; maxConcurrent = maxOf(maxConcurrent, running)
        try {
            gate?.await()
            return result(calls.size, text, to)
        } finally {
            running--
        }
    }
    override fun close() {}
}

private class FakeProbe(var state: PlaybackState = PlaybackState(false, false)) : PlaybackProbe {
    override fun state() = state
}

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSessionTest {

    private class Rig(
        val settings: SettingsRepository = SettingsRepository(FakeDataStore()),
        val translator: FakeTranslator = FakeTranslator(),
        val classifier: AudioClassifier = FakeClassifier { SPEECH_SCORES },
        val probe: FakeProbe = FakeProbe(),
        val asrEvents: Map<Int, List<AsrEvent>> = emptyMap(),
    )

    private fun TestScope.session(rig: Rig, virtualClassifier: Boolean = true): TranslationSession =
        TranslationSession(FakeAsr(rig.asrEvents), rig.classifier, CjkRatioLanguageDetector(), rig.translator, rig.settings, rig.probe)
            .also { if (virtualClassifier) it.classifierDispatcher = StandardTestDispatcher(testScheduler) }

    private fun noiseFrames(n: Int, thenHang: Boolean = true) = FakeAudio {
        repeat(n) { emit(NOISE); delay(100) }
        if (thenHang) awaitCancellation()
    }

    private fun final(text: String, start: Long, end: Long) = AsrEvent.Final(text, start, end)

    @Test fun partial_updatesPartial_neverTranslated() = runTest {
        val rig = Rig(asrEvents = mapOf(1 to listOf(AsrEvent.Partial("go"))))
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(3))
        runCurrent()
        assertEquals("go", s.partial.value)
        assertTrue(rig.translator.calls.isEmpty())
    }

    @Test fun final_appendsUtteranceThenFillsTranslation() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(
            translator = FakeTranslator(gate) { _, _, _ -> Result.success("chào buổi sáng") },
            asrEvents = mapOf(1 to listOf(AsrEvent.Partial("good"), final("good morning", 0, 1000))),
        )
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(5))
        runCurrent()
        val first = s.utterances.value.single()
        assertNull(first.translation)
        assertEquals("vi", first.targetLang)
        assertEquals(SourceLang.EN, first.sourceLang)
        assertEquals("good morning", first.source)
        assertNull(s.partial.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals("chào buổi sáng", s.utterances.value.single().translation)
    }

    @Test fun translationsAreSequential() = runTest {
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(
            translator = FakeTranslator(gate),
            asrEvents = mapOf(1 to listOf(final("alpha one", 0, 900)), 2 to listOf(final("bravo two", 900, 1800)), 3 to listOf(final("charlie three", 1800, 2700))),
        )
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(40))
        advanceTimeBy(3_500); runCurrent()
        assertEquals(3, s.utterances.value.size)
        assertEquals(1, rig.translator.calls.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(3, rig.translator.calls.size)
        assertEquals(1, rig.translator.maxConcurrent)
        assertEquals(listOf("T(vi):alpha one", "T(vi):bravo two", "T(vi):charlie three"), s.utterances.value.map { it.translation })
        assertEquals(s.utterances.value.map { it.id }.sorted(), s.utterances.value.map { it.id })
    }

    @Test fun targetChangedMidSession_appliesToNextSentence() = runTest {
        val rig = Rig(asrEvents = mapOf(1 to listOf(final("first sentence", 0, 900)), 20 to listOf(final("second sentence", 1000, 1900))))
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(30))
        advanceTimeBy(500); runCurrent()
        assertEquals("vi", s.utterances.value[0].targetLang)
        rig.settings.setTargetLanguage("ja")
        advanceTimeBy(2500); runCurrent()
        assertEquals("vi", s.utterances.value[0].targetLang)
        assertEquals("ja", s.utterances.value[1].targetLang)
        assertEquals("T(ja):second sentence", s.utterances.value[1].translation)
    }

    @Test fun translatorFailure_marksUtterance_andQueueContinues() = runTest {
        val rig = Rig(
            translator = FakeTranslator { n, text, _ -> if (n == 1) Result.failure(RuntimeException("pack missing")) else Result.success("ok:$text") },
            asrEvents = mapOf(1 to listOf(final("first sentence", 0, 900)), 2 to listOf(final("second sentence", 900, 1800))),
        )
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(40))
        advanceTimeBy(3_500); runCurrent()
        val u = s.utterances.value
        assertTrue(u[0].translationFailed); assertNull(u[0].translation)
        assertEquals("ok:second sentence", u[1].translation); assertFalse(u[1].translationFailed)
    }

    @Test fun junkFinals_areDroppedBeforeTranslation() = runTest {
        val rig = Rig(
            asrEvents = mapOf(
                1 to listOf(final("哥", 0, 300)), // single CJK character and under 500 ms
                2 to listOf(final("UH", 300, 1500)), // single short English token
                3 to listOf(final("GOOD MORNING", 1500, 1800)), // audio span under 500 ms
            ),
        )
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(6))
        advanceTimeBy(1000); runCurrent()
        assertTrue(s.utterances.value.isEmpty())
        assertTrue(rig.translator.calls.isEmpty())
    }

    @Test fun blankFinal_ignored() = runTest {
        val rig = Rig(asrEvents = mapOf(1 to listOf(final("  ", 0, 0))))
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(3))
        runCurrent()
        assertTrue(s.utterances.value.isEmpty())
    }

    @Test fun musicFinal_notTranslated_mergedAsMusic() = runTest {
        val rig = Rig(
            classifier = FakeClassifier { MUSIC_SCORES },
            asrEvents = mapOf(30 to listOf(final("la la", 0, 3000)), 60 to listOf(final("oh oh", 3000, 6000))),
        )
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(65))
        advanceTimeBy(7000); runCurrent()
        val u = s.utterances.value.single()
        assertEquals(UtteranceKind.MUSIC, u.kind)
        assertEquals(6000L, u.endMs)
        assertEquals("", u.source); assertNull(u.translation)
        assertTrue(rig.translator.calls.isEmpty())
    }

    @Test fun singingFinal_notTranslated() = runTest {
        val rig = Rig(
            classifier = FakeClassifier { SINGING_SCORES },
            asrEvents = mapOf(30 to listOf(final("la la", 0, 3000)), 60 to listOf(final("oh oh", 3000, 6000))),
        )
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(65))
        advanceTimeBy(7000); runCurrent()
        assertEquals(UtteranceKind.MUSIC, s.utterances.value.single().kind)
        assertTrue(rig.translator.calls.isEmpty())
    }

    @Test fun sungFinal_neverShowsSourceText() = runTest {
        val rig = Rig(classifier = FakeClassifier { SINGING_SCORES }, asrEvents = mapOf(30 to listOf(final("la la la", 0, 3000))))
        val s = session(rig)
        val seen = mutableListOf<List<Utterance>>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { s.utterances.collect { seen += it } }
        s.start(backgroundScope, noiseFrames(40))
        advanceTimeBy(5000); runCurrent()
        assertTrue(seen.flatten().none { it.source.contains("la la la") })
        assertEquals(UtteranceKind.MUSIC, s.utterances.value.single().kind)
        assertTrue(rig.translator.calls.isEmpty())
    }

    @Test fun stopWhileFinalWaitsForClassification_cancelsCleanly() = runTest {
        // Final ends at 5 s, so the gate waits for windows that never arrive (audio stops after 2 frames).
        val rig = Rig(asrEvents = mapOf(2 to listOf(final("hello", 0, 5000))))
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(2))
        advanceTimeBy(500); runCurrent()
        assertTrue(s.utterances.value.isEmpty())
        s.stop()
        runCurrent()
        advanceTimeBy(1.hours); runCurrent()
        assertEquals(SessionState.Stopped(StopReason.USER), s.state.value)
        assertTrue(s.utterances.value.isEmpty())
        assertTrue(rig.translator.calls.isEmpty())
    }

    @Test fun speechOverMusic_translated() = runTest {
        val rig = Rig(classifier = FakeClassifier { SPEECH_SCORES }, asrEvents = mapOf(30 to listOf(final("hello", 0, 1000))))
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(40))
        advanceTimeBy(4000); runCurrent()
        assertEquals(listOf("hello"), rig.translator.calls.map { it.first })
        assertEquals(UtteranceKind.SPEECH, s.utterances.value.single().kind)
    }

    @Test fun classifierRunsOffAsrPath() = runTest {
        suspend fun finalLatencyMs(sleepMs: Long): Long {
            val rig = Rig(classifier = FakeClassifier(sleepMs) { SPEECH_SCORES }, asrEvents = mapOf(11 to listOf(final("hello", 0, 500))))
            val s = session(rig, virtualClassifier = false)
            val job = TestScopeJob(this)
            val t0 = System.nanoTime()
            s.start(job.scope, FakeAudio { repeat(15) { emit(NOISE) }; awaitCancellation() })
            while (s.utterances.value.isEmpty()) { runCurrent(); if ((System.nanoTime() - t0) > 3_000_000_000L) break }
            val dt = (System.nanoTime() - t0) / 1_000_000
            s.stop()
            return dt
        }
        finalLatencyMs(0) // warm up
        val base = finalLatencyMs(0)
        val slow = finalLatencyMs(300)
        assertTrue("ASR Final delayed by ${slow - base} ms (base $base, slow $slow)", slow - base < 50)
    }

    @Test fun audioError_stopsSession() = runTest {
        val s = session(Rig())
        s.start(backgroundScope, FakeAudio { emit(NOISE); throw AudioUnavailableException("boom") })
        runCurrent()
        assertEquals(SessionState.Stopped(StopReason.AUDIO_ERROR), s.state.value)
    }

    @Test fun zerosWhilePlaying_setsBlockedWarning_keepsRunning() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = session(Rig(probe = FakeProbe(PlaybackState(true, true))))
        s.start(backgroundScope, FakeAudio { repeat(50) { emit(ZEROS) }; gate.await(); emit(NOISE); awaitCancellation() })
        runCurrent()
        assertEquals(CaptureWarning.BLOCKED_BY_APP, s.captureWarning.value)
        assertEquals(SessionState.Running, s.state.value)
        gate.complete(Unit); runCurrent()
        assertNull(s.captureWarning.value)
    }

    @Test fun zerosWhileNothingPlays_noWarning() = runTest {
        val s = session(Rig(probe = FakeProbe(PlaybackState(false, false))))
        s.start(backgroundScope, FakeAudio { repeat(200) { emit(ZEROS) }; awaitCancellation() })
        runCurrent()
        assertNull(s.captureWarning.value)
        assertEquals(SessionState.Running, s.state.value)
    }

    @Test fun stopWithReason_setsState() = runTest {
        val s = session(Rig())
        s.start(backgroundScope, noiseFrames(3))
        runCurrent()
        s.stop(StopReason.PROJECTION_ENDED)
        assertEquals(SessionState.Stopped(StopReason.PROJECTION_ENDED), s.state.value)
    }

    @Test fun utterancesSurviveStop_clearedOnNextStart() = runTest {
        val rig = Rig(asrEvents = mapOf(1 to listOf(final("one", 0, 500))))
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(3)); runCurrent()
        s.stop()
        assertEquals(1, s.utterances.value.size)
        s.start(backgroundScope, noiseFrames(1)); runCurrent()
        assertTrue(s.utterances.value.size <= 1)
        assertEquals(SessionState.Running, s.state.value)
    }

    @Test fun noSpeechFor30Min_stopsWithIdleTimeout() = runTest {
        val s = session(Rig())
        s.start(backgroundScope, noiseFrames(5))
        advanceTimeBy(30.minutes + 1.seconds); runCurrent()
        assertEquals(SessionState.Stopped(StopReason.IDLE_TIMEOUT), s.state.value)
    }

    @Test fun finalResetsIdleTimer() = runTest {
        val rig = Rig(asrEvents = mapOf(2 to listOf(final("good evening", 0, 900))))
        val s = session(rig)
        s.start(backgroundScope, FakeAudio { emit(NOISE); delay(29.minutes); emit(NOISE); awaitCancellation() })
        advanceTimeBy(31.minutes); runCurrent()
        assertEquals(SessionState.Running, s.state.value)
        advanceTimeBy(30.minutes); runCurrent()
        assertEquals(SessionState.Stopped(StopReason.IDLE_TIMEOUT), s.state.value)
    }

    @Test fun autoStopZero_neverStops() = runTest {
        val rig = Rig()
        rig.settings.setAutoStopMinutes(0)
        val s = session(rig)
        s.start(backgroundScope, noiseFrames(2))
        advanceTimeBy(3.hours); runCurrent()
        assertEquals(SessionState.Running, s.state.value)
    }

    @Test fun startResetsOverlayHidden() = runTest {
        val s = session(Rig())
        s.start(backgroundScope, noiseFrames(1)); runCurrent()
        s.setOverlayHidden(true)
        assertTrue(s.overlayHidden.value)
        s.stop()
        s.start(backgroundScope, noiseFrames(1)); runCurrent()
        assertFalse(s.overlayHidden.value)
    }
}

/** A scope on the test scheduler that the session can run in; cancelled when the test body ends via backgroundScope semantics. */
private class TestScopeJob(testScope: TestScope) {
    val scope = testScope.backgroundScope
}
