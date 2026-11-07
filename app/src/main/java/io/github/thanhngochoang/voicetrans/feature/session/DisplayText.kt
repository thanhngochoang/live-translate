package io.github.thanhngochoang.voicetrans.feature.session

import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang

private val PRONOUN_I = Regex("\\bi\\b")

/**
 * Display-only text for the source line. The Zipformer model outputs ALL CAPS English, so an all-caps English line is
 * turned into sentence case (first letter of each sentence capitalised, standalone "I" kept upper case). Text that
 * already has lowercase letters, and Chinese, is returned unchanged. The raw text stays in `Utterance.source`.
 */
fun displayCase(text: String, lang: SourceLang): String {
    if (lang != SourceLang.EN) return text
    val letters = text.filter { it.isLetter() }
    if (letters.isEmpty() || letters.any { it.isLowerCase() }) return text
    val out = StringBuilder(text.length)
    var capitalizeNext = true
    for (ch in text.lowercase()) {
        if (ch.isLetter() && capitalizeNext) {
            out.append(ch.uppercaseChar())
            capitalizeNext = false
        } else {
            out.append(ch)
        }
        if (ch == '.' || ch == '!' || ch == '?') capitalizeNext = true
    }
    return PRONOUN_I.replace(out.toString(), "I")
}

private fun Char.isCjk() = this in '一'..'鿿'

/**
 * Finals that are noise rather than speech: audio span under 500 ms, a single CJK character, or a single English
 * token of at most two letters. They are dropped before translation.
 */
fun isJunkFinal(text: String, startMs: Long, endMs: Long): Boolean {
    val t = text.trim()
    if (endMs - startMs < 500) return true
    if (t.length == 1 && t[0].isCjk()) return true
    val tokens = t.split(Regex("\\s+"))
    return tokens.size == 1 && t.none { it.isCjk() } && t.count { it.isLetter() } <= 2
}
