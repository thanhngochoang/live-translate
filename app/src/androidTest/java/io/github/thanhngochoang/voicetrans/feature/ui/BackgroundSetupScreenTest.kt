package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BackgroundSetupScreenTest {
    @get:Rule val rule = createComposeRule()
    private fun s(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun nav(app: FakeApp, landscape: Boolean = false) = rule.setContent {
        VoiceTransTheme(ThemeMode.DARK) {
            val state by app.state.collectAsState()
            val content = @androidx.compose.runtime.Composable { AppNav(state, app.actions) }
            if (landscape) DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 360.dp))) { content() } else content()
        }
    }

    private fun firstRun(status: BackgroundStatus = noBackground) =
        FakeApp(AppUiState(ready = true, packs = readyPacks(), backgroundSetupDone = false, background = status, wifiOnly = true))

    @Test fun continueAlwaysEnabled_andLeadsToLive() {
        val app = firstRun()
        nav(app)
        rule.onNodeWithText(s(R.string.action_continue)).assertIsEnabled().performClick()
        assertEquals(true, app.state.value.backgroundSetupDone)
        rule.onNodeWithText(s(R.string.start_listening)).assertIsDisplayed()
    }

    @Test fun showsStatusTextNotJustColor() {
        nav(firstRun(noBackground.copy(batteryUnrestricted = false)))
        rule.onNodeWithText(s(R.string.battery_optimized)).assertIsDisplayed()
    }

    @Test fun batteryUnrestricted_showsGoodStatusText() {
        nav(firstRun(noBackground.copy(batteryUnrestricted = true)))
        rule.onNodeWithText(s(R.string.battery_unrestricted)).assertIsDisplayed()
    }

    @Test fun overlayRowIsOptional() {
        val app = firstRun()
        nav(app)
        rule.onNodeWithText(s(R.string.optional), substring = true).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.action_continue)).assertIsEnabled().performClick()
        assertEquals(true, app.state.value.backgroundSetupDone)
    }

    @Test fun landscapeCompactHeight_continueReachable() {
        nav(firstRun(), landscape = true)
        rule.onNodeWithText(s(R.string.action_continue)).performScrollTo().assertIsDisplayed()
    }
}
