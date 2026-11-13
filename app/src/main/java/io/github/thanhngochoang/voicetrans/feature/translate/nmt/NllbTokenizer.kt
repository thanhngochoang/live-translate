package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import java.io.InputStream
import java.text.Normalizer

class NllbTokenizer(spmModel: InputStream, val meta: NmtMeta) : SubwordTokenizer {

    private val sp = SentencePieceModel.load(spmModel)
    private val spaceTokenId = sp.pieceToIndex["\u2581"]?.let { it + 1 } ?: 248059

    override fun encode(text: String): IntArray {
        val prefix = meta.srcLangToken ?: 256047
        val eos = meta.eos

        if (text.isEmpty()) {
            return intArrayOf(prefix, eos)
        }
        if (text.isBlank()) {
            return intArrayOf(prefix, spaceTokenId, eos)
        }

        // NFKC normalization
        var norm = Normalizer.normalize(text, Normalizer.Form.NFKC)
        norm = norm.trim().replace(Regex("\\s+"), " ")
        if (!norm.startsWith(" ")) {
            norm = " $norm"
        }
        norm = norm.replace(' ', '\u2581')

        // Initial character tokens
        val symbols = ArrayList<String>(norm.length)
        for (i in norm.indices) {
            symbols.add(norm[i].toString())
        }

        // BPE merges
        while (symbols.size >= 2) {
            var bestPairIdx = -1
            var bestScore = Float.NEGATIVE_INFINITY
            var bestMerged: String? = null

            for (i in 0 until symbols.size - 1) {
                val pair = symbols[i] + symbols[i + 1]
                val idx = sp.pieceToIndex[pair]
                if (idx != null) {
                    val score = sp.scores[idx]
                    if (score > bestScore) {
                        bestScore = score
                        bestPairIdx = i
                        bestMerged = pair
                    }
                }
            }

            if (bestPairIdx == -1 || bestMerged == null) {
                break
            }

            symbols[bestPairIdx] = bestMerged
            symbols.removeAt(bestPairIdx + 1)
        }

        val result = IntArray(symbols.size + 2)
        result[0] = prefix
        for (i in symbols.indices) {
            val s = symbols[i]
            val idx = sp.pieceToIndex[s]
            val hfId = if (idx != null) {
                when (idx) {
                    0 -> 3 // <unk>
                    1 -> 0 // <s>
                    2 -> 2 // </s>
                    else -> idx + 1
                }
            } else {
                meta.unk
            }
            result[i + 1] = hfId
        }
        result[symbols.size + 1] = eos
        return result
    }

    override fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in 0..3 || id >= 256000) {
                continue
            }
            val spIdx = id - 1
            if (spIdx in sp.pieces.indices) {
                sb.append(sp.pieces[spIdx])
            }
        }
        return sb.toString().replace('\u2581', ' ').trim()
    }
}
