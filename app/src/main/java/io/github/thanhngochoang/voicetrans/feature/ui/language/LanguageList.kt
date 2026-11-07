package io.github.thanhngochoang.voicetrans.feature.ui.language

import java.text.Collator
import java.util.Locale

data class LanguageItem(val code: String, val name: String, val pinned: Boolean)

private val PINNED = listOf("vi", "en", "zh", "ja", "ko", "th", "id")

/** Pinned languages first (vi, en, zh, ja, ko, th, id), then the rest sorted by name for [appLocale]. Names follow [appLocale]. */
fun orderedTargetLanguages(all: List<String>, appLocale: Locale): List<LanguageItem> {
    fun name(code: String) = Locale.forLanguageTag(code).getDisplayName(appLocale)
    val pinned = PINNED.filter { it in all }.map { LanguageItem(it, name(it), true) }
    val collator = Collator.getInstance(appLocale)
    val rest = all.filter { it !in PINNED }.map { LanguageItem(it, name(it), false) }.sortedWith { a, b -> collator.compare(a.name, b.name) }
    return pinned + rest
}
