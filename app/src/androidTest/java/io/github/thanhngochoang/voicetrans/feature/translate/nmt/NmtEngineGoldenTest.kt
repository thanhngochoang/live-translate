package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import android.content.res.AssetManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.github.thanhngochoang.voicetrans.TestModels
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter
import java.io.File
import org.json.JSONArray
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class NmtEngineGoldenTest {

    data class SuiteItem(
        val id: String,
        val lang: String,
        val text: String,
        val final: String,
        val draft: String
    )

    companion object {
        private const val TAG = "NmtEngineGoldenTest"
        private lateinit var env: OrtEnvironment
        private lateinit var nllbEngine: Seq2SeqEngine
        private lateinit var opusEngine: Seq2SeqEngine
        private lateinit var nllbTokenizer: NllbTokenizer
        private lateinit var opusTokenizer: MarianTokenizer
        private lateinit var suiteItems: List<SuiteItem>
        private lateinit var nllbMeta: NmtMeta
        private lateinit var opusMeta: NmtMeta

        private val testAssets: AssetManager
            get() = InstrumentationRegistry.getInstrumentation().context.assets

        @BeforeClass
        @JvmStatic
        fun setUp() {
            System.loadLibrary("onnxruntime")
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
            }

            val nllbDir = File(TestModels.dir(), "nmt/nllb-600m")
            nllbMeta = NmtMeta.parse(File(nllbDir, "meta.json").readText())
            val nllbEnc = env.createSession(File(nllbDir, "encoder_model.onnx").path, opts)
            val nllbDec = env.createSession(File(nllbDir, "decoder_model_merged.onnx").path, opts)
            nllbEngine = Seq2SeqEngine(nllbEnc, nllbDec, nllbMeta, env)
            nllbTokenizer = File(nllbDir, "sentencepiece.bpe.model").inputStream().use { NllbTokenizer(it, nllbMeta) }

            val opusDir = File(TestModels.dir(), "nmt/opus-zh-vi")
            opusMeta = NmtMeta.parse(File(opusDir, "meta.json").readText())
            val opusEnc = env.createSession(File(opusDir, "encoder_model.onnx").path, opts)
            val opusDec = env.createSession(File(opusDir, "decoder_model_merged.onnx").path, opts)
            opusEngine = Seq2SeqEngine(opusEnc, opusDec, opusMeta, env)
            opusTokenizer = MarianTokenizer(
                File(opusDir, "source.spm").readBytes().inputStream(),
                File(opusDir, "target.spm").readBytes().inputStream(),
                File(opusDir, "vocab.json").readBytes().inputStream(),
                opusMeta
            )

            val jsonStr = testAssets.open("nmt/translations.json").bufferedReader().readText()
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<SuiteItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SuiteItem(
                        id = obj.getString("id"),
                        lang = obj.getString("lang"),
                        text = obj.getString("text"),
                        final = obj.getString("final"),
                        draft = obj.getString("draft")
                    )
                )
            }
            suiteItems = list
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::nllbEngine.isInitialized) nllbEngine.close()
            if (::opusEngine.isInitialized) opusEngine.close()
        }
    }

    private fun translate(text: String, lang: String, beams: Int, maxNewTokens: Int): Pair<String, IntArray> {
        return if (lang == "EN") {
            val inputIds = nllbTokenizer.encode(text)
            val outputIds = nllbEngine.generate(inputIds, beams, maxNewTokens)
            val vi = nllbTokenizer.decode(outputIds).trim()
            vi to outputIds
        } else {
            val inputIds = opusTokenizer.encode(text)
            val outputIds = opusEngine.generate(inputIds, beams, maxNewTokens)
            val vi = opusTokenizer.decode(outputIds).trim()
            vi to outputIds
        }
    }

    @Test
    fun finals_matchReference() {
        val testLines = suiteItems.filter { it.id.matches(Regex("^(en|zh)\\d+$")) }
        assertEquals("Expected 20 suite lines", 20, testLines.size)

        var matchCount = 0
        for (item in testLines) {
            val (actual, _) = translate(item.text, item.lang, beams = 4, maxNewTokens = 128)
            val expected = item.final
            if (actual == expected) {
                matchCount++
            } else {
                Log.w(TAG, "GOLDEN-DIFF ${item.id} expected: '$expected' actual: '$actual'")
            }
        }
        Log.i(TAG, "Finals matched: $matchCount / ${testLines.size}")
        assertTrue("At least 17/20 lines must match reference final, but was $matchCount", matchCount >= 17)
    }

    @Test
    fun drafts_nonEmpty_noCjk() {
        val testLines = suiteItems.filter { it.id.matches(Regex("^(en|zh)\\d+$")) }
        assertEquals("Expected 20 suite lines", 20, testLines.size)

        for (item in testLines) {
            val (actual, _) = translate(item.text, item.lang, beams = 1, maxNewTokens = 64)
            assertTrue("Draft for ${item.id} must be non-empty", actual.isNotBlank())
            assertFalse("Draft for ${item.id} must not contain CJK: '$actual'", CjkFilter.hasCjk(actual))
        }
    }

    @Test
    fun longLine_notTruncated() {
        val longItem = suiteItems.first { it.id == "rf02_long_en" }
        val (_, outputIds) = translate(longItem.text, "EN", beams = 4, maxNewTokens = 128)
        assertTrue("Generated token count (${outputIds.size}) must be below 128", outputIds.size < 128)
        assertTrue("Generated sequence must end with EOS (${nllbMeta.eos})", outputIds.isNotEmpty() && outputIds.last() == nllbMeta.eos)
    }

    @Test
    fun codeSwitchedLines_translated() {
        val zhItem = suiteItems.first { it.id == "rf05_mixed_zh" } // "我们明天开meeting"
        val enItem = suiteItems.first { it.id == "rf05_mixed_en" } // "let's go to the 公司 now"

        val (zhActual, _) = translate(zhItem.text, "ZH", beams = 4, maxNewTokens = 128)
        assertTrue("ZH mixed line output must be non-empty", zhActual.isNotBlank())
        assertFalse("ZH mixed line must have no CJK: '$zhActual'", CjkFilter.hasCjk(zhActual))
        assertEquals("ZH mixed line must match reference final", zhItem.final, zhActual)

        val (enActual, _) = translate(enItem.text, "EN", beams = 4, maxNewTokens = 128)
        assertTrue("EN mixed line output must be non-empty", enActual.isNotBlank())
        assertFalse("EN mixed line must have no CJK: '$enActual'", CjkFilter.hasCjk(enActual))
        assertEquals("EN mixed line must match reference final", enItem.final, enActual)
    }

    @Test
    fun latency_logged() {
        val sample = suiteItems.filter { it.id.matches(Regex("^(en|zh)\\d+$")) }.take(6)
        val finalLatencies = mutableListOf<Long>()
        val draftLatencies = mutableListOf<Long>()

        for (item in sample) {
            val t0 = System.currentTimeMillis()
            translate(item.text, item.lang, beams = 4, maxNewTokens = 128)
            finalLatencies.add(System.currentTimeMillis() - t0)

            val t1 = System.currentTimeMillis()
            translate(item.text, item.lang, beams = 1, maxNewTokens = 64)
            draftLatencies.add(System.currentTimeMillis() - t1)
        }

        finalLatencies.sort()
        draftLatencies.sort()

        val fP50 = finalLatencies[finalLatencies.size / 2]
        val fP95 = finalLatencies[(finalLatencies.size * 0.95).toInt().coerceAtMost(finalLatencies.size - 1)]
        val dP50 = draftLatencies[draftLatencies.size / 2]
        val dP95 = draftLatencies[(draftLatencies.size * 0.95).toInt().coerceAtMost(draftLatencies.size - 1)]

        Log.i(TAG, "VT-NMT mode=final p50=$fP50 p95=$fP95")
        Log.i(TAG, "VT-NMT mode=draft p50=$dP50 p95=$dP95")
    }
}
