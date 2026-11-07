package io.github.thanhngochoang.voicetrans.feature.ui.modelsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.PackManager
import io.github.thanhngochoang.voicetrans.feature.ui.AppUiState
import io.github.thanhngochoang.voicetrans.feature.ui.PackRow
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** App-level state for the gate screens: pack status, first-run flag and the background-permission status. */
@HiltViewModel
class ModelSetupViewModel @Inject constructor(
    private val packManager: PackManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val background = MutableStateFlow(BackgroundStatus(false, false, false))

    val uiState: StateFlow<AppUiState> = combine(
        packManager.packs, settings.targetLanguage, settings.backgroundSetupDone, settings.wifiOnlyDownload, background,
    ) { packs, target, bgDone, wifiOnly, bg ->
        val rows = packManager.requiredPacks(target).sortedBy { order(it, target) }.map { lang ->
            PackRow(lang, Locale.forLanguageTag(lang).getDisplayName(Locale.getDefault()), packs[lang] ?: ModelState.NotDownloaded)
        }
        AppUiState(ready = rows.all { it.state == ModelState.Ready }, packs = rows, backgroundSetupDone = bgDone, background = bg, wifiOnly = wifiOnly)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        AppUiState(false, emptyList(), false, background.value, true),
    )

    init {
        viewModelScope.launch { packManager.refreshPacks() }
    }

    private fun order(lang: String, target: String) = when (lang) { "en" -> 0; "zh" -> 1; target -> 2; else -> 3 }

    fun onResume(status: BackgroundStatus) { background.value = status }

    fun download() {
        viewModelScope.launch {
            val target = settings.targetLanguage.first()
            val wifiOnly = settings.wifiOnlyDownload.first()
            for (lang in packManager.requiredPacks(target)) {
                if (packManager.packs.value[lang] != ModelState.Ready) packManager.download(lang, wifiOnly)
            }
        }
    }

    fun continueFromBackgroundSetup() {
        viewModelScope.launch { settings.setBackgroundSetupDone(true) }
    }
}
