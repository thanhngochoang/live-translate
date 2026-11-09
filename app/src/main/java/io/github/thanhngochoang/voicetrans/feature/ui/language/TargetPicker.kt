package io.github.thanhngochoang.voicetrans.feature.ui.language

import io.github.thanhngochoang.voicetrans.core.settings.SettingsRepository
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.PackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Choosing an undownloaded language downloads it first; the old target stays active until the new pack is Ready. */
class TargetPicker(
    private val packs: PackManager,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _pending = MutableStateFlow<String?>(null)

    /** The language being downloaded, or null. */
    val pending: StateFlow<String?> = _pending

    fun pick(code: String) {
        scope.launch {
            if (packs.packs.value[code] == ModelState.Ready) {
                _pending.value = null
                settings.setTargetLanguage(code)
                return@launch
            }
            _pending.value = code
            val result = packs.download(code, settings.wifiOnlyDownload.first())
            if (_pending.value == code) { // the user may have picked something else meanwhile
                if (result.isSuccess) settings.setTargetLanguage(code)
                _pending.value = null
            }
        }
    }
}
