package io.github.thanhngochoang.voicetrans.feature.translate.nmt

interface SubwordTokenizer {
    /**
     * Encodes raw text into token IDs.
     * Returns the full encoder input, including any required language prefix (NLLB)
     * and trailing eos token.
     */
    fun encode(text: String): IntArray

    /**
     * Decodes an array of token IDs back into text, skipping special/control tokens.
     */
    fun decode(ids: IntArray): String
}
