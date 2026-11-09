package io.github.thanhngochoang.voicetrans.feature.translate.llama

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.thanhngochoang.voicetrans.feature.translate.api.CjkFilter
import io.github.thanhngochoang.voicetrans.feature.translate.api.ContextPromptBuilder
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import io.github.thanhngochoang.voicetrans.feature.translate.api.UtteranceContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaContextTranslator @Inject constructor(
    @ApplicationContext private val context: Context
) : Translator {
    private val bridge = LlamaBridge()
    private var nativeHandle: Long = 0L
    private var initialized = false

    private suspend fun ensureInitialized(): Boolean = withContext(Dispatchers.IO) {
        if (initialized && nativeHandle != 0L) return@withContext true
        val modelFile = getOrExtractModelFile() ?: return@withContext false
        try {
            nativeHandle = bridge.nativeInit(modelFile.absolutePath, 1024, 0)
            initialized = nativeHandle != 0L
            initialized
        } catch (t: Throwable) {
            android.util.Log.e("LlamaContextTranslator", "nativeInit failed", t)
            initialized = false
            false
        }
    }

    private fun getOrExtractModelFile(): File? {
        val dest = File(context.filesDir, "models/qwen2.5-0.5b-instruct-q4_k_m.gguf")
        if (dest.exists() && dest.length() > 0) return dest

        return try {
            dest.parentFile?.mkdirs()
            context.assets.open("models/llm/qwen2.5-0.5b-instruct-q4_k_m.gguf").use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun translate(
        text: String,
        from: SourceLang,
        to: String,
        context: List<UtteranceContext>
    ): Result<String> = withContext(Dispatchers.Default) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext Result.success("")

        if (!ensureInitialized()) {
            return@withContext Result.failure(IllegalStateException("LlamaContextTranslator: model could not be initialized"))
        }

        val prompt = ContextPromptBuilder.buildPrompt(
            text = trimmed,
            from = from,
            context = context
        )

        try {
            val output = bridge.nativeGenerate(nativeHandle, prompt, 96)?.trim()
            if (output != null) {
                val clean = CjkFilter.purgeCjk(output)
                if (clean.any { it.isLetter() } && !CjkFilter.hasCjk(clean)) {
                    Result.success(clean)
                } else {
                    Result.failure(IllegalStateException("Qwen output contained only Chinese text"))
                }
            } else {
                Result.failure(IllegalStateException("Inference returned null"))
            }
        } catch (t: Throwable) {
            android.util.Log.e("LlamaContextTranslator", "nativeGenerate failed", t)
            Result.failure(t)
        }
    }

    override suspend fun translate(text: String, from: SourceLang, to: String): Result<String> {
        return translate(text, from, to, emptyList())
    }

    override fun close() {
        if (nativeHandle != 0L) {
            try {
                bridge.nativeDestroy(nativeHandle)
            } catch (t: Throwable) {
                android.util.Log.w("LlamaContextTranslator", "nativeDestroy failed", t)
            }
            nativeHandle = 0L
            initialized = false
        }
    }
}
