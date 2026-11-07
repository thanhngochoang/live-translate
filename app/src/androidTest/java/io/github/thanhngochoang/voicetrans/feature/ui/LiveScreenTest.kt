package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thanhngochoang.voicetrans.MainActivity
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.session.CaptureWarning
import io.github.thanhngochoang.voicetrans.feature.session.SessionState
import io.github.thanhngochoang.voicetrans.feature.session.UtteranceKind
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.ui.live.LiveActions
import io.github.thanhngochoang.voicetrans.feature.ui.live.LiveRoute
import io.github.thanhngochoang.voicetrans.feature.ui.live.LiveScreen
import io.github.thanhngochoang.voicetrans.feature.ui.live.LiveUiState
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VoiceTransTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiveScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = ctx.getString(id)

    private val state = mutableStateOf(LiveUiState())
    private var started = 0; private var stopped = 0; private var fixed = 0; private var dismissedExit = 0
    private val actions = LiveActions(
        onStart = { started++ }, onStop = { stopped++ }, onRetryConsent = {}, onDismissExitReason = { dismissedExit++; state.value = state.value.copy(exitReason = null) },
        onFixBackground = { fixed++ }, onDismissBackgroundWarning = {}, onOpenSettings = {}, onOpenTargetPicker = {},
    )
    private var listState: LazyListState? = null

    private fun show(override: DpSize? = null) = rule.setContent {
        VoiceTransTheme(ThemeMode.DARK) {
            val ls = rememberLazyListState(initialFirstVisibleItemIndex = (state.value.utterances.size - 1).coerceAtLeast(0)).also { listState = it }
            val content = @androidx.compose.runtime.Composable { LiveScreen(state.value, actions, ls) }
            if (override != null) DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(override)) { content() } else content()
        }
    }

    private fun many(n: Int) = (1..n).map { utt(it.toLong(), "source $it", "dịch $it") }

    @After fun cleanup() = UiHost.reset()

    @Test fun showsSourceBadgeAndTranslation() {
        state.value = LiveUiState(utterances = listOf(utt(1, "good morning", "chào buổi sáng")))
        show()
        rule.onNodeWithText("EN").assertIsDisplayed()
        rule.onNodeWithText("good morning").assertIsDisplayed()
        rule.onNodeWithText("chào buổi sáng").assertIsDisplayed()
    }

    @Test fun musicUtterance_rendersCompactNoteRow() {
        state.value = LiveUiState(utterances = listOf(utt(1, "", null, kind = UtteranceKind.MUSIC, start = 0, end = 42_000)))
        show()
        rule.onNodeWithTag("music_row").assertIsDisplayed()
        rule.onNodeWithText("♪", substring = true).assertIsDisplayed()
        rule.onNodeWithText("0:42", substring = true).assertIsDisplayed()
        rule.onNodeWithText("EN").assertDoesNotExist()
    }

    @Test fun sameLanguage_hidesTranslationLine() {
        state.value = LiveUiState(utterances = listOf(utt(1, "hello", "hello", target = "en")))
        show()
        rule.onAllNodesWithText("hello").assertCountEquals(1)
    }

    @Test fun partialShownLast() {
        state.value = LiveUiState(utterances = many(3), partial = "hel")
        show()
        rule.onNodeWithTag("partial").assertIsDisplayed()
        rule.onNodeWithText("hel").assertIsDisplayed()
        val partialTop = rule.onNodeWithTag("partial").fetchSemanticsNode().boundsInRoot.top
        val lastCardTop = rule.onAllNodesWithTag("utterance_card").fetchSemanticsNodes().maxOf { it.boundsInRoot.top }
        assertTrue(partialTop > lastCardTop)
    }

    @Test fun failedTranslation_showsMarker() {
        state.value = LiveUiState(utterances = listOf(utt(1, "oops", null, failed = true)))
        show()
        rule.onNodeWithText(s(R.string.translation_failed)).assertIsDisplayed()
    }

    @Test fun emptyState_shown() {
        show()
        rule.onNodeWithTag("empty").assertIsDisplayed()
    }

    @Test fun scrolledUp_newUtterance_doesNotAutoScroll_showsJumpChip() {
        state.value = LiveUiState(utterances = many(30))
        show()
        rule.onNodeWithTag("live_list").performScrollToIndex(0)
        rule.waitForIdle()
        state.value = state.value.copy(utterances = many(31))
        rule.waitForIdle()
        assertEquals(0, listState!!.firstVisibleItemIndex)
        rule.onNodeWithText(s(R.string.new_sentence)).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("dịch 31").assertIsDisplayed()
    }

    @Test fun atBottom_newUtterance_autoScrolls() {
        state.value = LiveUiState(utterances = many(30))
        show()
        rule.waitForIdle()
        state.value = state.value.copy(utterances = many(31))
        rule.waitForIdle()
        rule.onNodeWithText("dịch 31").assertIsDisplayed()
    }

    @Test fun runningStatus_saysInternalAudio() {
        state.value = LiveUiState(sessionState = SessionState.Running)
        show()
        rule.onNodeWithText(s(R.string.status_running_internal)).assertIsDisplayed()
    }

    @Test fun captureBlocked_showsBanner_andStaysRunning() {
        state.value = LiveUiState(sessionState = SessionState.Running, captureWarning = CaptureWarning.BLOCKED_BY_APP)
        show()
        rule.onNodeWithText(s(R.string.capture_blocked)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.status_running_internal)).assertIsDisplayed()
    }

    @Test fun exitReasonBanner_shown_andDismissible() {
        state.value = LiveUiState(exitReason = R.string.exit_memory)
        show()
        rule.onNodeWithText(s(R.string.exit_memory)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.dismiss)).performClick()
        rule.onNodeWithText(s(R.string.exit_memory)).assertDoesNotExist()
        assertEquals(1, dismissedExit)
    }

    @Test fun backgroundWarning_whenBatteryOptimized_linksToSetup() {
        state.value = LiveUiState(backgroundWarning = true)
        show()
        rule.onNodeWithText(s(R.string.background_warning)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.action_fix)).performClick()
        assertEquals(1, fixed)
    }

    @Test fun cardSemantics_mergedForTalkBack() {
        state.value = LiveUiState(utterances = listOf(utt(1, "good morning", "chào buổi sáng")))
        show()
        rule.onNode(hasContentDescription("good morning", substring = true) and hasContentDescription("chào buổi sáng", substring = true)).assertExists()
    }

    @Test fun compactHeight_startStopIsFab_andListGetsSpace() {
        state.value = LiveUiState(utterances = many(12))
        show(DpSize(400.dp, 360.dp))
        rule.onNodeWithTag("start_stop_fab").assertIsDisplayed()
        rule.onNodeWithTag("start_stop_bar").assertDoesNotExist()
        val list = rule.onNodeWithTag("live_list").fetchSemanticsNode().boundsInRoot
        val visible = rule.onAllNodesWithTag("utterance_card").fetchSemanticsNodes().count { it.boundsInRoot.top >= list.top - 1 && it.boundsInRoot.bottom <= list.bottom + 1 }
        assertTrue("visible cards $visible", visible >= 4)
    }

    @Test fun portrait_startStopIsBottomBar() {
        show()
        rule.onNodeWithTag("start_stop_bar").assertIsDisplayed()
        rule.onNodeWithTag("start_stop_fab").assertDoesNotExist()
    }

    @Test fun landscape_contentMaxWidth720() {
        state.value = LiveUiState(utterances = many(3))
        show(DpSize(1000.dp, 400.dp))
        rule.onNodeWithTag("live_list").assertWidthIsEqualTo(720.dp)
        rule.onNodeWithTag("live_list").assertLeftPositionInRootIsEqualTo(140.dp)
    }

    // ---- start flow (LiveRoute) ----

    private fun route(model: FakeLiveModel, consent: FakeConsent, autoStart: Boolean = false, consumed: () -> Unit = {}) = rule.setContent {
        VoiceTransTheme(ThemeMode.DARK) {
            LiveRoute(model, consent, autoStart, consumed, onOpenSettings = {}, onOpenTargetPicker = {}, onFixBackground = {})
        }
    }

    @Test fun firstStart_showsConsentExplainer_once() {
        val model = FakeLiveModel().apply { consentExplainerSeen.value = false }
        val consent = FakeConsent(model)
        route(model, consent)
        rule.onNodeWithTag("start_stop_bar").performClick()
        rule.onNodeWithTag("consent_explainer").assertIsDisplayed()
        assertEquals(0, consent.requests)
        rule.onNodeWithText(s(R.string.action_continue)).performClick()
        rule.waitForIdle()
        assertEquals(1, consent.requests)
        assertTrue(model.consentExplainerSeen.value)
        rule.onNodeWithTag("start_stop_bar").performClick()
        rule.onNodeWithTag("consent_explainer").assertDoesNotExist()
        assertEquals(2, consent.requests)
    }

    @Test fun consentDenied_showsRetry() {
        val model = FakeLiveModel()
        val consent = FakeConsent(model, deny = true)
        route(model, consent)
        rule.onNodeWithTag("start_stop_bar").performClick()
        rule.onNodeWithText(s(R.string.consent_denied)).assertIsDisplayed()
        rule.onNodeWithText(s(R.string.retry)).performClick()
        assertEquals(2, consent.requests)
    }

    @Test fun stopButton_whileRunning_stopsSession() {
        val model = FakeLiveModel().apply { sessionState.value = SessionState.Running }
        route(model, FakeConsent(model))
        rule.onNodeWithTag("start_stop_bar").performClick()
        assertEquals(1, model.stops)
    }
}

class LiveActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = ctx.getString(id)

    @After fun cleanup() = UiHost.reset()

    private fun launch(model: FakeLiveModel, consent: FakeConsent? = null, autoStart: Boolean = false, override: DpSize? = null, listRef: ((LazyListState) -> Unit)? = null): ActivityScenario<UiHostActivity> {
        UiHost.content = { activity ->
            VoiceTransTheme(ThemeMode.DARK) {
                val ls = rememberLazyListState().also { listRef?.invoke(it) }
                val c = consent ?: object : io.github.thanhngochoang.voicetrans.feature.ui.live.ConsentRequester { override fun request() { UiHost.consent!!.request() } }
                val body = @androidx.compose.runtime.Composable {
                    LiveRoute(
                        model, c, autoStart && activity.intent.getBooleanExtra(MainActivity.EXTRA_AUTO_START, false),
                        onAutoStartConsumed = { activity.intent.removeExtra(MainActivity.EXTRA_AUTO_START) },
                        onOpenSettings = {}, onOpenTargetPicker = {}, onFixBackground = {}, listState = ls,
                    )
                }
                if (override != null) DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(override)) { body() } else body()
            }
        }
        val intent = android.content.Intent(ctx, UiHostActivity::class.java).putExtra(MainActivity.EXTRA_AUTO_START, autoStart)
        return ActivityScenario.launch(intent)
    }

    @Test fun recreateActivity_keepsUtterances() {
        val model = FakeLiveModel().apply { utterances.value = listOf(utt(1, "good morning", "chào buổi sáng")) }
        val scenario = launch(model)
        rule.onNodeWithText("chào buổi sáng").assertIsDisplayed()
        scenario.recreate()
        rule.onNodeWithText("chào buổi sáng").assertIsDisplayed()
        scenario.close()
    }

    @Test fun rotation_keepsScrollPosition() {
        val model = FakeLiveModel().apply { utterances.value = (1..30).map { utt(it.toLong(), "source $it", "dịch $it") } }
        var ls: LazyListState? = null
        val scenario = launch(model, override = DpSize(400.dp, 360.dp), listRef = { ls = it })
        rule.onNodeWithTag("live_list").performScrollToIndex(10)
        rule.waitForIdle()
        val before = ls!!.firstVisibleItemIndex
        scenario.recreate()
        rule.waitForIdle()
        assertEquals(before, ls!!.firstVisibleItemIndex)
        assertTrue(before >= 8)
        scenario.close()
    }

    @Test fun autoStartExtra_requestsConsentOnce_evenAfterRecreate() {
        val model = FakeLiveModel()
        val consent = FakeConsent(model)
        val scenario = launch(model, consent, autoStart = true)
        rule.waitForIdle()
        assertEquals(1, consent.requests)
        scenario.recreate()
        rule.waitForIdle()
        assertEquals(1, consent.requests)
        scenario.close()
    }

    @Test fun consentResult_afterRecreate_startsSession() {
        val model = FakeLiveModel()
        val scenario = launch(model)
        rule.onNodeWithTag("start_stop_bar").performClick()
        rule.waitForIdle()
        assertEquals(1, UiHost.registry.launched.size)
        scenario.recreate()
        rule.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { UiHost.registry.deliverOk() }
        rule.waitForIdle()
        assertEquals(1, UiHost.consentStarted)
        scenario.close()
    }
}
