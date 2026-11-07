package io.github.thanhngochoang.voicetrans.feature.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.OverlayPosition
import io.github.thanhngochoang.voicetrans.feature.overlay.OverlayLine
import io.github.thanhngochoang.voicetrans.feature.overlay.OverlayMode
import io.github.thanhngochoang.voicetrans.feature.overlay.SubtitleOverlay
import io.github.thanhngochoang.voicetrans.feature.ui.ScreenFrame
import kotlin.math.roundToInt

/**
 * Settings → Phụ đề nổi. With the overlay permission the real overlay is shown in setup mode while this screen is
 * open (and leaves setup mode when it closes). Without the permission an in-app preview is shown instead and every
 * control except the permission button is disabled.
 */
@Composable
fun OverlaySettingsScreen(state: SettingsUiState, actions: SettingsActions, onBack: () -> Unit) {
    val overlay = state.overlay
    val permitted = state.overlayPermission
    val controlsEnabled = permitted && overlay.enabled
    val controls = actions.overlayControls

    DisposableEffect(permitted) {
        if (permitted) controls.setSetupMode(true)
        onDispose { if (permitted) controls.setSetupMode(false) }
    }

    ScreenFrame {
        SubPageHeader(stringResource(R.string.settings_overlay_title), onBack)
        if (!permitted) {
            Text(stringResource(R.string.overlay_permission_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = actions.onRequestOverlayPermission, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_grant)) }
            Section(stringResource(R.string.settings_overlay_title)) {
                Preview(state)
            }
        } else {
            Text(stringResource(R.string.overlay_permission_ok), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!controlsEnabled) {
            Text(stringResource(R.string.overlay_disabled_reason), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section(stringResource(R.string.settings_overlay_title)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.overlay_enable), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(checked = overlay.enabled, enabled = permitted, onCheckedChange = { v -> actions.onOverlay { it.copy(enabled = v) } }, modifier = Modifier.testTag("overlay_enable"))
            }

            Text(stringResource(R.string.overlay_opacity_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            val opacity = overlay.bgOpacityPercent
            Text(
                when (opacity) {
                    0 -> stringResource(R.string.overlay_opacity_none)
                    100 -> stringResource(R.string.overlay_opacity_solid)
                    else -> stringResource(R.string.overlay_opacity_dim, opacity)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary,
            )
            var dragging by remember { mutableStateOf(false) }
            Slider(
                value = opacity.toFloat(), valueRange = 0f..100f, steps = 9, enabled = controlsEnabled,
                onValueChange = { v ->
                    if (!dragging) { dragging = true; controls.setOpacityDragging(true) }
                    actions.onOverlay { it.copy(bgOpacityPercent = ((v / 10f).roundToInt() * 10)) }
                },
                onValueChangeFinished = { dragging = false; controls.setOpacityDragging(false) },
                modifier = Modifier.testTag("overlay_opacity"),
            )
            Text(stringResource(R.string.overlay_opacity_helper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text("${stringResource(R.string.overlay_text_size)}: ${overlay.textSizeSp} sp", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = overlay.textSizeSp.toFloat(), valueRange = 14f..28f, steps = 13, enabled = controlsEnabled,
                onValueChange = { v -> actions.onOverlay { it.copy(textSizeSp = v.roundToInt()) } }, modifier = Modifier.testTag("overlay_text_size"),
            )

            Text(stringResource(R.string.overlay_lines), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Segmented(listOf(1, 2, 3).map { it to it.toString() }, overlay.maxLines, "lines", controlsEnabled, { v -> actions.onOverlay { it.copy(maxLines = v) } }, Modifier.testTag("overlay_lines"))

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.overlay_show_source), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(checked = overlay.showSource, enabled = controlsEnabled, onCheckedChange = { v -> actions.onOverlay { it.copy(showSource = v) } }, modifier = Modifier.testTag("overlay_show_source"))
            }

            Text(stringResource(R.string.overlay_position), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Segmented(
                listOf(
                    OverlayPosition.TOP to stringResource(R.string.position_top), OverlayPosition.CENTER to stringResource(R.string.position_center),
                    OverlayPosition.BOTTOM to stringResource(R.string.position_bottom),
                ),
                overlay.position, "position", controlsEnabled, { v -> actions.onOverlay { it.copy(position = v) } }, Modifier.testTag("overlay_position"),
            )
            OutlinedButton(
                onClick = { actions.onOverlay { it.copy(position = OverlayPosition.BOTTOM, customYPortrait = null, customYLandscape = null, customXPortrait = null, customXLandscape = null) } },
                enabled = controlsEnabled, modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.overlay_reset_position)) }
            Text(stringResource(R.string.overlay_position_helper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(stringResource(R.string.overlay_auto_hide), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Segmented(
                listOf(0, 5, 8, 15).map { it to if (it == 0) stringResource(R.string.auto_hide_never) else stringResource(R.string.auto_hide_seconds, it) },
                overlay.autoHideSec, "auto_hide", controlsEnabled, { v -> actions.onOverlay { it.copy(autoHideSec = v) } }, Modifier.testTag("overlay_auto_hide"),
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.overlay_lock), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(checked = overlay.locked, enabled = controlsEnabled, onCheckedChange = { v -> actions.onOverlay { it.copy(locked = v) } }, modifier = Modifier.testTag("overlay_lock"))
            }
            Text(stringResource(R.string.overlay_lock_helper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** In-app preview card (same rendering as the real box, setup mode) for when the overlay permission is missing. */
@Composable
private fun Preview(state: SettingsUiState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("overlay_preview")) {
        SubtitleOverlay(
            lines = emptyList<OverlayLine>(), notice = null, settings = state.overlay, mode = OverlayMode.SETUP,
            onClose = {}, onExitApp = {},
        )
    }
}
