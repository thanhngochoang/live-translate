package io.github.thanhngochoang.voicetrans.feature.ui.language

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageListTest {
    private val all = listOf("af", "ar", "ja", "de", "zh", "vi", "en", "th", "id", "ko", "fr", "es", "pt")
    private val pinned = listOf("vi", "en", "zh", "ja", "ko", "th", "id")

    @Test fun pinnedFirst_thenAlphabetical() {
        val items = orderedTargetLanguages(all, Locale.ENGLISH)
        assertEquals(pinned, items.take(7).map { it.code })
        assertEquals(pinned.toSet(), items.filter { it.pinned }.map { it.code }.toSet())
        val rest = items.drop(7)
        assertTrue(rest.none { it.pinned })
        assertEquals(rest.map { it.name }.sortedWith(java.text.Collator.getInstance(Locale.ENGLISH)), rest.map { it.name })
        assertEquals(all.size, items.size)
    }

    @Test fun namesFollowAppLocale() {
        assertEquals("Tiếng Nhật", orderedTargetLanguages(all, Locale("vi")).first { it.code == "ja" }.name)
        assertEquals("Japanese", orderedTargetLanguages(all, Locale.ENGLISH).first { it.code == "ja" }.name)
    }

    @Test fun missingPinnedCodesAreSkipped() {
        assertEquals(listOf("vi", "en", "de"), orderedTargetLanguages(listOf("de", "en", "vi"), Locale.ENGLISH).map { it.code })
    }
}
