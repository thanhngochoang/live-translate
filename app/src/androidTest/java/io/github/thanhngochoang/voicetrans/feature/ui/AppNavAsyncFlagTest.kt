package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

/**
 * The real view model saves `backgroundSetupDone` asynchronously. These tests make the fake flip it 300 ms after
 * Continue, which is what exposed a navigation loop (Live bounced back to BackgroundSetup).
 */
class AppNavAsyncFlagTest {
    @get:Rule val rule = createComposeRule()
    private fun s(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun firstRunContinue_withDelayedFlag_endsOnLive_andStays() {
        val state = mutableStateOf(AppUiState(ready = true, packs = readyPacks(), backgroundSetupDone = false, background = noBackground, wifiOnly = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val scope = rememberCoroutineScope()
            val actions = AppActions(
                onDownload = {},
                onBackgroundContinue = { scope.launch { delay(300); state.value = state.value.copy(backgroundSetupDone = true) } },
                onRequestNotifications = {}, onRequestBattery = {}, onRequestOverlay = {},
            )
            VoiceTransTheme(ThemeMode.DARK) { AppNav(state.value, actions) }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText(s(R.string.background_title)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.action_continue)).performClick()
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithText(s(R.string.start_listening)).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText(s(R.string.start_listening)).assertIsDisplayed()
    }

    @Test fun modelSetupToBackground_whenPacksBecomeReadyLater_thenContinueDelayed_endsOnLive() {
        val state = mutableStateOf(AppUiState(ready = false, packs = missingVi(), backgroundSetupDone = false, background = noBackground, wifiOnly = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val scope = rememberCoroutineScope()
            val actions = AppActions(
                onDownload = { scope.launch { delay(200); state.value = state.value.copy(ready = true, packs = readyPacks()) } },
                onBackgroundContinue = { scope.launch { delay(300); state.value = state.value.copy(backgroundSetupDone = true) } },
                onRequestNotifications = {}, onRequestBattery = {}, onRequestOverlay = {},
            )
            VoiceTransTheme(ThemeMode.DARK) { AppNav(state.value, actions) }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText(s(R.string.download_packs)).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText(s(R.string.background_title)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.action_continue)).performClick()
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithText(s(R.string.start_listening)).assertIsDisplayed()
    }

    @Test fun reopenedFromLive_continuePopsBackToLive() {
        val state = mutableStateOf(AppUiState(ready = true, packs = readyPacks(), backgroundSetupDone = true, background = noBackground, wifiOnly = true))
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val actions = AppActions({}, {}, {}, {}, {})
            val nav = rememberNavController()
            val screens = AppScreens(live = { n -> Column { Text("LIVE_HOME"); Button(onClick = { n.navigate(Routes.BACKGROUND_SETUP) }) { Text("open_bg") } } })
            VoiceTransTheme(ThemeMode.DARK) { AppNav(state.value, actions, screens, nav) }
        }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("open_bg").performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText(s(R.string.background_title)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.action_continue)).performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("LIVE_HOME").assertIsDisplayed()
    }
}
