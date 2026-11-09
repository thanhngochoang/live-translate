package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** App-level state for the gate screens: engine status, first-run flag and the background-permission status. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val translator: Translator,
) : ViewModel() {

    private val background = MutableStateFlow(BackgroundStatus(false, false, false))

    val uiState: StateFlow<AppUiState> = combine(
        translator.state, settings.backgroundSetupDone, background,
    ) { engineState, bgDone, bg ->
        AppUiState(
            engineState = engineState,
            backgroundSetupDone = bgDone,
            background = bg,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        AppUiState(engineState = EngineState.Loading, backgroundSetupDone = false, background = background.value),
    )

    fun onResume(status: BackgroundStatus) { background.value = status }

    fun continueFromBackgroundSetup() {
        viewModelScope.launch { settings.setBackgroundSetupDone(true) }
    }
}
