package io.github.thanhngochoang.voicetrans.feature.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig

/** Audio tagging (AudioSet labels) with the model bundled under assets/models/tagging. Top-k 10, 1 thread. */
class SherpaAudioClassifier(assets: AssetManager) : AudioClassifier {
    private val tagging = AudioTagging(
        assets,
        AudioTaggingConfig(
            model = AudioTaggingModelConfig(
                ced = "models/tagging/model.int8.onnx",
                numThreads = 1,
            ),
            labels = "models/tagging/class_labels_indices.csv",
            topK = 10,
        ),
    )

    override fun classify(window: FloatArray): Map<String, Float> {
        val stream = tagging.createStream()
        try {
            stream.acceptWaveform(window, SAMPLE_RATE)
            return tagging.compute(stream, 10).associate { it.name to it.prob }
        } finally {
            stream.release()
        }
    }

    override fun close() = tagging.release()

    private companion object { const val SAMPLE_RATE = 16000 }
}
