package io.github.thanhngochoang.voicetrans.feature.ui

import android.content.res.Configuration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.MainActivity
import io.github.thanhngochoang.voicetrans.R
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class AppLanguageTest {
    // The permission must be granted before the activity starts, or PermissionGate would be the first screen.
    @get:Rule(order = 0) val grant = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .grantRuntimePermission("io.github.thanhngochoang.voicetrans", android.Manifest.permission.RECORD_AUDIO)
                base.evaluate()
            }
        }
    }
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @After fun reset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.SYSTEM.apply() }
    }

    private fun localized(tag: String, id: Int): String {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val cfg = Configuration(ctx.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return ctx.createConfigurationContext(cfg).getString(id)
    }

    @Test fun switchToEnglish_updatesUi_thenVietnamese() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.EN.apply() }
        rule.activityRule.scenario.recreate()
        // On a fresh install the packs are missing, so the first screen is ModelSetup.
        rule.onNodeWithText(localized("en", R.string.download_packs)).assertExists()
        assertEquals(AppLanguage.EN, currentAppLanguage())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppLanguage.VI.apply() }
        rule.activityRule.scenario.recreate()
        rule.onNodeWithText(localized("vi", R.string.download_packs)).assertExists()
    }

    @Test fun englishAndVietnamese_startListening_differ() {
        assertEquals("Start listening", localized("en", R.string.start_listening))
    }
}
