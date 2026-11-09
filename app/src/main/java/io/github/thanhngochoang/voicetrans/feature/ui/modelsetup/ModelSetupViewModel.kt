package io.github.thanhngochoang.voicetrans.feature.ui.modelsetup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.PackManager
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
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

/** App-level state for the gate screens: engine status, first-run flag and the background-permission status. */
@HiltViewModel
class ModelSetupViewModel @Inject constructor(
    private val packManager: PackManager,
    private val settings: SettingsRepository,
    private val translator: Translator,
) : ViewModel() {

    private val background = MutableStateFlow(BackgroundStatus(false, false, false))

    val uiState: StateFlow<AppUiState> = combine(
        translator.state, settings.backgroundSetupDone, settings.wifiOnlyDownload, background,
    ) { engineState, bgDone, wifiOnly, bg ->
        AppUiState(
            engineState = engineState,
            backgroundSetupDone = bgDone,
            background = bg,
            wifiOnly = wifiOnly,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        AppUiState(engineState = EngineState.Loading, backgroundSetupDone = false, background = background.value, wifiOnly = true),
    )

    init {
        viewModelScope.launch { packManager.refreshPacks() }
    }

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
