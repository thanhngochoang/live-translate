package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.ui.settings.OverlayControls
import io.github.thanhngochoang.voicetrans.feature.ui.settings.PackItem
import io.github.thanhngochoang.voicetrans.feature.ui.settings.SettingsActions
import io.github.thanhngochoang.voicetrans.feature.ui.settings.SettingsScreen
import io.github.thanhngochoang.voicetrans.feature.ui.settings.SettingsUiState
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VtPalette
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeOverlayControls : OverlayControls {
    val setup = mutableListOf<Boolean>()
    val dragging = mutableListOf<Boolean>()
    override fun setSetupMode(active: Boolean) { setup += active }
    override fun setOpacityDragging(active: Boolean) { dragging += active }
}

class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = ctx.getString(id)

    private val controls = FakeOverlayControls()
    private val state = mutableStateOf(initial())
    private val deleted = mutableListOf<String>()

    private fun initial() = SettingsUiState(
        appLanguage = AppLanguage.SYSTEM, targetName = "Tiếng Việt", themeMode = ThemeMode.DARK, subtitleSizeSp = 20,
        overlay = OverlaySettings(), overlayPermission = true, background = goodBackground, autoStopMinutes = 30, wifiOnly = true,
        packs = listOf(
            PackItem("en", "English", ModelState.Ready, inUse = true),
            PackItem("zh", "Chinese", ModelState.Ready, inUse = true),
            PackItem("vi", "Vietnamese", ModelState.Ready, inUse = true),
            PackItem("ja", "Japanese", ModelState.Ready, inUse = false),
        ),
        versionName = "0.1.0", modelVersions = listOf("asr-zipformer-bilingual-zh-en-2023-02-20", "silero-vad"),
    )

    private val actions = SettingsActions(
        onAppLanguage = { state.value = state.value.copy(appLanguage = it) },
        onTheme = { state.value = state.value.copy(themeMode = it) },
        onSubtitleSize = { state.value = state.value.copy(subtitleSizeSp = it) },
        onOverlay = { t -> state.value = state.value.copy(overlay = t(state.value.overlay)) },
        onRequestOverlayPermission = {}, onRequestNotifications = {}, onRequestBattery = {},
        onAutoStop = { state.value = state.value.copy(autoStopMinutes = it) },
        onWifiOnly = { state.value = state.value.copy(wifiOnly = it) },
        onDeletePack = { deleted += it },
        onOpenTargetPicker = {}, onReopenBackgroundSetup = {}, overlayControls = controls,
    )

    private fun show() = rule.setContent {
        VoiceTransTheme(state.value.themeMode) { SettingsScreen(state.value, actions) }
    }

    private fun openOverlay() { rule.onNodeWithText(s(R.string.settings_overlay_title)).performScrollTo().performClick() }

    @Test fun about_explainsUncapturableSources() {
        show()
        rule.onNodeWithTag("settings_about_row").performScrollTo().performClick()
        rule.onNodeWithText(s(R.string.why_no_subtitles)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.about_row_drm), substring = true).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.about_row_brave), substring = true).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.about_row_calls), substring = true).assertIsDisplayed()
        rule.onNodeWithText("0.1.0", substring = true).assertIsDisplayed()
    }

    @Test fun deletePack_requiresConfirm() {
        show()
        rule.onNodeWithContentDescription(ctx.getString(R.string.delete_pack_desc, "Japanese")).performScrollTo().performClick()
        rule.onNodeWithTag("delete_dialog").assertIsDisplayed()
        assertTrue(deleted.isEmpty())
        rule.onNodeWithText(s(R.string.delete)).performClick()
        assertEquals(listOf("ja"), deleted)
        rule.onNodeWithTag("delete_dialog").assertDoesNotExist()
    }

    @Test fun deletePack_cancelDoesNotDelete() {
        show()
        rule.onNodeWithContentDescription(ctx.getString(R.string.delete_pack_desc, "Japanese")).performScrollTo().performClick()
        rule.onNodeWithText(s(R.string.cancel)).performClick()
        assertTrue(deleted.isEmpty())
    }

    @Test fun cannotDeleteInUsePacks() {
        show()
        rule.onAllNodesWithText(s(R.string.in_use)).assertCountEquals(3)
        for (name in listOf("English", "Chinese", "Vietnamese")) {
            rule.onNode(hasContentDescription(ctx.getString(R.string.delete_pack_desc, name))).assertDoesNotExist()
        }
    }

    @Test fun overlaySection_showsAllControlsWithDefaults() {
        show(); openOverlay()
        rule.onNodeWithText("30%", substring = true).assertIsDisplayed()
        for (tag in listOf("overlay_opacity", "overlay_text_size", "overlay_lines", "overlay_show_source", "overlay_position", "overlay_auto_hide", "overlay_lock")) {
            rule.onNodeWithTag(tag).performScrollTo().assertExists()
        }
        rule.onNodeWithTag("position_BOTTOM").assertIsSelected()
        rule.onNodeWithTag("auto_hide_8").assertIsSelected()
        rule.onNodeWithTag("lines_2").assertIsSelected()
    }

    @Test fun overlayScreen_entersSetupMode_andLeaves() {
        show(); openOverlay()
        rule.waitForIdle()
        assertEquals(listOf(true), controls.setup)
        rule.onNodeWithContentDescription(s(R.string.nav_back)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(true, false), controls.setup)
    }

    @Test fun overlayOpacity_slider_reportsDragging_andChangesValue() {
        show(); openOverlay()
        rule.onNodeWithTag("overlay_opacity").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        rule.waitForIdle()
        assertEquals(true, controls.dragging.firstOrNull())
        assertEquals(false, controls.dragging.lastOrNull())
        assertEquals(0, state.value.overlay.bgOpacityPercent)
    }

    @Test fun overlayNoPermission_showsInAppPreview_andNoSetupMode() {
        state.value = state.value.copy(overlayPermission = false)
        show(); openOverlay()
        rule.onNodeWithTag("overlay_preview").assertIsDisplayed()
        assertTrue(controls.setup.isEmpty())
    }

    @Test fun overlayControlsDisabled_withoutPermission() {
        state.value = state.value.copy(overlayPermission = false)
        show(); openOverlay()
        rule.onNodeWithTag("overlay_opacity").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(s(R.string.action_grant)).assertIsDisplayed()
    }

    @Test fun overlayControlsEnabled_withPermission() {
        show(); openOverlay()
        rule.onNodeWithTag("overlay_opacity").performScrollTo().assertIsEnabled()
    }

    @Test fun autoStop_optionsAndDefault() {
        show()
        rule.onNodeWithTag("autostop_30").performScrollTo().assertIsSelected()
        for (m in listOf(0, 10, 60)) rule.onNodeWithTag("autostop_$m").performScrollTo().assertExists()
        rule.onNodeWithTag("autostop_60").performClick()
        assertEquals(60, state.value.autoStopMinutes)
    }

    @Test fun themeSwitch_appliesImmediately() {
        show()
        rule.onNodeWithTag("theme_LIGHT").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(ThemeMode.LIGHT, state.value.themeMode)
        val bitmap = rule.onNodeWithTag("settings_root").captureToImage().asAndroidBitmap()
        assertEquals(VtPalette.Light.background.toArgb(), bitmap.getPixel(2, 2))
    }
}

class SettingsActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun cleanup() = UiHost.reset()

    @Test fun deleteDialog_survivesRotation() {
        val state = SettingsUiState(
            AppLanguage.SYSTEM, "Tiếng Việt", ThemeMode.DARK, 20, OverlaySettings(), true, goodBackground, 30, true,
            listOf(PackItem("ja", "Japanese", ModelState.Ready, inUse = false)), "0.1.0", emptyList(),
        )
        val actions = SettingsActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, FakeOverlayControls())
        UiHost.content = { VoiceTransTheme(ThemeMode.DARK) { SettingsScreen(state, actions) } }
        val scenario = ActivityScenario.launch(UiHostActivity::class.java)
        rule.onNodeWithContentDescription(ctx.getString(R.string.delete_pack_desc, "Japanese")).performScrollTo().performClick()
        rule.onNodeWithTag("delete_dialog").assertIsDisplayed()
        scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag("delete_dialog").assertIsDisplayed()
        scenario.close()
    }
}
