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
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.PackManager
import io.github.thanhngochoang.voicetrans.feature.ui.AppLanguage
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus
import io.github.thanhngochoang.voicetrans.feature.ui.background.readBackgroundStatus
import io.github.thanhngochoang.voicetrans.feature.ui.currentAppLanguage
import java.util.Locale
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
    private val packManager: PackManager,
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
        settings.targetLanguage, settings.themeMode, settings.subtitleSizeSp, settings.overlay, settings.autoStopMinutes,
        settings.wifiOnlyDownload, settings.draftTranslation, packManager.packs, status,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val target = values[0] as String
        val packs = values[7] as Map<String, ModelState>
        val st = values[8] as Status
        val required = packManager.requiredPacks(target)
        SettingsUiState(
            appLanguage = st.appLanguage, targetName = displayName(target), themeMode = values[1] as ThemeMode,
            subtitleSizeSp = values[2] as Int, overlay = values[3] as OverlaySettings, overlayPermission = st.overlayPermission,
            background = st.background, autoStopMinutes = values[4] as Int, wifiOnly = values[5] as Boolean,
            draftTranslation = values[6] as Boolean,
            packs = packs.filterValues { it == ModelState.Ready }.keys.sorted().map { PackItem(it, displayName(it), ModelState.Ready, it in required) },
            versionName = versionName, modelVersions = modelVersions,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(AppLanguage.SYSTEM, "", ThemeMode.DARK, 20, OverlaySettings(), false, status.value.background, 30, true, true, emptyList(), versionName, modelVersions),
    )

    init { viewModelScope.launch { packManager.refreshPacks() } }

    private fun displayName(code: String) = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())

    fun onResume() { status.value = Status(readBackgroundStatus(context), Settings.canDrawOverlays(context), currentAppLanguage()) }

    fun setTheme(mode: ThemeMode) { viewModelScope.launch { settings.setThemeMode(mode) } }
    fun setSubtitleSize(sp: Int) { viewModelScope.launch { settings.setSubtitleSizeSp(sp) } }
    fun updateOverlay(transform: (OverlaySettings) -> OverlaySettings) { viewModelScope.launch { settings.updateOverlay(transform) } }
    fun setAutoStop(minutes: Int) { viewModelScope.launch { settings.setAutoStopMinutes(minutes) } }
    fun setWifiOnly(value: Boolean) { viewModelScope.launch { settings.setWifiOnlyDownload(value) } }
    fun setDraftTranslation(value: Boolean) { viewModelScope.launch { settings.setDraftTranslation(value) } }
    fun deletePack(code: String) { viewModelScope.launch { packManager.delete(code) } }
}
