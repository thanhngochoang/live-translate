package io.github.thanhngochoang.voicetrans.feature.overlay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.core.audio.AudioSource
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackProbe
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackState
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEngine
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEvent
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClassifier
import io.github.thanhngochoang.voicetrans.feature.session.TranslationSession
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkRatioLanguageDetector
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

class MemStore : DataStore<Preferences> {
    private val s = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = s
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = transform(s.value).also { s.value = it }
}

class FakeAsr : AsrEngine {
    @Volatile var isReleased = false
    override fun transcribe(frames: Flow<FloatArray>): Flow<AsrEvent> = emptyFlow()
    override fun close() { isReleased = true }
}

class FakeClassifier : AudioClassifier {
    @Volatile var isClosed = false
    override fun classify(window: FloatArray) = emptyMap<String, Float>()
    override fun close() { isClosed = true }
}

class FakeTranslator : Translator {
    @Volatile var isClosed = false
    override val state = MutableStateFlow<EngineState>(EngineState.Ready)
    override suspend fun translate(text: String, from: SourceLang, mode: TranslateMode) = Result.success(text)
    override fun close() { isClosed = true }
}

/** A real [TranslationSession] with fake engines, running on its own scope. */
class SessionRig {
    val asr = FakeAsr()
    val classifier = FakeClassifier()
    val translator = FakeTranslator()
    val settings = SettingsRepository(MemStore())
    val session = TranslationSession(asr, classifier, CjkRatioLanguageDetector(), translator, settings, PlaybackProbe { PlaybackState(false, false) })
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun startRunning() = session.start(scope, object : AudioSource {
        override fun frames(): Flow<FloatArray> = flow { awaitCancellation() }
    })

    fun close() { session.stop(); scope.cancel() }
}

fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

fun waitFor(timeoutMs: Long = 3000, what: String = "condition", cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        Thread.sleep(20)
    }
    throw AssertionError("timed out waiting for $what")
}
