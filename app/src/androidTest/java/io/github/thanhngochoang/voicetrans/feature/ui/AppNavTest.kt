package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import org.junit.Rule
import org.junit.Test

class AppNavTest {
    @get:Rule val rule = createComposeRule()
    private fun s(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(app: FakeApp) = rule.setContent {
        VoiceTransTheme(ThemeMode.DARK) {
            val state by app.state.collectAsState()
            AppNav(state, app.actions)
        }
    }

    @Test fun loading_thenLive() {
        val app = FakeApp(AppUiState(engineState = EngineState.Loading, backgroundSetupDone = true, background = noBackground))
        show(app)
        rule.onNodeWithText(s(R.string.models_loading)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.start_listening)).assertDoesNotExist()

        app.state.value = app.state.value.copy(engineState = EngineState.Ready)
        rule.waitForIdle()
        rule.onNodeWithText(s(R.string.start_listening)).assertIsDisplayed()
    }

    @Test fun loading_thenBackgroundSetupOnFirstRun() {
        val app = FakeApp(AppUiState(engineState = EngineState.Loading, backgroundSetupDone = false, background = noBackground))
        show(app)
        rule.onNodeWithText(s(R.string.models_loading)).assertIsDisplayed()

        app.state.value = app.state.value.copy(engineState = EngineState.Ready)
        rule.waitForIdle()
        rule.onNodeWithText(s(R.string.background_title)).assertIsDisplayed()
    }

    @Test fun failed_showsError() {
        val app = FakeApp(AppUiState(engineState = EngineState.Failed("Disk error"), backgroundSetupDone = true, background = noBackground))
        show(app)
        rule.onNodeWithText(s(R.string.models_failed)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.start_listening)).assertDoesNotExist()
    }

    @Test fun ready_goesToBackgroundSetupFirstTime() {
        show(FakeApp(AppUiState(engineState = EngineState.Ready, backgroundSetupDone = false, background = noBackground)))
        rule.onNodeWithText(s(R.string.background_title)).assertIsDisplayed()
    }

    @Test fun ready_andBackgroundDone_showsLive() {
        show(FakeApp(AppUiState(engineState = EngineState.Ready, backgroundSetupDone = true, background = noBackground)))
        rule.onNodeWithText(s(R.string.start_listening)).assertIsDisplayed()
    }
}
