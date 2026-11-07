package io.github.thanhngochoang.voicetrans

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaSmokeTest {

    private val modelAssets: AssetManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    private val testAssets: AssetManager
        get() = InstrumentationRegistry.getInstrumentation().context.assets

    private fun newRecognizer(): OnlineRecognizer {
        val dir = "models/asr"
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder-epoch-99-avg-1.int8.onnx",
                    decoder = "$dir/decoder-epoch-99-avg-1.onnx",
                    joiner = "$dir/joiner-epoch-99-avg-1.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 2,
                modelType = "zipformer",
            ),
            enableEndpoint = false,
        )
        return OnlineRecognizer(modelAssets, config)
    }

    /** Reads a 16 kHz mono PCM16 WAV (44-byte header assumed to be standard; data chunk searched) as floats. */
    private fun readWav(name: String): FloatArray {
        val bytes = testAssets.open("wav/$name").use { it.readBytes() }
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val n = minOf(size, bytes.size - pos - 8) / 2
                val sb = ByteBuffer.wrap(bytes, pos + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(n) { sb.get(it) / 32768f }
            }
            pos += 8 + size
        }
        error("no data chunk in $name")
    }

    private fun decode(wav: String): String {
        val recognizer = newRecognizer()
        try {
            val stream = recognizer.createStream("")
            try {
                stream.acceptWaveform(readWav(wav), 16000)
                stream.acceptWaveform(FloatArray(16000), 16000) // 1 s of silence flushes the tail
                stream.inputFinished()
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                return recognizer.getResult(stream).text
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }

    @Test
    fun decodesEnglishWav() {
        val text = decode("en_good_morning.wav")
        assertTrue("got: '$text'", text.lowercase().contains("morning"))
    }

    @Test
    fun decodesChineseWav() {
        val text = decode("zh_zaoshanghao.wav")
        assertTrue("got: '$text'", text.contains("早上好"))
    }
}
