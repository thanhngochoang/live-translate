package io.github.thanhngochoang.voicetrans.feature.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Silero VAD gates a streaming zipformer zh-en recognizer: during silence nothing is decoded.
 * Native objects are created once here and used only on [dispatcher] (they are not thread-safe).
 */
class SherpaAsrEngine(
    assets: AssetManager,
    private val config: AsrConfig = AsrConfig(),
) : AsrEngine {

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "asr-engine") }.asCoroutineDispatcher()

    private val recognizer: OnlineRecognizer
    private val vad: Vad

    init {
        val dir = "models/asr"
        recognizer = OnlineRecognizer(
            assets,
            OnlineRecognizerConfig(
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
        vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(model = "models/vad/silero_vad.onnx"),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ),
        )
    }

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
            frames.collect { frame ->
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
                    emit(AsrEvent.Partial(text))
                }
                if (recognizer.isEndpoint(stream)) {
                    if (text.isNotEmpty()) emit(AsrEvent.Final(text, toMs(startSample), toMs(position)))
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
            recognizer.release()
            vad.release()
        }
        (dispatcher.executor as java.util.concurrent.ExecutorService).shutdown()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
