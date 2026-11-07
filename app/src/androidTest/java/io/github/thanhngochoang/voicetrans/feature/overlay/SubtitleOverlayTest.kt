package io.github.thanhngochoang.voicetrans.feature.overlay

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SubtitleOverlayTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = ctx.getString(id)

    private val lines = listOf(OverlayLine(1, "Xin chào", false, false), OverlayLine(2, "Tạm biệt", false, true))

    private fun show(
        lines: List<OverlayLine> = this.lines,
        notice: OverlayNotice? = null,
        settings: OverlaySettings = OverlaySettings(),
        mode: OverlayMode = OverlayMode.TRANSLATING,
        onClose: () -> Unit = {},
        onExit: () -> Unit = {},
        onDrag: (Float) -> Unit = {},
        onDragEnd: () -> Unit = {},
    ) = rule.setContent { SubtitleOverlay(lines, notice, settings, mode, onClose, onExit, onDrag, onDragEnd) }

    @Test fun music_showsNoteLine_keepingPreviousTranslations() {
        show(notice = OverlayNotice.MUSIC)
        rule.onNodeWithText(s(R.string.overlay_music)).assertIsDisplayed()
        rule.onNodeWithText("Xin chào").assertIsDisplayed()
        rule.onNodeWithTag("notice").assertIsDisplayed()
    }

    @Test fun captureBlocked_showsWarningLine() {
        show(notice = OverlayNotice.CAPTURE_BLOCKED)
        rule.onNodeWithText(s(R.string.overlay_capture_blocked)).assertIsDisplayed()
    }

    @Test fun translatingMode_backgroundFollowsSetting() {
        show(settings = OverlaySettings(bgOpacityPercent = 30))
        rule.onNodeWithTag("overlay_box").assert(SemanticsMatcher.expectValue(OverlayBackgroundAlphaKey, 0.3f))
        rule.onNodeWithTag("outline").assertDoesNotExist()
        rule.onNodeWithTag("drag_handle").assertDoesNotExist()
    }

    @Test fun translatingMode_zeroOpacity_textStillThere() {
        show(settings = OverlaySettings(bgOpacityPercent = 0))
        rule.onNodeWithTag("overlay_box").assert(SemanticsMatcher.expectValue(OverlayBackgroundAlphaKey, 0f))
        rule.onNodeWithText("Tạm biệt").assertIsDisplayed()
    }

    @Test fun setupMode_solidBackgroundOutlineHandle_andSampleText() {
        show(lines = emptyList(), settings = OverlaySettings(bgOpacityPercent = 0), mode = OverlayMode.SETUP)
        rule.onNodeWithTag("overlay_box").assert(SemanticsMatcher.expectValue(OverlayBackgroundAlphaKey, 0.85f))
        rule.onNodeWithTag("outline").assertExists()
        rule.onNodeWithTag("drag_handle").assertExists()
        rule.onNodeWithText(s(R.string.overlay_sample_1)).assertIsDisplayed()
    }

    @Test fun textStyle_hasShadow() {
        show()
        val result = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Tạm biệt").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(result)
        assertNotNull(result.first().layoutInput.style.shadow)
    }

    @Test fun longPress_showsCloseAndExit_thenHidesAfter5s() {
        show()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("overlay_box").performTouchInput { longClick(durationMillis = 600) }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText(s(R.string.overlay_close)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.overlay_exit_app)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.notif_action_stop)).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(5_200)
        rule.onNodeWithText(s(R.string.overlay_close)).assertDoesNotExist()
        rule.onNodeWithText(s(R.string.overlay_exit_app)).assertDoesNotExist()
    }

    @Test fun tap_doesNothing() {
        var calls = 0
        show(onClose = { calls++ }, onExit = { calls++ }, onDrag = { calls++ }, onDragEnd = { calls++ })
        rule.onNodeWithTag("overlay_box").performClick()
        rule.onNodeWithText(s(R.string.overlay_close)).assertDoesNotExist()
        assertEquals(0, calls)
    }

    @Test fun closeAndExit_invokeCallbacks() {
        var closed = 0; var exited = 0
        show(onClose = { closed++ }, onExit = { exited++ })
        rule.onNodeWithTag("overlay_box").performTouchInput { longClick(durationMillis = 600) }
        rule.onNodeWithText(s(R.string.overlay_close)).performClick()
        assertEquals(1, closed)
        rule.onNodeWithTag("overlay_box").performTouchInput { longClick(durationMillis = 600) }
        rule.onNodeWithText(s(R.string.overlay_exit_app)).performClick()
        assertEquals(1, exited)
    }

    @Test fun drag_movesWithoutControlRow() {
        val dys = mutableListOf<Float>(); var ended = 0
        show(onDrag = { dys += it }, onDragEnd = { ended++ })
        rule.onNodeWithTag("overlay_box").performTouchInput { down(center); moveBy(Offset(0f, -300f)); up() }
        assertTrue("dys=$dys", dys.isNotEmpty() && dys.sum() < 0f)
        assertEquals(1, ended)
        rule.onNodeWithText(s(R.string.overlay_close)).assertDoesNotExist()
    }
}
