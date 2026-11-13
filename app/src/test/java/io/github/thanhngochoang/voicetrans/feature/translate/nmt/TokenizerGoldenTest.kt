package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class TokenizerGoldenTest {

    data class TokenizerGolden(val text: String, val ids: IntArray)
    data class GoldenSet(val encode: List<TokenizerGolden>, val decode: List<TokenizerGolden>)

    private lateinit var nllb: SubwordTokenizer
    private lateinit var marian: SubwordTokenizer

    private fun goldens(model: String): GoldenSet {
        val stream = javaClass.classLoader?.getResourceAsStream("nmt/tokenizer-$model.json")
            ?: error("Missing test resource nmt/tokenizer-$model.json (run scripts/export-nmt.py)")
        val jsonStr = stream.bufferedReader().use { it.readText() }

        val encodeList = parseEntries(jsonStr, "encode")
        val decodeList = parseEntries(jsonStr, "decode")
        return GoldenSet(encodeList, decodeList)
    }

    private fun parseEntries(json: String, key: String): List<TokenizerGolden> {
        val sectionMatch = Regex(""""$key"\s*:\s*\[(.*?)\]\s*(?:,\s*"|\})""", RegexOption.DOT_MATCHES_ALL).find(json)
            ?: error("Section $key not found in json")
        val sectionContent = sectionMatch.groupValues[1]

        val list = mutableListOf<TokenizerGolden>()
        val objRegex = Regex("""\{[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)
        for (m in objRegex.findAll(sectionContent)) {
            val objStr = m.value
            val textMatch = Regex(""""text"\s*:\s*"((?:\\.|[^"\\])*)"""").find(objStr)
                ?: error("text not found in $objStr")
            val text = unescapeJson(textMatch.groupValues[1])

            val idsMatch = Regex(""""ids"\s*:\s*\[([\d,\s]*)\]""").find(objStr)
                ?: error("ids not found in $objStr")
            val ids = Regex("""\d+""").findAll(idsMatch.groupValues[1]).map { it.value.toInt() }.toList().toIntArray()

            list.add(TokenizerGolden(text, ids))
        }
        return list
    }

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val next = s[i + 1]) {
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '/' -> { sb.append('/'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, i + 6)
                        sb.append(hex.toInt(16).toChar())
                        i += 6
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    @Before
    fun setUp() {
        val baseDir = if (File("src/main/assets").exists()) File(".") else File("app")
        val assetsDir = File(baseDir, "src/main/assets/models/nmt")
        val nllbDir = File(assetsDir, "nllb-600m")
        val opusDir = File(assetsDir, "opus-zh-vi")

        val nllbMetaFile = File(nllbDir, "meta.json")
        val opusMetaFile = File(opusDir, "meta.json")
        if (!nllbMetaFile.exists() || !opusMetaFile.exists()) {
            error("Model files missing in src/main/assets/models/nmt/ (run scripts/export-nmt.py)")
        }

        val nllbMeta = NmtMeta.parse(nllbMetaFile.readText())
        val opusMeta = NmtMeta.parse(opusMetaFile.readText())

        val nllbSpm = File(nllbDir, "sentencepiece.bpe.model")
        nllb = NllbTokenizer(FileInputStream(nllbSpm), nllbMeta)

        val opusSrcSpm = File(opusDir, "source.spm")
        val opusTgtSpm = File(opusDir, "target.spm")
        val opusVocab = File(opusDir, "vocab.json")
        marian = MarianTokenizer(
            FileInputStream(opusSrcSpm),
            FileInputStream(opusTgtSpm),
            FileInputStream(opusVocab),
            opusMeta
        )
    }

    @Test
    fun nllb_encode_matchesHuggingFace() {
        goldens("nllb-600m").encode.forEach {
            assertArrayEquals(it.text, it.ids, nllb.encode(it.text))
        }
    }

    @Test
    fun nllb_decode_matchesHuggingFace() {
        goldens("nllb-600m").decode.forEach {
            assertEquals(it.text, nllb.decode(it.ids))
        }
    }

    @Test
    fun marian_encode_matchesHuggingFace() {
        goldens("opus-zh-vi").encode.forEach {
            assertArrayEquals(it.text, it.ids, marian.encode(it.text))
        }
    }

    @Test
    fun marian_decode_matchesHuggingFace() {
        goldens("opus-zh-vi").decode.forEach {
            assertEquals(it.text, marian.decode(it.ids))
        }
    }
}
