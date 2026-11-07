package io.github.thanhngochoang.voicetrans.feature.ui.settings

import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.ui.AppLanguage
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus

/** What the Settings screens ask of the floating subtitle (implemented by OverlayController). */
interface OverlayControls {
    fun setSetupMode(active: Boolean)

    /** While the opacity slider is dragged the box shows the real opacity instead of the setup opacity. */
    fun setOpacityDragging(active: Boolean)
}

data class PackItem(val code: String, val name: String, val state: ModelState, val inUse: Boolean)

data class SettingsUiState(
    val appLanguage: AppLanguage,
    val targetName: String,
    val themeMode: ThemeMode,
    val subtitleSizeSp: Int,
    val overlay: OverlaySettings,
    val overlayPermission: Boolean,
    val background: BackgroundStatus,
    val autoStopMinutes: Int,
    val wifiOnly: Boolean,
    val packs: List<PackItem>,
    val versionName: String,
    val modelVersions: List<String>,
)

class SettingsActions(
    val onAppLanguage: (AppLanguage) -> Unit,
    val onTheme: (ThemeMode) -> Unit,
    val onSubtitleSize: (Int) -> Unit,
    val onOverlay: ((OverlaySettings) -> OverlaySettings) -> Unit,
    val onRequestOverlayPermission: () -> Unit,
    val onRequestNotifications: () -> Unit,
    val onRequestBattery: () -> Unit,
    val onAutoStop: (Int) -> Unit,
    val onWifiOnly: (Boolean) -> Unit,
    val onDeletePack: (String) -> Unit,
    val onOpenTargetPicker: () -> Unit,
    val onReopenBackgroundSetup: () -> Unit,
    val overlayControls: OverlayControls,
)
