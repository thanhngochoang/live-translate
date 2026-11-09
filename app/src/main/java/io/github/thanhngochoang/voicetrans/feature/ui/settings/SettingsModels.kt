package io.github.thanhngochoang.voicetrans.feature.ui.settings

import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.ui.AppLanguage
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus

/** What the Settings screens ask of the floating subtitle (implemented by OverlayController). */
interface OverlayControls {
    fun setSetupMode(active: Boolean)

    /** While the opacity slider is dragged the box shows the real opacity instead of the setup opacity. */
    fun setOpacityDragging(active: Boolean)
}

data class SettingsUiState(
    val appLanguage: AppLanguage,
    val themeMode: ThemeMode,
    val subtitleSizeSp: Int,
    val overlay: OverlaySettings,
    val overlayPermission: Boolean,
    val background: BackgroundStatus,
    val autoStopMinutes: Int,
    val draftTranslation: Boolean = true,
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
    val onDraftTranslation: (Boolean) -> Unit = {},
    val onReopenBackgroundSetup: () -> Unit,
    val overlayControls: OverlayControls,
)
