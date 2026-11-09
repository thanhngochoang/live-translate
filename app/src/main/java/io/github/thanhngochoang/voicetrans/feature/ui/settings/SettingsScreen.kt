package io.github.thanhngochoang.voicetrans.feature.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.ui.AppLanguage
import io.github.thanhngochoang.voicetrans.feature.ui.ScreenFrame
import io.github.thanhngochoang.voicetrans.feature.ui.about.AboutScreen

private const val PAGE_MAIN = 0
private const val PAGE_OVERLAY = 1
private const val PAGE_ABOUT = 2

/** Grouped settings. The floating-subtitle and About pages are sub-screens of this one (Back returns to the list). */
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, onBack: () -> Unit = {}) {
    var page by rememberSaveable { mutableIntStateOf(PAGE_MAIN) }
    BackHandler(enabled = page != PAGE_MAIN) { page = PAGE_MAIN }
    when (page) {
        PAGE_OVERLAY -> OverlaySettingsScreen(state, actions, onBack = { page = PAGE_MAIN })
        PAGE_ABOUT -> AboutScreen(state.versionName, state.modelVersions, onBack = { page = PAGE_MAIN })
        else -> MainPage(state, actions, onBack, openOverlay = { page = PAGE_OVERLAY }, openAbout = { page = PAGE_ABOUT })
    }
}

@Composable
internal fun SubPageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back), tint = MaterialTheme.colorScheme.onSurface)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

@Composable
internal fun LinkRow(title: String, value: String? = null, onClick: () -> Unit, tag: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).let { if (tag != null) it.testTag(tag) else it },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Segmented(options: List<Pair<T, String>>, selected: T, tagPrefix: String, enabled: Boolean = true, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected, onClick = { onSelect(value) }, enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.heightIn(min = 48.dp).testTag("${tagPrefix}_$value"),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun MainPage(state: SettingsUiState, actions: SettingsActions, onBack: () -> Unit, openOverlay: () -> Unit, openAbout: () -> Unit) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    ScreenFrame(Modifier.testTag("settings_root")) {
        SubPageHeader(stringResource(R.string.settings_title), onBack)

        Section(stringResource(R.string.settings_language_header)) {
            Text(stringResource(R.string.settings_app_language), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Segmented(
                listOf(
                    AppLanguage.SYSTEM to stringResource(R.string.language_system), AppLanguage.VI to stringResource(R.string.language_vi),
                    AppLanguage.EN to stringResource(R.string.language_en),
                ),
                state.appLanguage, "applang", onSelect = actions.onAppLanguage,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_draft_translation), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.settings_draft_translation_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.draftTranslation, onCheckedChange = actions.onDraftTranslation, modifier = Modifier.testTag("draft_switch"))
            }
            Text(stringResource(R.string.settings_source_language), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.settings_source_value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_source_helper), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_packs_header), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            for (pack in state.packs) PackRow(pack) { pendingDelete = pack.code }
        }

        Section(stringResource(R.string.settings_display_header)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Segmented(
                listOf(
                    ThemeMode.DARK to stringResource(R.string.theme_dark), ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.SYSTEM to stringResource(R.string.theme_system),
                ),
                state.themeMode, "theme", onSelect = actions.onTheme,
            )
            Text("${stringResource(R.string.settings_subtitle_size)}: ${state.subtitleSizeSp} sp", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Slider(
                value = state.subtitleSizeSp.toFloat(), onValueChange = { actions.onSubtitleSize(it.toInt()) }, valueRange = 14f..32f, steps = 17,
                modifier = Modifier.testTag("subtitle_size"),
            )
            Text(
                stringResource(R.string.subtitle_preview_text), color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = state.subtitleSizeSp.sp, lineHeight = (state.subtitleSizeSp * 1.4f).sp),
                modifier = Modifier.background(MaterialTheme.colorScheme.background, RoundedCornerShape(12.dp)).padding(12.dp).fillMaxWidth(),
            )
            LinkRow(stringResource(R.string.settings_overlay_title), onClick = openOverlay, tag = "settings_overlay_row")
        }

        Section(stringResource(R.string.settings_background_header)) {
            StatusLine(stringResource(R.string.bg_notifications_title), state.background.notificationsGranted,
                stringResource(if (state.background.notificationsGranted) R.string.status_granted else R.string.status_not_granted), actions.onRequestNotifications)
            StatusLine(stringResource(R.string.bg_battery_title), state.background.batteryUnrestricted,
                stringResource(if (state.background.batteryUnrestricted) R.string.battery_unrestricted else R.string.battery_optimized), actions.onRequestBattery)
            Text(stringResource(R.string.auto_stop_title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Segmented(
                listOf(0, 10, 30, 60).map { it to if (it == 0) stringResource(R.string.auto_stop_never) else stringResource(R.string.auto_stop_minutes, it) },
                state.autoStopMinutes, "autostop", onSelect = actions.onAutoStop,
            )
            LinkRow(stringResource(R.string.settings_reopen_background), onClick = actions.onReopenBackgroundSetup)
        }

        Section(stringResource(R.string.settings_downloads_header)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_wifi_only), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Switch(checked = state.wifiOnly, onCheckedChange = actions.onWifiOnly)
            }
        }

        Section(stringResource(R.string.settings_about)) {
            LinkRow(stringResource(R.string.settings_about), onClick = openAbout, tag = "settings_about_row")
        }
    }
    pendingDelete?.let { code ->
        val name = state.packs.firstOrNull { it.code == code }?.name ?: code
        AlertDialog(
            modifier = Modifier.testTag("delete_dialog"),
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_pack_title)) },
            text = { Text(stringResource(R.string.delete_pack_message, name)) },
            confirmButton = { TextButton(onClick = { pendingDelete = null; actions.onDeletePack(code) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PackRow(pack: PackItem, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(pack.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (pack.inUse) {
            Text(stringResource(R.string.in_use), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val description = stringResource(R.string.delete_pack_desc, pack.name)
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusLine(title: String, ok: Boolean, status: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, modifier = Modifier.size(18.dp), tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!ok) OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_grant)) }
    }
}
