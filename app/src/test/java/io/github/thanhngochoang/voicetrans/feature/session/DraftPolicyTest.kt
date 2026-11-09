package io.github.thanhngochoang.voicetrans.feature.session

import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftPolicyTest {

    @Test
    fun en_needsThreeWords() {
        assertFalse(shouldDraft("one two", null, SourceLang.EN))
        assertTrue(shouldDraft("one two three", null, SourceLang.EN))
    }

    @Test
    fun en_needsTwoNewWords() {
        val last = "one two three"
        assertFalse(shouldDraft("one two three four", last, SourceLang.EN))
        assertTrue(shouldDraft("one two three four five", last, SourceLang.EN))
    }

    @Test
    fun zh_needsFourChars() {
        assertFalse(shouldDraft("一二三", null, SourceLang.ZH))
        assertTrue(shouldDraft("一二三四", null, SourceLang.ZH))
    }

    @Test
    fun zh_needsThreeNewChars() {
        val last = "一二三四"
        assertFalse(shouldDraft("一二三四五", last, SourceLang.ZH))
        assertFalse(shouldDraft("一二三四五六", last, SourceLang.ZH))
        assertTrue(shouldDraft("一二三四五六七", last, SourceLang.ZH))
    }

    @Test
    fun firstDraft_noLastText() {
        assertFalse(shouldDraft("hi", null, SourceLang.EN))
        assertTrue(shouldDraft("hi there friend", null, SourceLang.EN))
        assertFalse(shouldDraft("你好", null, SourceLang.ZH))
        assertTrue(shouldDraft("你好世界呀", null, SourceLang.ZH))
    }
}
