package io.github.thanhngochoang.voicetrans.feature.ui.settings

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.thanhngochoang.voicetrans.core.settings.OverlaySettings
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.core.settings.ThemeMode
import io.github.thanhngochoang.voicetrans.feature.overlay.OverlayController
import io.github.thanhngochoang.voicetrans.feature.ui.AppLanguage
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus
import io.github.thanhngochoang.voicetrans.feature.ui.background.readBackgroundStatus
import io.github.thanhngochoang.voicetrans.feature.ui.currentAppLanguage
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    val overlayController: OverlayController,
) : ViewModel() {

    private val status = MutableStateFlow(Status(readBackgroundStatus(context), Settings.canDrawOverlays(context), currentAppLanguage()))

    private data class Status(val background: BackgroundStatus, val overlayPermission: Boolean, val appLanguage: AppLanguage)

    private val versionName = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    private val modelVersions = runCatching {
        val json = JSONObject(context.assets.open("models/models.json").bufferedReader().use { it.readText() })
        val ids = mutableListOf<String>()
        json.getJSONArray("archives").let { a -> for (i in 0 until a.length()) ids += a.getJSONObject(i).getString("id") }
        json.getJSONArray("files").let { a -> for (i in 0 until a.length()) ids += a.getJSONObject(i).getString("id") }
        ids
    }.getOrDefault(emptyList())

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.themeMode, settings.subtitleSizeSp, settings.overlay, settings.autoStopMinutes,
        settings.draftTranslation, status,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val theme = values[0] as ThemeMode
        val subtitleSize = values[1] as Int
        val overlay = values[2] as OverlaySettings
        val autoStop = values[3] as Int
        val draftTranslation = values[4] as Boolean
        val st = values[5] as Status
        SettingsUiState(
            appLanguage = st.appLanguage, themeMode = theme,
            subtitleSizeSp = subtitleSize, overlay = overlay, overlayPermission = st.overlayPermission,
            background = st.background, autoStopMinutes = autoStop,
            draftTranslation = draftTranslation,
            versionName = versionName, modelVersions = modelVersions,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(AppLanguage.SYSTEM, ThemeMode.DARK, 20, OverlaySettings(), false, status.value.background, 30, true, versionName, modelVersions),
    )

    fun onResume() { status.value = Status(readBackgroundStatus(context), Settings.canDrawOverlays(context), currentAppLanguage()) }

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { settings.setThemeMode(mode) } }
    fun setSubtitleSize(sp: Int) { viewModelScope.launch { settings.setSubtitleSizeSp(sp) } }
    fun updateOverlay(transform: (OverlaySettings) -> OverlaySettings) { viewModelScope.launch { settings.updateOverlay(transform) } }
    fun setAutoStop(minutes: Int) { viewModelScope.launch { settings.setAutoStopMinutes(minutes) } }
    fun setDraftTranslation(value: Boolean) { viewModelScope.launch { settings.setDraftTranslation(value) } }
}
