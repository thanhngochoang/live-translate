package io.github.thanhngochoang.voicetrans.feature.translate.hybrid

import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import io.github.thanhngochoang.voicetrans.feature.translate.llama.LlamaContextTranslator
import io.github.thanhngochoang.voicetrans.feature.translate.mlkit.MlKitTranslator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridTranslator @Inject constructor(
    private val llamaTranslator: LlamaContextTranslator,
    private val mlKitTranslator: MlKitTranslator,
) : Translator {
    override val state: StateFlow<EngineState> = MutableStateFlow<EngineState>(EngineState.Failed("Deprecated")).asStateFlow()

    override suspend fun translate(text: String, from: SourceLang, mode: TranslateMode): Result<String> =
        Result.failure(UnsupportedOperationException("Deprecated"))

    override fun close() {
        llamaTranslator.close()
        mlKitTranslator.close()
    }
}
