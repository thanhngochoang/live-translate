package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import org.junit.Rule
import org.junit.Test

class ModelSetupScreenTest {
    @get:Rule val rule = createComposeRule()
    private fun s(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(app: FakeApp, landscape: Boolean = false) = rule.setContent {
        VoiceTransTheme(ThemeMode.DARK) {
            val state by app.state.collectAsState()
            val content = @androidx.compose.runtime.Composable { AppNav(state, app.actions) }
            if (landscape) DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 360.dp))) { content() } else content()
        }
    }

    @Test fun notReady_blocksLive() {
        show(FakeApp(AppUiState(ready = false, packs = missingVi(), backgroundSetupDone = true, background = noBackground, wifiOnly = true)))
        rule.onNodeWithText(s(R.string.download_packs)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.start_listening)).assertDoesNotExist()
    }

    @Test fun ready_goesToBackgroundSetupFirstTime() {
        show(FakeApp(AppUiState(ready = true, packs = readyPacks(), backgroundSetupDone = false, background = noBackground, wifiOnly = true)))
        rule.onNodeWithText(s(R.string.background_title)).assertIsDisplayed()
    }

    @Test fun ready_andBackgroundDone_showsLive() {
        show(FakeApp(AppUiState(ready = true, packs = readyPacks(), backgroundSetupDone = true, background = noBackground, wifiOnly = true)))
        rule.onNodeWithText(s(R.string.start_listening)).assertIsDisplayed()
    }

    @Test fun downloadButton_invokesAction() {
        val app = FakeApp(AppUiState(ready = false, packs = missingVi(), backgroundSetupDone = false, background = noBackground, wifiOnly = true))
        show(app)
        rule.onNodeWithText(s(R.string.download_packs)).performClick()
        org.junit.Assert.assertEquals(1, app.downloads)
    }

    @Test fun landscapeCompactHeight_primaryButtonReachable() {
        show(FakeApp(AppUiState(ready = false, packs = missingVi(), backgroundSetupDone = false, background = noBackground, wifiOnly = true)), landscape = true)
        rule.onNodeWithText(s(R.string.download_packs)).performScrollTo().assertIsDisplayed()
    }
}
