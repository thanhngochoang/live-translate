package io.github.thanhngochoang.voicetrans.feature.translate.llama

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaContextTranslator @Inject constructor(
    @ApplicationContext private val context: Context
) : Translator {
    override val state: StateFlow<EngineState> = MutableStateFlow<EngineState>(EngineState.Failed("Deprecated")).asStateFlow()

    override suspend fun translate(text: String, from: SourceLang, mode: TranslateMode): Result<String> =
        Result.failure(UnsupportedOperationException("Deprecated"))

    override fun close() {}
}
