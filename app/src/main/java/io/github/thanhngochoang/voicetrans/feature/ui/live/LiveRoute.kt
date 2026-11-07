package io.github.thanhngochoang.voicetrans.feature.ui.live

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.thanhngochoang.voicetrans.feature.session.SessionState
import kotlinx.coroutines.launch

/**
 * Live screen plus the start flow: first start shows the consent explainer (once), then the system consent dialog;
 * the granted result starts the foreground service from the activity's registered handler. [autoStart] (from the
 * Quick Settings tile) is handled once and then cleared through [onAutoStartConsumed].
 */
@Composable
fun LiveRoute(
    model: LiveModel,
    consent: ConsentRequester,
    autoStart: Boolean,
    onAutoStartConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTargetPicker: () -> Unit,
    onFixBackground: () -> Unit,
    listState: LazyListState? = null,
) {
    val scope = rememberCoroutineScope()
    val utterances by model.utterances.collectAsState()
    val partial by model.partial.collectAsState()
    val sessionState by model.sessionState.collectAsState()
    val warning by model.captureWarning.collectAsState()
    val target by model.targetName.collectAsState()
    val seen by model.consentExplainerSeen.collectAsState()
    val denied by model.consentDenied.collectAsState()
    val background by model.background.collectAsState()
    val size by model.subtitleSizeSp.collectAsState()
    val exitReason by model.exitReason.collectAsState()
    var showExplainer by rememberSaveable { mutableStateOf(false) }
    var backgroundDismissed by rememberSaveable { mutableStateOf(false) }
    var autoStartHandled by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        model.refreshBackground()
        onPauseOrDispose { }
    }

    val start: () -> Unit = {
        if (seen) consent.request() else showExplainer = true
    }
    LaunchedEffect(autoStart) {
        if (autoStart && !autoStartHandled) {
            autoStartHandled = true
            onAutoStartConsumed()
            if (sessionState !is SessionState.Running) start()
        }
    }

    val state = LiveUiState(
        utterances = utterances, partial = partial, sessionState = sessionState, captureWarning = warning, targetName = target,
        exitReason = exitReason, consentDenied = denied, subtitleSizeSp = size,
        backgroundWarning = !backgroundDismissed && (!background.notificationsGranted || !background.batteryUnrestricted),
    )
    val actions = remember(model, consent, seen) {
        LiveActions(
            onStart = start, onStop = model::stopSession, onRetryConsent = { consent.request() },
            onDismissExitReason = model::dismissExitReason, onFixBackground = onFixBackground,
            onDismissBackgroundWarning = { backgroundDismissed = true }, onOpenSettings = onOpenSettings, onOpenTargetPicker = onOpenTargetPicker,
        )
    }
    if (listState != null) LiveScreen(state, actions, listState) else LiveScreen(state, actions)
    if (showExplainer) {
        ConsentExplainerSheet(
            onContinue = {
                showExplainer = false
                scope.launch { model.markExplainerSeen() }
                consent.request()
            },
            onDismiss = { showExplainer = false },
        )
    }
}
