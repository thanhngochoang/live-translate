package io.github.thanhngochoang.voicetrans.feature.ui

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Hosts a fixed screen (independent of which pack or permission state the app is in) and switches the per-app
 * language through [AppLanguage], recreating the activity each time.
 */
class AppLanguageTest {
    @get:Rule val rule = createEmptyComposeRule()

    @After fun reset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.SYSTEM.apply() }
        UiHost.reset()
    }

    private fun localized(tag: String, id: Int): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cfg = Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return ctx.createConfigurationContext(cfg).getString(id)
    }

    @Test fun switchToEnglish_updatesUi_thenVietnamese() {
        UiHost.content = { Text(stringResource(R.string.start_listening)) }
        val scenario = ActivityScenario.launch(UiHostActivity::class.java)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.EN.apply() }
        scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText("Start listening").assertExists()
        assertEquals(AppLanguage.EN, currentAppLanguage())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.VI.apply() }
        scenario.recreate()
        rule.waitForIdle()
        rule.onNodeWithText(localized("vi", R.string.start_listening)).assertExists()
        assertEquals(AppLanguage.VI, currentAppLanguage())
        scenario.close()
    }

    @Test fun englishAndVietnamese_startListening_differ() {
        assertEquals("Start listening", localized("en", R.string.start_listening))
        assertEquals("Bắt đầu nghe", localized("vi", R.string.start_listening))
    }
}
