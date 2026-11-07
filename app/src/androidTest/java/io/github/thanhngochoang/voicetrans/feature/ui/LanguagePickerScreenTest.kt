package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.mutableStateOf
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.overlay.MemStore
import io.github.thanhngochoang.voicetrans.feature.overlay.waitFor
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.PackManager
import io.github.thanhngochoang.voicetrans.feature.ui.language.LanguageItem
import io.github.thanhngochoang.voicetrans.feature.ui.language.LanguagePickerScreen
import io.github.thanhngochoang.voicetrans.feature.ui.language.LanguageRow
import io.github.thanhngochoang.voicetrans.feature.ui.language.TargetPicker
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

private class FakePacks : PackManager {
    override val packs = MutableStateFlow<Map<String, ModelState>>(mapOf("en" to ModelState.Ready, "zh" to ModelState.Ready, "vi" to ModelState.Ready))
    val downloads = mutableListOf<Pair<String, Boolean>>()
    val gate = CompletableDeferred<Unit>()
    override suspend fun refreshPacks() {}
    override suspend fun download(lang: String, wifiOnly: Boolean): Result<Unit> {
        downloads += lang to wifiOnly
        packs.value = packs.value + (lang to ModelState.Downloading)
        gate.await()
        packs.value = packs.value + (lang to ModelState.Ready)
        return Result.success(Unit)
    }
    override suspend fun delete(lang: String) = Result.success(Unit)
    override fun requiredPacks(target: String) = setOf("en", "zh", target)
    override fun isReadyFor(target: String) = requiredPacks(target).all { packs.value[it] == ModelState.Ready }
}

class LanguagePickerScreenTest {
    @get:Rule val rule = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun cleanup() = scope.cancel()

    @Test fun pickUndownloaded_startsDownload_keepsOldTarget() = runBlocking {
        val packs = FakePacks()
        val settings = SettingsRepository(MemStore())
        val picker = TargetPicker(packs, settings, scope)
        picker.pick("ja")
        waitFor(what = "download started") { packs.downloads.isNotEmpty() }
        assertEquals(listOf("ja" to true), packs.downloads)
        assertEquals("vi", settings.targetLanguage.first())
        assertEquals("ja", picker.pending.value)
        packs.gate.complete(Unit)
        waitFor(what = "target switched") { runBlocking { settings.targetLanguage.first() } == "ja" }
        assertEquals(null, picker.pending.value)
    }

    @Test fun pickDownloaded_switchesImmediately() = runBlocking {
        val packs = FakePacks().apply { packs.value = packs.value + ("ja" to ModelState.Ready) }
        val settings = SettingsRepository(MemStore())
        TargetPicker(packs, settings, scope).pick("ja")
        waitFor(what = "target switched") { runBlocking { settings.targetLanguage.first() } == "ja" }
        assertEquals(emptyList<Pair<String, Boolean>>(), packs.downloads)
    }

    @Test fun screen_rendersRows_filtersBySearch_andReportsPick() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = listOf(
            LanguageRow(LanguageItem("vi", "Vietnamese", true), ModelState.Ready, isCurrent = true),
            LanguageRow(LanguageItem("ja", "Japanese", true), ModelState.NotDownloaded, isCurrent = false),
            LanguageRow(LanguageItem("de", "German", false), ModelState.NotDownloaded, isCurrent = false),
        )
        var picked: String? = null
        rule.setContent { VoiceTransTheme(ThemeMode.DARK) { LanguagePickerScreen(rows, pendingName = "Japanese", currentName = "Vietnamese", onPick = { picked = it }, onBack = {}) } }
        rule.onNodeWithText("Japanese").assertIsDisplayed()
        rule.onNodeWithTag("language_search").performTextInput("ger")
        rule.onNodeWithText("Japanese").assertDoesNotExist()
        rule.onNodeWithText("German").performClick()
        assertEquals("de", picked)
        rule.onNodeWithText(ctx.getString(R.string.target_pending, "Japanese", "Vietnamese")).assertExists()
    }
}
