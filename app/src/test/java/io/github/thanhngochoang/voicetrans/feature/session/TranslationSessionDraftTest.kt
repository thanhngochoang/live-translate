package io.github.thanhngochoang.voicetrans.feature.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.thanhngochoang.voicetrans.core.audio.AudioSource
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackProbe
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackState
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEngine
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEvent
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClassifier
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkRatioLanguageDetector
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDraftDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

private val DRAFT_FRAME get() = FloatArray(1600) { 0.1f }

private class FakeDraftAudio(private val body: suspend FlowCollector<FloatArray>.() -> Unit) : AudioSource {
    override fun frames(): Flow<FloatArray> = flow { body() }
}

private class FakeDraftAsr(private val events: Map<Int, List<AsrEvent>>) : AsrEngine {
    override fun transcribe(frames: Flow<FloatArray>): Flow<AsrEvent> = flow {
        var n = 0
        frames.collect { n++; events[n]?.forEach { emit(it) } }
    }
    override fun close() {}
}

private class FakeDraftClassifier(private val isMusic: Boolean = false) : AudioClassifier {
    override fun classify(window: FloatArray): Map<String, Float> =
        if (isMusic) mapOf("Music" to 0.9f) else mapOf("Speech" to 0.9f)
    override fun close() {}
}

private class FakeDraftTranslator(
    private val onTranslate: suspend (String, SourceLang, TranslateMode) -> Result<String> = { text, _, mode ->
        Result.success("[$mode]:$text")
    }
) : Translator {
    override val state = MutableStateFlow<EngineState>(EngineState.Ready)
    val calls = mutableListOf<Triple<String, SourceLang, TranslateMode>>()

    override suspend fun translate(text: String, from: SourceLang, mode: TranslateMode): Result<String> {
        calls += Triple(text, from, mode)
        return onTranslate(text, from, mode)
    }

    override fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSessionDraftTest {

    private fun TestScope.session(
        translator: Translator,
        settings: SettingsRepository = SettingsRepository(FakeDraftDataStore()),
        asrEvents: Map<Int, List<AsrEvent>> = emptyMap(),
        isMusic: Boolean = false,
    ): TranslationSession =
        TranslationSession(
            FakeDraftAsr(asrEvents),
            FakeDraftClassifier(isMusic),
            CjkRatioLanguageDetector(),
            translator,
            settings,
            PlaybackProbe { PlaybackState(false, false) }
        ).also { it.classifierDispatcher = StandardTestDispatcher(testScheduler) }

    private fun frames(n: Int) = FakeDraftAudio {
        repeat(n) { emit(DRAFT_FRAME); delay(100) }
        awaitCancellation()
    }

    @Test
    fun partialGrows_draftShown() = runTest {
        val tr = FakeDraftTranslator()
        val s = session(
            translator = tr,
            asrEvents = mapOf(
                1 to listOf(AsrEvent.Partial("I didn't")),
                2 to listOf(AsrEvent.Partial("I didn't mean to")),
                3 to listOf(AsrEvent.Partial("I didn't mean to hurt you")),
            )
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(500); runCurrent()

        // 2 calls: one for "I didn't mean to", one for "I didn't mean to hurt you"
        assertEquals(2, tr.calls.size)
        assertEquals(TranslateMode.DRAFT, tr.calls[0].third)
        assertEquals("I didn't mean to", tr.calls[0].first)
        assertEquals(TranslateMode.DRAFT, tr.calls[1].third)
        assertEquals("I didn't mean to hurt you", tr.calls[1].first)
        assertEquals("[DRAFT]:I didn't mean to hurt you", s.partialTranslation.value)
    }

    @Test
    fun finalQueued_runsBeforeDraft() = runTest {
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<TranslateMode>()
        val tr = FakeDraftTranslator { _, _, mode ->
            order += mode
            if (mode == TranslateMode.DRAFT) {
                gate.await()
            }
            Result.success("ok")
        }
        val s = session(
            translator = tr,
            asrEvents = mapOf(
                1 to listOf(
                    AsrEvent.Final("good morning", 0, 500),
                    AsrEvent.Partial("I didn't mean to"),
                ),
            )
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(400); runCurrent()
        gate.complete(Unit)
        advanceTimeBy(500); runCurrent()

        assertTrue(order.isNotEmpty())
        assertEquals(TranslateMode.FINAL, order[0])
    }

    @Test
    fun drafts_latestWins() = runTest {
        val draftGate = CompletableDeferred<Unit>()
        val drafted = mutableListOf<String>()
        val tr = FakeDraftTranslator { text, _, mode ->
            if (mode == TranslateMode.DRAFT) {
                drafted += text
                if (drafted.size == 1) draftGate.await()
            }
            Result.success("ok")
        }
        val s = session(
            translator = tr,
            asrEvents = mapOf(
                1 to listOf(AsrEvent.Partial("one two three")),
                2 to listOf(AsrEvent.Partial("one two three four five")),
                3 to listOf(AsrEvent.Partial("one two three four five six seven")),
                4 to listOf(AsrEvent.Partial("one two three four five six seven eight nine")),
            )
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(200); runCurrent()
        assertEquals(1, drafted.size)
        assertEquals("one two three", drafted[0])

        advanceTimeBy(300); runCurrent()
        draftGate.complete(Unit)
        advanceTimeBy(500); runCurrent()

        // After the first draft finished, only the latest partial was translated
        assertEquals(2, drafted.size)
        assertEquals("one two three four five six seven eight nine", drafted[1])
    }

    @Test
    fun shrinkingPartial_noNewDraft() = runTest {
        val tr = FakeDraftTranslator()
        val s = session(
            translator = tr,
            asrEvents = mapOf(
                1 to listOf(AsrEvent.Partial("I didn't mean to")),
                2 to listOf(AsrEvent.Partial("I didn't mean")),
            )
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(500); runCurrent()

        assertEquals(1, tr.calls.size)
        assertEquals("I didn't mean to", tr.calls[0].first)
    }

    @Test
    fun handOver_draftClearedWhenFirstRowTranslated() = runTest {
        val finalGate = CompletableDeferred<Unit>()
        val tr = FakeDraftTranslator { text, _, mode ->
            if (mode == TranslateMode.FINAL) finalGate.await()
            Result.success("[$mode]:$text")
        }
        val s = session(
            translator = tr,
            asrEvents = mapOf(
                1 to listOf(AsrEvent.Partial("I didn't mean to")),
                2 to listOf(AsrEvent.Final("I didn't mean to", 0, 500)),
            )
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(300); runCurrent()

        // Draft is shown while final is translating
        assertEquals("[DRAFT]:I didn't mean to", s.partialTranslation.value)

        finalGate.complete(Unit)
        advanceTimeBy(300); runCurrent()

        // Row translated -> draft cleared
        assertNull(s.partialTranslation.value)
    }

    @Test
    fun handOver_newerDraftKept() = runTest {
        val finalGate = CompletableDeferred<Unit>()
        val tr = FakeDraftTranslator { text, _, mode ->
            if (mode == TranslateMode.FINAL && text.contains("first")) finalGate.await()
            Result.success("[$mode]:$text")
        }
        val s = session(
            translator = tr,
            asrEvents = mapOf(
                1 to listOf(AsrEvent.Partial("first sentence here")),
                2 to listOf(AsrEvent.Final("first sentence here", 0, 500)),
                3 to listOf(AsrEvent.Partial("second sentence starting now")),
            )
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(400); runCurrent()

        finalGate.complete(Unit)
        advanceTimeBy(500); runCurrent()

        // The second sentence's draft replaced the old one, so it was kept
        assertEquals("[DRAFT]:second sentence starting now", s.partialTranslation.value)
    }

    @Test
    fun musicFinal_clearsDraft() = runTest {
        val tr = FakeDraftTranslator()
        val s = session(
            translator = tr,
            asrEvents = mapOf(
                1 to listOf(AsrEvent.Partial("la la la la la")),
                2 to listOf(AsrEvent.Final("la la la la la", 0, 500)),
            ),
            isMusic = true
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(500); runCurrent()

        assertNull(s.partialTranslation.value)
    }

    @Test
    fun switchOff_noDrafts() = runTest {
        val tr = FakeDraftTranslator()
        val settings = SettingsRepository(FakeDraftDataStore())
        settings.setDraftTranslation(false)
        val s = session(
            translator = tr,
            settings = settings,
            asrEvents = mapOf(
                1 to listOf(AsrEvent.Partial("one two three four five")),
            )
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(500); runCurrent()

        assertTrue(tr.calls.isEmpty())
        assertNull(s.partialTranslation.value)
    }

    @Test
    fun stop_clearsDraft() = runTest {
        val tr = FakeDraftTranslator()
        val s = session(
            translator = tr,
            asrEvents = mapOf(
                1 to listOf(AsrEvent.Partial("one two three four five")),
            )
        )
        s.start(backgroundScope, frames(10))
        advanceTimeBy(300); runCurrent()
        assertEquals("[DRAFT]:one two three four five", s.partialTranslation.value)

        s.stop()
        assertNull(s.partialTranslation.value)
    }
}
