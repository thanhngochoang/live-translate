package io.github.thanhngochoang.voicetrans.feature.asr

import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import java.io.File

/**
 * Audio tagging (AudioSet labels) with the downloaded model under [modelDir]/tagging. Top-k 10, 1 thread.
 * The native model is loaded on the first [classify], so building this class never needs the files.
 */
class SherpaAudioClassifier(private val modelDir: File) : AudioClassifier {
    private val taggingLazy = lazy {
        AudioTagging(
            config = AudioTaggingConfig(
                model = AudioTaggingModelConfig(
                    ced = File(modelDir, "tagging/model.int8.onnx").path,
                    numThreads = 1,
                ),
                labels = File(modelDir, "tagging/class_labels_indices.csv").path,
                topK = 10,
            ),
        )
    }
    private val tagging: AudioTagging by taggingLazy

    override fun classify(window: FloatArray): Map<String, Float> {
        val stream = tagging.createStream()
        try {
            stream.acceptWaveform(window, SAMPLE_RATE)
            return tagging.compute(stream, 10).associate { it.name to it.prob }
        } finally {
            stream.release()
        }
    }

    override fun close() { if (taggingLazy.isInitialized()) tagging.release() }

    private companion object { const val SAMPLE_RATE = 16000 }
}
