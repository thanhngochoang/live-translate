package io.github.thanhngochoang.voicetrans.feature.overlay

import io.github.thanhngochoang.voicetrans.feature.asr.AudioClass
import io.github.thanhngochoang.voicetrans.feature.session.CaptureWarning
import io.github.thanhngochoang.voicetrans.feature.session.Utterance
import io.github.thanhngochoang.voicetrans.feature.session.UtteranceKind
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayLinesTest {
    private fun u(id: Long, src: String, tr: String?, failed: Boolean = false, target: String = "vi") =
        Utterance(id, UtteranceKind.SPEECH, src, SourceLang.EN, target, tr, id * 1000, id * 1000 + 900, failed)

    @Test fun takesNewestTranslatedOnly() {
        val list = listOf(u(1, "a", "A"), u(2, "b", "B"), u(3, "c", "C"), u(4, "d", null))
        val lines = overlayLines(list, maxLines = 2, showSource = false)
        assertEquals(listOf("B", "C"), lines.map { it.text })
        assertEquals(listOf(false, true), lines.map { it.isNewest })
    }

    @Test fun showSource_addsSourceAboveEachTranslation() {
        val lines = overlayLines(listOf(u(1, "a", "A"), u(2, "b", "B")), maxLines = 1, showSource = true)
        assertEquals(listOf("b" to true, "B" to false), lines.map { it.text to it.isSource })
        assertEquals(true, lines.last().isNewest)
    }

    @Test fun failedAndSameLanguage() {
        val list = listOf(u(1, "a", null, failed = true), u(2, "hello", "hello", target = "en"))
        val lines = overlayLines(list, maxLines = 3, showSource = true)
        assertEquals(listOf("hello"), lines.map { it.text })
        assertEquals(false, lines.single().isSource)
    }

    @Test fun musicRowsAreIgnored() {
        val music = Utterance(1, UtteranceKind.MUSIC, "", SourceLang.EN, "vi", null, 0, 5000)
        assertEquals(emptyList<OverlayLine>(), overlayLines(listOf(music), 2, false))
    }

    @Test fun notice_rules() {
        assertEquals(OverlayNotice.MUSIC, overlayNotice(AudioClass.MUSIC, null, 3000))
        assertNull(overlayNotice(AudioClass.MUSIC, null, 500))
        assertEquals(OverlayNotice.CAPTURE_BLOCKED, overlayNotice(AudioClass.SPEECH, CaptureWarning.BLOCKED_BY_APP, 0))
        assertEquals(OverlayNotice.MUSIC, overlayNotice(AudioClass.SINGING, null, 2500))
        assertEquals(OverlayNotice.CAPTURE_BLOCKED, overlayNotice(AudioClass.MUSIC, CaptureWarning.BLOCKED_BY_APP, 9000))
        assertNull(overlayNotice(AudioClass.SPEECH, null, 9000))
    }

    @Test fun overlayLines_neverContainsHanziInTranslation() {
        // Untranslated Hanzi in translation is purged/transliterated, never displayed as Chinese
        val list = listOf(
            u(1, "zhao da ren", "Triệu 大人"),
            u(2, "huang shang", "hoàng thượng (皇上)"),
            u(3, "unknown", "这是什么"), // pure untranslated Hanzi should be purged and omitted
        )
        val lines = overlayLines(list, maxLines = 5, showSource = false)
        assertEquals(listOf("Triệu đại nhân", "hoàng thượng"), lines.map { it.text })
        org.junit.Assert.assertTrue(lines.none { io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter.hasCjk(it.text) })
    }

    @Test fun overlayLines_neverShowsHanziInSource() {
        val list = listOf(
            Utterance(1, UtteranceKind.SPEECH, "大人", SourceLang.ZH, "vi", "đại nhân", 0, 900),
            Utterance(2, UtteranceKind.SPEECH, "hello", SourceLang.EN, "vi", "xin chào", 1000, 1900),
        )
        val lines = overlayLines(list, maxLines = 5, showSource = true)
        // Chinese source "大人" must NOT be displayed above "đại nhân"; English source "hello" is displayed
        assertEquals(listOf("đại nhân", "hello", "xin chào"), lines.map { it.text })
        org.junit.Assert.assertTrue(lines.none { io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter.hasCjk(it.text) })
    }

    @Test fun liveLine_draftReplacesChinesePartial() {
        val line = overlayLiveLine(partialDraft = "chào buổi sáng", partialText = "早上好", inFlight = false, translatingText = "Đang dịch…")
        assertEquals("chào buổi sáng", line)
    }

    @Test fun liveLine_noDraft_englishPartialShown() {
        val line = overlayLiveLine(partialDraft = null, partialText = "good morning", inFlight = false, translatingText = "Đang dịch…")
        assertEquals("good morning", line)
    }

    @Test fun liveLine_noDraft_chinesePartialShowsTranslating() {
        val line = overlayLiveLine(partialDraft = null, partialText = "早上好", inFlight = false, translatingText = "Đang dịch…")
        assertEquals("Đang dịch…", line)
    }

    @Test fun liveLine_inFlightOnly_showsTranslating() {
        val line = overlayLiveLine(partialDraft = null, partialText = null, inFlight = true, translatingText = "Đang dịch…")
        assertEquals("Đang dịch…", line)
    }
}
