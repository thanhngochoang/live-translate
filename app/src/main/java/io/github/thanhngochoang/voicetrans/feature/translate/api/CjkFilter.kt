package io.github.thanhngochoang.voicetrans.feature.translate.api

object CjkFilter {
    fun isCjk(ch: Char): Boolean =
        ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch in '\uF900'..'\uFAFF'

    fun hasCjk(text: String): Boolean = text.any { isCjk(it) }

    /**
     * Purges all Chinese characters and bracketed Chinese text from translated output.
     * Ensures the output is 100% clean Vietnamese Latin text.
     */
    fun purgeCjk(text: String): String {
        // Remove parenthesized CJK annotations, e.g. (大人) or （大人）
        val noParentheses = text
            .replace(Regex("""[（\(][\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF\s,.\-—–]+[）\)]"""), "")
        // Remove any remaining CJK characters
        val noCjk = noParentheses.replace(Regex("""[\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF]"""), "")
        // Remove Chinese-style quotation marks or brackets
        return noCjk.replace(Regex("""[《》「」【】]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
