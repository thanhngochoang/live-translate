package io.github.thanhngochoang.voicetrans.feature.translate.hybrid

import android.util.Log
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import io.github.thanhngochoang.voicetrans.feature.translate.api.UtteranceContext
import io.github.thanhngochoang.voicetrans.feature.translate.llama.LlamaContextTranslator
import io.github.thanhngochoang.voicetrans.feature.translate.mlkit.MlKitTranslator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HybridTranslator @Inject constructor(
    private val llamaTranslator: LlamaContextTranslator,
    private val mlKitTranslator: MlKitTranslator,
) : Translator {

    override suspend fun translate(
        text: String,
        from: SourceLang,
        to: String,
        context: List<UtteranceContext>
    ): Result<String> {
        val isVietnamese = to.equals("vi", ignoreCase = true)
        if (isVietnamese) {
            val qwenResult = llamaTranslator.translate(text, from, to, context)
            if (qwenResult.isSuccess) {
                val raw = qwenResult.getOrNull()?.trim().orEmpty()
                if (raw.isNotBlank()) {
                    val clean = CjkFilter.purgeCjk(raw)
                    if (clean.any { it.isLetter() }) {
                        return Result.success(clean)
                    }
                }
            } else {
                Log.w(TAG, "Qwen translate failed, falling back to ML Kit: ${qwenResult.exceptionOrNull()?.message}")
            }
        }
        val mlResult = mlKitTranslator.translate(text, from, to, context)
        if (isVietnamese && mlResult.isSuccess) {
            val raw = mlResult.getOrNull()?.trim().orEmpty()
            val clean = CjkFilter.purgeCjk(raw)
            if (clean.any { it.isLetter() }) {
                return Result.success(clean)
            }
        }
        return mlResult
    }

    override suspend fun translate(text: String, from: SourceLang, to: String): Result<String> =
        translate(text, from, to, emptyList())

    override fun close() {
        llamaTranslator.close()
        mlKitTranslator.close()
    }

    private companion object {
        const val TAG = "HybridTranslator"
    }
}
