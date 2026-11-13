package io.github.thanhngochoang.voicetrans.feature.translate.api

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

sealed interface EngineState {
    data object Loading : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

interface Translator : Closeable {
    val state: StateFlow<EngineState>

    suspend fun translate(
        text: String,
        from: SourceLang,
        mode: TranslateMode = TranslateMode.FINAL,
    ): Result<String>
}
