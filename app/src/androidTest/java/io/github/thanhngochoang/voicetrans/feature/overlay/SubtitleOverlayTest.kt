package io.github.thanhngochoang.voicetrans.feature.overlay

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        controlsVisible: Boolean = false,
        onControlsHidden: () -> Unit = {},
        livePartial: String? = null,
    ) = rule.setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 600.dp))) {
            SubtitleOverlay(lines, notice, settings, mode, onClose, onExit, controlsVisible, onControlsHidden, livePartial = livePartial)
        }
    }

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

    @Test fun draft_replacesPartialLine() {
        val live = overlayLiveLine(partialDraft = "chào buổi sáng", partialText = "早上好", inFlight = false, translatingText = s(R.string.overlay_translating))
        show(livePartial = live)
        rule.onNodeWithTag("live_partial").assertIsDisplayed()
        rule.onNodeWithText("chào buổi sáng").assertIsDisplayed()
        rule.onAllNodesWithText("早上好").assertCountEquals(0)
        rule.onAllNodesWithText(s(R.string.overlay_translating)).assertCountEquals(0)
    }

    @Test fun noDraft_englishPartialShown() {
        val live = overlayLiveLine(partialDraft = null, partialText = "good morning", inFlight = false, translatingText = s(R.string.overlay_translating))
        show(livePartial = live)
        rule.onNodeWithTag("live_partial").assertIsDisplayed()
        rule.onNodeWithText("good morning").assertIsDisplayed()
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

    // Gestures (tap / drag / hold) live in OverlayTouchFrame: see OverlayGestureDetectorTest and OverlayControllerTest.

    @Test fun controlRow_showsCloseAndExit_thenAsksToHideAfter5s() {
        var hidden = 0
        rule.mainClock.autoAdvance = false
        show(controlsVisible = true, onControlsHidden = { hidden++ })
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText(s(R.string.overlay_close)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.overlay_exit_app)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.notif_action_stop)).assertDoesNotExist()
        assertEquals(0, hidden)
        rule.mainClock.advanceTimeBy(5_200)
        assertEquals(1, hidden)
    }

    @Test fun controlRow_hiddenByDefault() {
        show()
        rule.onNodeWithText(s(R.string.overlay_close)).assertDoesNotExist()
        rule.onNodeWithText(s(R.string.overlay_exit_app)).assertDoesNotExist()
    }

    @Test fun closeAndExit_invokeCallbacks_andHideTheRow() {
        var closed = 0; var exited = 0; var hidden = 0
        show(controlsVisible = true, onClose = { closed++ }, onExit = { exited++ }, onControlsHidden = { hidden++ })
        rule.onNodeWithText(s(R.string.overlay_close)).performClick()
        rule.onNodeWithText(s(R.string.overlay_exit_app)).performClick()
        assertEquals(1, closed)
        assertEquals(1, exited)
        assertEquals(2, hidden)
    }
}
