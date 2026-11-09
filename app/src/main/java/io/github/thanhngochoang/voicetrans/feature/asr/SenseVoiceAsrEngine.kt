package io.github.thanhngochoang.voicetrans.feature.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Silero VAD gates a SenseVoice offline recognizer:
 * Audio frames are accepted by Silero VAD. While a speech segment is open, samples collected so far
 * are re-decoded periodically to emit Partials. When a VAD segment completes (or reaches max speech duration),
 * it is decoded once to emit a Final with stream-position timestamps that include the pre-roll.
 * Native objects are created once here and used only on [dispatcher] (they are not thread-safe).
 */
class SenseVoiceAsrEngine(
    assets: AssetManager,
    private val config: AsrConfig = AsrConfig(),
) : AsrEngine {

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "asr-engine") }.asCoroutineDispatcher()

    /** True once the native recognizer and VAD have been released. */
    @Volatile
    var isReleased = false
        private set

    private val recognizer: OfflineRecognizer
    private val vad: Vad

    init {
        val dir = "models/asr-sv"
        recognizer = OfflineRecognizer(
            assets,
            OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$dir/model.int8.onnx",
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$dir/tokens.txt",
                    numThreads = config.numThreads,
                ),
            ),
        )
        vad = Vad(
            assets,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "models/vad/silero_vad.onnx",
                    minSilenceDuration = config.vadMinSilenceS,
                    maxSpeechDuration = config.vadMaxSpeechS,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ),
        )
    }

    override fun transcribe(frames: Flow<FloatArray>): Flow<AsrEvent> = flow {
        vad.reset()
        val partialIntervalSamples = config.partialEveryMs * SAMPLE_RATE / 1000
        val maxSpeechSamples = (config.vadMaxSpeechS * SAMPLE_RATE).toInt()
        val currentSegmentFrames = mutableListOf<FloatArray>()
        var currentSegmentSamples = 0
        var currentSegmentStartSample = 0L
        var totalSamplesConsumed = 0L
        var samplesSinceLastPartial = 0
        var lastPartial = ""
        var inSpeech = false

        fun decodeSamples(samples: FloatArray): String {
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)
                return recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

        suspend fun drainVad() {
            var popped = false
            while (!vad.empty()) {
                val seg = vad.front()
                val samples = seg.samples.copyOf()
                val segStart = seg.start
                vad.pop()
                popped = true
                if (samples.isEmpty()) continue
                val text = decodeSamples(samples)
                if (text.isNotEmpty()) {
                    val startMs = maxOf(0L, segStart * 1000 / SAMPLE_RATE - config.preRollMs)
                    val endMs = (segStart + samples.size) * 1000 / SAMPLE_RATE
                    emit(AsrEvent.Final(text, startMs, endMs))
                }
            }
            if (popped) {
                currentSegmentFrames.clear()
                currentSegmentSamples = 0
                samplesSinceLastPartial = 0
                lastPartial = ""
            }
        }

        frames.flowOn(Dispatchers.Default).buffer(Channel.UNLIMITED).collect { frame ->
            val frameStart = totalSamplesConsumed
            totalSamplesConsumed += frame.size

            vad.acceptWaveform(frame)
            drainVad()

            val speechNow = vad.isSpeechDetected()
            if (!inSpeech && speechNow) {
                inSpeech = true
                currentSegmentFrames.clear()
                currentSegmentSamples = 0
                currentSegmentStartSample = frameStart
                samplesSinceLastPartial = 0
                lastPartial = ""
            }

            if (inSpeech) {
                currentSegmentFrames.add(frame)
                currentSegmentSamples += frame.size
                samplesSinceLastPartial += frame.size

                if (currentSegmentSamples >= maxSpeechSamples) {
                    val combined = FloatArray(currentSegmentSamples)
                    var offset = 0
                    for (f in currentSegmentFrames) {
                        System.arraycopy(f, 0, combined, offset, f.size)
                        offset += f.size
                    }
                    val text = decodeSamples(combined)
                    if (text.isNotEmpty()) {
                        val startMs = maxOf(0L, currentSegmentStartSample * 1000 / SAMPLE_RATE - config.preRollMs)
                        val endMs = totalSamplesConsumed * 1000 / SAMPLE_RATE
                        emit(AsrEvent.Final(text, startMs, endMs))
                    }
                    currentSegmentFrames.clear()
                    currentSegmentSamples = 0
                    currentSegmentStartSample = totalSamplesConsumed
                    samplesSinceLastPartial = 0
                    lastPartial = ""
                    vad.reset()
                } else if (samplesSinceLastPartial >= partialIntervalSamples) {
                    samplesSinceLastPartial = 0
                    val combined = FloatArray(currentSegmentSamples)
                    var offset = 0
                    for (f in currentSegmentFrames) {
                        System.arraycopy(f, 0, combined, offset, f.size)
                        offset += f.size
                    }
                    val partialText = decodeSamples(combined)
                    if (partialText.isNotEmpty() && partialText != lastPartial) {
                        lastPartial = partialText
                        emit(AsrEvent.Partial(partialText))
                    }
                }
            }

            if (!speechNow) {
                inSpeech = false
                currentSegmentFrames.clear()
                currentSegmentSamples = 0
                samplesSinceLastPartial = 0
                lastPartial = ""
            }
        }

        vad.flush()
        drainVad()
    }.flowOn(dispatcher)

    override fun close() {
        dispatcher.executor.execute {
            recognizer.release()
            vad.release()
            isReleased = true
        }
        (dispatcher.executor as java.util.concurrent.ExecutorService).shutdown()
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
