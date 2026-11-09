package io.github.thanhngochoang.voicetrans.feature.session

import android.util.Log
import io.github.thanhngochoang.voicetrans.core.audio.AudioSource
import io.github.thanhngochoang.voicetrans.core.audio.CaptureBlockedDetector
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackProbe
import io.github.thanhngochoang.voicetrans.core.audio.PlaybackState
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEngine
import io.github.thanhngochoang.voicetrans.feature.asr.AsrEvent
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClass
import io.github.thanhngochoang.voicetrans.feature.asr.AudioClassifier
import io.github.thanhngochoang.voicetrans.feature.asr.ClassTimeline
import io.github.thanhngochoang.voicetrans.feature.asr.classify
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter
import io.github.thanhngochoang.voicetrans.feature.translate.api.LanguageDetector
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The pipeline for one capture session: audio frames fan out to the ASR engine and to a classifier (own thread, off
 * the ASR path). Each ASR Final is queued. A gate waits for the class of the audio under it: music and singing become (merged) note
 * rows with no source text, speech becomes a row and goes to a second queue that translates one sentence at a time.
 *
 * Fan-out uses two unlimited channels rather than shareIn, so neither consumer can miss early frames.
 */
@Singleton
class TranslationSession @Inject constructor(
    private val asr: AsrEngine,
    private val classifier: AudioClassifier,
    private val detector: LanguageDetector,
    private val translator: Translator,
    private val settings: SettingsRepository,
    private val probe: PlaybackProbe,
) {
    /** Tests replace this with a virtual-time dispatcher. Null means a private single-thread executor per session. */
    internal var classifierDispatcher: CoroutineDispatcher? = null

    private val _utterances = MutableStateFlow<List<Utterance>>(emptyList())
    private val _partial = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val _audioClass = MutableStateFlow(AudioClass.OTHER)
    private val _captureWarning = MutableStateFlow<CaptureWarning?>(null)
    private val _overlayHidden = MutableStateFlow(false)
    private val _translatedAt = MutableSharedFlow<Pair<Utterance, Long>>(extraBufferCapacity = 64)

    /** Survives [stop]; cleared on the next [start]. */
    val utterances: StateFlow<List<Utterance>> = _utterances.asStateFlow()
    val partial: StateFlow<String?> = _partial.asStateFlow()
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Class of the latest 1 s window, for the note indicator. */
    val audioClass: StateFlow<AudioClass> = _audioClass.asStateFlow()

    /** Set when frames are all zero while something plays; cleared by the next non-zero frame. Never stops the session. */
    val captureWarning: StateFlow<CaptureWarning?> = _captureWarning.asStateFlow()

    /** Per-session flag, reset to false on [start]. */
    val overlayHidden: StateFlow<Boolean> = _overlayHidden.asStateFlow()
    fun setOverlayHidden(hidden: Boolean) { _overlayHidden.value = hidden }

    /** Latency hook: the utterance and the ms between its Final and the translation result. */
    val translatedAt: SharedFlow<Pair<Utterance, Long>> = _translatedAt.asSharedFlow()

    private var job: Job? = null
    private var nextId = 0L

    fun start(scope: CoroutineScope, audio: AudioSource) {
        job?.cancel()
        _utterances.value = emptyList()
        _partial.value = null
        _captureWarning.value = null
        _overlayHidden.value = false
        _audioClass.value = AudioClass.OTHER
        nextId = 0L
        _state.value = SessionState.Running
        lateinit var self: Job
        self = scope.launch {
            try {
                runPipeline(audio) { reason -> stopFrom(self, reason) }
            } catch (e: CancellationException) {
                throw e
            }
        }
        job = self
    }

    fun stop(reason: StopReason = StopReason.USER) {
        job?.cancel()
        job = null
        _partial.value = null
        _state.value = SessionState.Stopped(reason)
    }

    private fun stopFrom(caller: Job, reason: StopReason) {
        if (job === caller) stop(reason)
    }

    private class Pending(val id: Long, val text: String, val startMs: Long, val endMs: Long, val finalAtNanos: Long, val wallMs: Long)

    private suspend fun runPipeline(audio: AudioSource, stopWith: (StopReason) -> Unit) {
        val ownDispatcher: ExecutorCoroutineDispatcher? =
            if (classifierDispatcher == null) Executors.newSingleThreadExecutor { r -> Thread(r, "audio-classifier") }.asCoroutineDispatcher() else null
        try {
            pipeline(audio, stopWith, classifierDispatcher ?: ownDispatcher!!)
        } finally {
            ownDispatcher?.close()
        }
    }

    private suspend fun pipeline(audio: AudioSource, stopWith: (StopReason) -> Unit, clsDispatcher: CoroutineDispatcher) = coroutineScope {
        val asrIn = Channel<FloatArray>(Channel.UNLIMITED)
        val clsIn = Channel<FloatArray>(Channel.UNLIMITED)
        val queue = Channel<Pending>(Channel.UNLIMITED)
        val translateQueue = Channel<Pending>(Channel.UNLIMITED)
        val activity = Channel<Unit>(Channel.CONFLATED)
        val timeline = ClassTimeline()
        val classifiedUntil = MutableStateFlow(0L)
        run {
            // 1. capture: detect blocked capture, fan frames out
            launch {
                val detectorState = CaptureBlockedDetector()
                var playback = PlaybackState(false, false)
                var frameNo = 0
                try {
                    audio.frames().collect { frame ->
                        if (frameNo++ % FRAMES_PER_SECOND == 0) playback = probe.state() // once per second
                        if (detectorState.onFrame(frame, playback)) _captureWarning.value = CaptureWarning.BLOCKED_BY_APP
                        if (frame.any { it != 0f }) _captureWarning.value = null
                        asrIn.trySend(frame)
                        clsIn.trySend(frame)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "audio source failed", e)
                    stopWith(StopReason.AUDIO_ERROR)
                } finally {
                    asrIn.close()
                    clsIn.close()
                }
            }
            // 2. ASR
            launch {
                try {
                    asr.transcribe(asrIn.consumeAsFlow()).collect { event ->
                        when (event) {
                            is AsrEvent.Partial -> _partial.value = event.text
                            is AsrEvent.Final -> onFinal(event, queue, activity)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "ASR failed", e)
                    stopWith(StopReason.AUDIO_ERROR)
                }
            }
            // 3. classifier, 1 s windows, off the ASR path
            launch {
                val window = FloatArray(WINDOW_SAMPLES)
                var filled = 0
                var index = 0L
                for (frame in clsIn) {
                    var offset = 0
                    while (offset < frame.size) {
                        val n = minOf(frame.size - offset, WINDOW_SAMPLES - filled)
                        System.arraycopy(frame, offset, window, filled, n)
                        filled += n; offset += n
                        if (filled == WINDOW_SAMPLES) {
                            val copy = window.copyOf()
                            val cls = withContext(clsDispatcher) {
                                runCatching { classify(classifier.classify(copy)) }.getOrDefault(AudioClass.OTHER)
                            }
                            timeline.add(index * WINDOW_MS, cls)
                            _audioClass.value = cls
                            index++
                            classifiedUntil.value = index * WINDOW_MS
                            filled = 0
                        }
                    }
                }
            }
            // 4a. gate: wait for the class of each Final, then append a row (or merge a note row); no sung text is ever shown
            launch {
                for (p in queue) if (admit(p, timeline, classifiedUntil)) translateQueue.trySend(p)
            }
            // 4b. translation queue, one sentence at a time
            launch {
                for (p in translateQueue) translate(p)
            }
            // 5. idle auto-stop
            launch {
                while (true) {
                    val minutes = settings.autoStopMinutes.first()
                    val waitMs = if (minutes == 0) IDLE_POLL_MS else minutes * 60_000L
                    val gotActivity = withTimeoutOrNull(waitMs) { activity.receive() } != null
                    if (!gotActivity && minutes != 0) {
                        stopWith(StopReason.IDLE_TIMEOUT)
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun onFinal(event: AsrEvent.Final, queue: Channel<Pending>, activity: Channel<Unit>) {
        val text = event.text.trim()
        if (text.isEmpty() || isJunkFinal(text, event.startMs, event.endMs)) return // noise: no row, no translation
        activity.trySend(Unit)
        _partial.value = null
        queue.trySend(Pending(nextId++, text, event.startMs, event.endMs, System.nanoTime(), System.currentTimeMillis()))
    }

    /** Returns true when a SPEECH row was appended and the sentence should be translated. */
    private suspend fun admit(p: Pending, timeline: ClassTimeline, classifiedUntil: StateFlow<Long>): Boolean {
        // Wait (bounded) until every window fully inside the sentence is classified.
        withTimeoutOrNull(CLASSIFY_WAIT_MS) { classifiedUntil.first { it >= p.endMs - WINDOW_MS } }
        val cls = timeline.majority(p.startMs, p.endMs)
        val sourceLang = detector.detect(p.text)
        if (cls == AudioClass.MUSIC || cls == AudioClass.SINGING) {
            appendMusic(p, sourceLang)
            return false
        }
        val row = Utterance(
            id = p.id, kind = UtteranceKind.SPEECH, source = p.text, sourceLang = sourceLang,
            targetLang = settings.targetLanguage.first(), translation = null, timestampMs = p.startMs, endMs = p.endMs,
            wallClockMs = p.wallMs,
        )
        _utterances.update { it + row }
        return true
    }

    private suspend fun translate(p: Pending) {
        val target = settings.targetLanguage.first() // read per sentence, at dequeue time
        val row = _utterances.value.firstOrNull { it.id == p.id } ?: return
        update(p.id) { it.copy(targetLang = target) }
        val result = translator.translate(p.text, row.sourceLang)
        val elapsedMs = (System.nanoTime() - p.finalAtNanos) / 1_000_000
        update(p.id) {
            result.fold(
                onSuccess = { t ->
                    val clean = CjkFilter.purgeCjk(t)
                    if (clean.any { ch -> ch.isLetter() } && !CjkFilter.hasCjk(clean)) {
                        it.copy(translation = clean, translationFailed = false)
                    } else {
                        it.copy(translation = null, translationFailed = true)
                    }
                },
                onFailure = { _ -> it.copy(translation = null, translationFailed = true) },
            )
        }
        _utterances.value.firstOrNull { it.id == p.id }?.let { u ->
            Log.i("VT-Latency", "final->translation ${elapsedMs}ms (${u.sourceLang}->${u.targetLang}) [${p.text}] -> [${u.translation}]")
            _translatedAt.tryEmit(u to elapsedMs)
        }
    }

    private fun update(id: Long, transform: (Utterance) -> Utterance) =
        _utterances.update { list -> list.map { if (it.id == id) transform(it) else it } }

    /** Appends a note row, or extends a directly preceding note row. The sung source text is never stored. */
    private suspend fun appendMusic(p: Pending, sourceLang: io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang) {
        val target = settings.targetLanguage.first()
        _utterances.update { list ->
            val last = list.lastOrNull()
            if (last != null && last.kind == UtteranceKind.MUSIC) {
                list.dropLast(1) + last.copy(endMs = maxOf(last.endMs, p.endMs))
            } else {
                list + Utterance(
                    id = p.id, kind = UtteranceKind.MUSIC, source = "", sourceLang = sourceLang, targetLang = target,
                    translation = null, timestampMs = p.startMs, endMs = p.endMs,
                )
            }
        }
    }

    private companion object {
        const val TAG = "TranslationSession"
        const val FRAMES_PER_SECOND = 10
        const val WINDOW_SAMPLES = 16_000
        const val WINDOW_MS = 1_000L
        const val CLASSIFY_WAIT_MS = 2_000L
        const val IDLE_POLL_MS = 60_000L
    }
}
