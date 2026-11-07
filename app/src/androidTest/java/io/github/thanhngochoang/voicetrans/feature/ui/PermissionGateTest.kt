package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import org.junit.Rule
import org.junit.Test

class PermissionGateTest {
    @get:Rule val rule = createComposeRule()
    private fun s(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test fun denied_showsOpenSettingsButton_andNoMicClaim() {
        rule.setContent { VoiceTransTheme(ThemeMode.DARK) { PermissionGate(isGranted = { false }) { Text("content") } } }
        rule.onNodeWithText(s(R.string.open_settings)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.permission_gate_message), substring = true).assertIsDisplayed()
        rule.onNodeWithText("content").assertDoesNotExist()
    }

    @Test fun granted_showsContent() {
        rule.setContent { VoiceTransTheme(ThemeMode.DARK) { PermissionGate(isGranted = { true }) { Text("content") } } }
        rule.onNodeWithText("content").assertIsDisplayed()
    }
}
