package io.github.thanhngochoang.voicetrans.feature.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.feature.session.CaptureWarning
import io.github.thanhngochoang.voicetrans.feature.session.SessionState
import io.github.thanhngochoang.voicetrans.feature.session.StopReason
import io.github.thanhngochoang.voicetrans.feature.session.Utterance
import io.github.thanhngochoang.voicetrans.feature.ui.theme.VtTheme

data class LiveUiState(
    val utterances: List<Utterance> = emptyList(),
    val partial: String? = null,
    val sessionState: SessionState = SessionState.Idle,
    val captureWarning: CaptureWarning? = null,
    val targetName: String = "",
    val exitReason: Int? = null,
    val backgroundWarning: Boolean = false,
    val consentDenied: Boolean = false,
    val subtitleSizeSp: Int = 20,
)

class LiveActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onRetryConsent: () -> Unit,
    val onDismissExitReason: () -> Unit,
    val onFixBackground: () -> Unit,
    val onDismissBackgroundWarning: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenTargetPicker: () -> Unit,
)

/**
 * The Live screen. The list is owned by the session; this composable only renders it. Auto-scroll happens only when
 * the user is already at the bottom, otherwise a "↓ Câu mới" chip appears. At compact heights (landscape phone)
 * Start/Stop becomes a FAB so the list gets the full height. Content is at most 720 dp wide and centered.
 */
@Composable
fun LiveScreen(
    state: LiveUiState,
    actions: LiveActions,
    // Starts at the end, so returning to the app shows the newest sentences.
    listState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = (state.utterances.size - 1).coerceAtLeast(0)),
) {
    val running = state.sessionState == SessionState.Running
    val haptics = LocalHapticFeedback.current
    val toggle = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (running) actions.onStop() else actions.onStart() }

    // Measured from the layout constraints (the window height), so it also follows a forced size in tests.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val compact = maxHeight < 480.dp
        Column(Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxHeight().fillMaxWidth()) {
            TopBar(state, actions, compact)
            if (!compact) StatusRow(state)
            Banners(state, actions)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                UtteranceList(state, listState, compact)
            }
            if (!compact) {
                Box(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    StartStopButton(running, toggle, Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("start_stop_bar"))
                }
            }
        }
        if (compact) {
            ExtendedFloatingActionButton(
                onClick = toggle,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).heightIn(min = 56.dp).testTag("start_stop_fab"),
                containerColor = if (running) VtTheme.colors.errorContainer else VtTheme.colors.primaryContainer,
                contentColor = if (running) VtTheme.colors.onErrorContainer else VtTheme.colors.onPrimaryContainer,
            ) { Text(stringResource(if (running) R.string.action_stop else R.string.start_listening)) }
        }
    }
}

@Composable
private fun StartStopButton(running: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Button(
        onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) VtTheme.colors.errorContainer else VtTheme.colors.primaryContainer,
            contentColor = if (running) VtTheme.colors.onErrorContainer else VtTheme.colors.onPrimaryContainer,
        ),
    ) {
        if (!running) Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(stringResource(if (running) R.string.action_stop else R.string.start_listening), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TopBar(state: LiveUiState, actions: LiveActions, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            // Compact height: the status becomes the subtitle line of the small top bar.
            if (compact) StatusRow(state, padded = false)
        }
        if (state.targetName.isNotEmpty()) {
            Text(
                stringResource(R.string.target_chip, state.targetName), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.heightIn(min = 48.dp).clickable(onClick = actions.onOpenTargetPicker).padding(horizontal = 8.dp).wrapContentHeightCentered(),
            )
        }
        IconButton(onClick = actions.onOpenSettings, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_open_desc), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun Modifier.wrapContentHeightCentered(): Modifier = this.then(Modifier.padding(vertical = 12.dp))

@Composable
private fun StatusRow(state: LiveUiState, padded: Boolean = true) {
    val running = state.sessionState == SessionState.Running
    Text(
        stringResource(if (running) R.string.status_running_internal else R.string.status_stopped),
        style = MaterialTheme.typography.bodyMedium,
        color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (padded) Modifier.padding(horizontal = 16.dp, vertical = 4.dp) else Modifier,
    )
}

@Composable
private fun Banners(state: LiveUiState, actions: LiveActions) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.captureWarning == CaptureWarning.BLOCKED_BY_APP) Banner(stringResource(R.string.capture_blocked))
        val stopped = state.sessionState as? SessionState.Stopped
        if (stopped != null && stopped.reason != StopReason.USER) {
            Banner(
                stringResource(
                    when (stopped.reason) {
                        StopReason.PROJECTION_ENDED -> R.string.stopped_projection
                        StopReason.AUDIO_ERROR -> R.string.stopped_audio_error
                        StopReason.IDLE_TIMEOUT -> R.string.stopped_idle
                        StopReason.USER -> R.string.stopped_generic
                    }.let { if (it == R.string.stopped_idle) R.string.stopped_generic else it },
                ),
                action = stringResource(R.string.action_restart) to actions.onStart,
            )
        }
        state.exitReason?.let { Banner(stringResource(it), action = stringResource(R.string.dismiss) to actions.onDismissExitReason) }
        if (state.consentDenied) Banner(stringResource(R.string.consent_denied), action = stringResource(R.string.retry) to actions.onRetryConsent)
        if (state.backgroundWarning) {
            Banner(
                stringResource(R.string.background_warning), action = stringResource(R.string.action_fix) to actions.onFixBackground,
                secondary = stringResource(R.string.dismiss) to actions.onDismissBackgroundWarning, warning = true,
            )
        }
    }
}

@Composable
private fun Banner(
    text: String,
    action: Pair<String, () -> Unit>? = null,
    secondary: Pair<String, () -> Unit>? = null,
    warning: Boolean = false,
) {
    val container = if (warning) MaterialTheme.colorScheme.surface else VtTheme.colors.bannerContainer
    val content = if (warning) MaterialTheme.colorScheme.onSurface else VtTheme.colors.onBanner
    Column(Modifier.fillMaxWidth().background(container, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = content)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            secondary?.let { TextButton(onClick = it.second, modifier = Modifier.heightIn(min = 48.dp)) { Text(it.first) } }
            action?.let { TextButton(onClick = it.second, modifier = Modifier.heightIn(min = 48.dp)) { Text(it.first) } }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.UtteranceList(state: LiveUiState, listState: LazyListState, compact: Boolean) {
    val itemCount = state.utterances.size + if (state.partial != null) 1 else 0
    // Read from the previous layout during composition: "was the user at the bottom before this item arrived?"
    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount == 0 || (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 1
        }
    }
    val wasAtBottom = atBottom
    var lastCount by rememberSaveable { mutableStateOf(itemCount) }
    var pendingNew by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(itemCount) {
        if (itemCount > lastCount) {
            if (wasAtBottom) listState.scrollToItem(itemCount - 1) else pendingNew = true
        }
        lastCount = itemCount
    }
    LaunchedEffect(atBottom) { if (atBottom) pendingNew = false }

    if (state.utterances.isEmpty() && state.partial == null) {
        Box(Modifier.fillMaxSize().padding(24.dp).testTag("empty"), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.live_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val newest = state.utterances.lastOrNull { it.translation != null && it.kind == io.github.thanhngochoang.voicetrans.feature.session.UtteranceKind.SPEECH }
        ?.translation?.let { io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter.purgeCjk(it) }
        ?.takeIf { it.any { ch -> ch.isLetter() } && !io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter.hasCjk(it) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("live_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(if (compact) 8.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        itemsIndexed(state.utterances, key = { _, u -> u.id }) { _, u -> UtteranceCard(u, state.subtitleSizeSp, compact) }
        state.partial?.let { p ->
            val cleanPartial = if (io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter.hasCjk(p)) "Đang dịch…" else p
            item(key = "partial") {
                Text(
                    cleanPartial, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("partial").padding(horizontal = 4.dp),
                )
            }
        }
    }
    // One polite live region announces only the newest finished translation, never partials.
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = newest.orEmpty() })
    if (pendingNew) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        OutlinedButton(
            onClick = { scope.launch { listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0)) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.new_sentence)) }
    }
}


