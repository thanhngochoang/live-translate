package io.github.thanhngochoang.voicetrans.feature.ui.language

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.nl.translate.TranslateLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.PackManager
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LanguagePickerViewModel @Inject constructor(
    private val packManager: PackManager,
    settings: SettingsRepository,
) : ViewModel() {
    private val picker = TargetPicker(packManager, settings, viewModelScope)
    private val items = orderedTargetLanguages(TranslateLanguage.getAllLanguages(), Locale.getDefault())

    data class UiState(val rows: List<LanguageRow>, val pendingName: String?, val currentName: String)

    val uiState: StateFlow<UiState> = combine(packManager.packs, settings.targetLanguage, picker.pending) { packs, target, pending ->
        UiState(
            rows = items.map { LanguageRow(it, packs[it.code] ?: ModelState.NotDownloaded, it.code == target) },
            pendingName = pending?.let { name(it) },
            currentName = name(target),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(emptyList(), null, ""))

    init { viewModelScope.launch { packManager.refreshPacks() } }

    private fun name(code: String) = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())

    fun pick(code: String) = picker.pick(code)
}
