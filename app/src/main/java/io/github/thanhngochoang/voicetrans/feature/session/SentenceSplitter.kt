package io.github.thanhngochoang.voicetrans.feature.session

fun splitSentences(text: String): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()

    val sentences = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    val n = trimmed.length

    while (i < n) {
        val c = trimmed[i]
        current.append(c)

        var isBoundary = false
        if (c == '。' || c == '！' || c == '？') {
            isBoundary = true
        } else if (c == '.' || c == '!' || c == '?') {
            val prevChar = if (i > 0) trimmed[i - 1] else null
            val nextChar = if (i + 1 < n) trimmed[i + 1] else null
            if (prevChar != '.' && nextChar != null && nextChar.isWhitespace()) {
                isBoundary = true
            }
        }

        if (isBoundary) {
            val seg = current.toString().trim()
            if (seg.any { it.isLetterOrDigit() }) {
                sentences.add(seg)
            }
            current.clear()
            while (i + 1 < n && trimmed[i + 1].isWhitespace()) {
                i++
            }
        }
        i++
    }

    if (current.isNotEmpty()) {
        val seg = current.toString().trim()
        if (seg.any { it.isLetterOrDigit() }) {
            sentences.add(seg)
        }
    }

    return sentences
}
