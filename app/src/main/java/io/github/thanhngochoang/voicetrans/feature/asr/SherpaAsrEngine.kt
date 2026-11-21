package io.github.thanhngochoang.voicetrans.feature.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silero VAD gates a streaming zipformer zh-en recognizer: during silence nothing is decoded.
 * The models are read from [modelDir] (downloaded files, see ModelStore). Native objects are created on first use,
 * so building this class never needs the files, and they are used only on [dispatcher] (they are not thread-safe).
 */
@Singleton
class SherpaAsrEngine @Inject constructor(
    private val modelDir: File,
    private val config: AsrConfig = AsrConfig(),
) : AsrEngine {

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "asr-engine") }.asCoroutineDispatcher()

    /** True once the native recognizer and VAD have been released. */
    @Volatile var isReleased = false
        private set

    private val recognizerLazy = lazy {
        val dir = File(modelDir, "asr").path
        OnlineRecognizer(
            config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$dir/encoder-epoch-99-avg-1.int8.onnx",
                        decoder = "$dir/decoder-epoch-99-avg-1.onnx",
                        joiner = "$dir/joiner-epoch-99-avg-1.int8.onnx",
                    ),
                    tokens = "$dir/tokens.txt",
                    numThreads = config.numThreads,
                    modelType = "zipformer",
                ),
                endpointConfig = EndpointConfig(
                    EndpointRule(false, 2.4f, 0f),
                    EndpointRule(true, config.rule2TrailingSilenceS, 0f),
                    EndpointRule(false, 0f, config.rule3MaxUtteranceS),
                ),
                enableEndpoint = true,
            ),
        )
    }
    private val vadLazy = lazy {
        Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File(modelDir, "vad/silero_vad.onnx").path,
                    threshold = 0.35f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.15f,
                    maxSpeechDuration = 12.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ),
        )
    }
    private val recognizer: OnlineRecognizer by recognizerLazy
    private val vad: Vad by vadLazy

    override fun transcribe(frames: Flow<FloatArray>): Flow<AsrEvent> = flow {
        val stream = recognizer.createStream("")
        val preRollSamples = config.preRollMs * SAMPLE_RATE / 1000
        val ring = ArrayDeque<FloatArray>()
        var ringSamples = 0
        var inSpeech = false
        var lastPartial = ""
        var position = 0L // samples consumed so far
        var startSample = 0L
        vad.reset()
        try {
            // Run the source on its own context behind an unlimited buffer so capture never waits for decoding.
            frames.flowOn(Dispatchers.Default).buffer(Channel.UNLIMITED).collect { frame ->
                val frameStart = position
                position += frame.size
                vad.acceptWaveform(frame)
                while (!vad.empty()) vad.pop() // we only use the detection flag; drop buffered segments

                if (!inSpeech && vad.isSpeechDetected()) {
                    inSpeech = true
                    startSample = frameStart - ringSamples
                    for (r in ring) stream.acceptWaveform(r, SAMPLE_RATE)
                    ring.clear(); ringSamples = 0
                    stream.acceptWaveform(frame, SAMPLE_RATE)
                } else if (inSpeech) {
                    stream.acceptWaveform(frame, SAMPLE_RATE)
                } else {
                    ring.addLast(frame); ringSamples += frame.size
                    while (ring.size > 1 && ringSamples - ring.first().size >= preRollSamples) {
                        ringSamples -= ring.removeFirst().size
                    }
                    return@collect // skip decoding during silence (battery)
                }

                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    android.util.Log.d("VT-ASR", "Partial: $text")
                    emit(AsrEvent.Partial(text))
                }
                if (recognizer.isEndpoint(stream)) {
                    if (text.isNotEmpty()) {
                        android.util.Log.i("VT-ASR", "Final: $text [${toMs(startSample)}..${toMs(position)}ms]")
                        emit(AsrEvent.Final(text, toMs(startSample), toMs(position)))
                    }
                    recognizer.reset(stream)
                    inSpeech = vad.isSpeechDetected()
                    if (inSpeech) startSample = position
                    lastPartial = ""
                }
            }
            if (inSpeech) { // input ended mid-utterance: flush what we have
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                val text = recognizer.getResult(stream).text.trim()
                if (text.isNotEmpty()) emit(AsrEvent.Final(text, toMs(startSample), toMs(position)))
            }
        } finally {
            stream.release()
        }
    }.flowOn(dispatcher)

    private fun toMs(samples: Long) = samples * 1000 / SAMPLE_RATE

    override fun close() {
        // Run on the engine thread so it cannot overlap with a running transcribe().
        dispatcher.executor.execute {
            if (recognizerLazy.isInitialized()) recognizer.release()
            if (vadLazy.isInitialized()) vad.release()
            isReleased = true
        }
        (dispatcher.executor as java.util.concurrent.ExecutorService).shutdown()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
