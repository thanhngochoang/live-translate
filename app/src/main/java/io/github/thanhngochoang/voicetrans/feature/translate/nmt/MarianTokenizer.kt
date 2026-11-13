package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import java.io.InputStream
import java.text.Normalizer

class MarianTokenizer(
    sourceSpm: InputStream,
    targetSpm: InputStream,
    vocabJson: InputStream,
    val meta: NmtMeta
) : SubwordTokenizer {

    private val srcSp = SentencePieceModel.load(sourceSpm)
    private val vocabMap: Map<String, Int>
    private val idToPieceMap: Map<Int, String>

    init {
        val (vMap, rMap) = parseVocab(vocabJson)
        vocabMap = vMap
        idToPieceMap = rMap
        targetSpm.close()
    }

    override fun encode(text: String): IntArray {
        val eos = meta.eos
        if (text.isEmpty() || text.isBlank()) {
            return intArrayOf(eos)
        }

        // NFKC normalization
        var norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
        norm = norm.trim().replace(Regex("\\s+"), " ")
        if (!norm.startsWith(" ")) {
            norm = " $norm"
        }
        norm = norm.replace(' ', '\u2581')

        val n = norm.length
        val dp = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestPrev = IntArray(n + 1) { -1 }
        val bestPiece = arrayOfNulls<String>(n + 1)
        dp[0] = 0.0f

        for (i in 0 until n) {
            if (dp[i] == Float.NEGATIVE_INFINITY) continue
            val maxLen = minOf(n, i + 32)
            for (end in (i + 1)..maxLen) {
                val sub = norm.substring(i, end)
                val idx = srcSp.pieceToIndex[sub]
                if (idx != null) {
                    val score = dp[i] + srcSp.scores[idx]
                    if (score > dp[end]) {
                        dp[end] = score
                        bestPrev[end] = i
                        bestPiece[end] = sub
                    }
                }
            }

            // Fallback for single character not in piece vocabulary
            if (dp[i + 1] == Float.NEGATIVE_INFINITY) {
                dp[i + 1] = dp[i] - 10000.0f
                bestPrev[i + 1] = i
                bestPiece[i + 1] = norm[i].toString()
            }
        }

        // Backtrack
        val pieces = ArrayList<String>()
        var curr = n
        while (curr > 0) {
            val prev = bestPrev[curr]
            val piece = bestPiece[curr] ?: norm.substring(prev, curr)
            pieces.add(piece)
            curr = prev
        }
        pieces.reverse()

        val unkId = vocabMap["<unk>"] ?: meta.unk
        val result = IntArray(pieces.size + 1)
        for (i in pieces.indices) {
            result[i] = vocabMap[pieces[i]] ?: unkId
        }
        result[pieces.size] = eos
        return result
    }

    override fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == meta.eos || id == meta.unk || id == meta.pad) {
                continue
            }
            val piece = idToPieceMap[id]
            if (piece != null) {
                sb.append(piece)
            }
        }
        return sb.toString().replace('\u2581', ' ').trim()
    }

    companion object {
        private fun unescapeJson(s: String): String {
            if (!s.contains('\\')) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val next = s[i + 1]) {
                        'u' -> {
                            if (i + 5 < s.length) {
                                val hex = s.substring(i + 2, i + 6)
                                val code = hex.toIntOrNull(16)
                                if (code != null) {
                                    sb.append(code.toChar())
                                    i += 6
                                    continue
                                }
                            }
                            sb.append(c)
                            i++
                        }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        '"' -> { sb.append('"'); i += 2 }
                        '\\' -> { sb.append('\\'); i += 2 }
                        else -> { sb.append(c); i++ }
                    }
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        fun parseVocab(stream: InputStream): Pair<Map<String, Int>, Map<Int, String>> {
            val text = stream.bufferedReader().use { it.readText() }
            val vMap = HashMap<String, Int>(65536)
            val rMap = HashMap<Int, String>(65536)
            var i = 0
            val n = text.length
            while (i < n) {
                val q1 = text.indexOf('"', i)
                if (q1 == -1) break
                var q2 = q1 + 1
                while (q2 < n) {
                    if (text[q2] == '"' && text[q2 - 1] != '\\') break
                    q2++
                }
                if (q2 >= n) break
                val rawKey = text.substring(q1 + 1, q2)
                val key = unescapeJson(rawKey)
                val colon = text.indexOf(':', q2)
                if (colon == -1) break
                var numStart = colon + 1
                while (numStart < n && text[numStart].isWhitespace()) numStart++
                var numEnd = numStart
                while (numEnd < n && (text[numEnd].isDigit() || text[numEnd] == '-')) numEnd++
                if (numEnd > numStart) {
                    val id = text.substring(numStart, numEnd).toInt()
                    vMap[key] = id
                    rMap[id] = key
                }
                i = numEnd
            }
            return Pair(vMap, rMap)
        }
    }
}
