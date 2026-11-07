package io.github.thanhngochoang.voicetrans.feature.translate.mlkit

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.PackManager
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await

fun SourceLang.mlKitCode(): String = when (this) {
    SourceLang.EN -> TranslateLanguage.ENGLISH
    SourceLang.ZH -> TranslateLanguage.CHINESE
}

/** ML Kit on-device translation. Language packs are managed per language through [RemoteModelManager]. */
@Singleton
class MlKitTranslator @Inject constructor() : Translator, PackManager {

    private val modelManager = RemoteModelManager.getInstance()
    private val clients = HashMap<Pair<String, String>, com.google.mlkit.nl.translate.Translator>()
    private val _packs = MutableStateFlow<Map<String, ModelState>>(emptyMap())

    /** Per-language pack state. A language that is missing from the map has not been checked yet (treat as NotDownloaded). */
    override val packs: StateFlow<Map<String, ModelState>> = _packs.asStateFlow()

    override fun requiredPacks(target: String): Set<String> = setOf(TranslateLanguage.ENGLISH, TranslateLanguage.CHINESE, target)

    override fun isReadyFor(target: String): Boolean = requiredPacks(target).all { _packs.value[it] == ModelState.Ready }

    override suspend fun refreshPacks() {
        val downloaded = modelManager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
        _packs.update { old ->
            TranslateLanguage.getAllLanguages().associateWith { lang ->
                when {
                    lang in downloaded -> ModelState.Ready
                    old[lang] == ModelState.Downloading -> ModelState.Downloading
                    else -> ModelState.NotDownloaded
                }
            }
        }
    }

    override suspend fun download(lang: String, wifiOnly: Boolean): Result<Unit> {
        setState(lang, ModelState.Downloading)
        return try {
            val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
            modelManager.download(TranslateRemoteModel.Builder(lang).build(), conditions).await()
            setState(lang, ModelState.Ready)
            Result.success(Unit)
        } catch (e: CancellationException) {
            setState(lang, ModelState.NotDownloaded)
            throw e
        } catch (e: Exception) {
            setState(lang, ModelState.Error(e.message ?: e.javaClass.simpleName))
            Result.failure(e)
        }
    }

    override suspend fun delete(lang: String): Result<Unit> = try {
        closeClientsFor(lang)
        modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(lang).build()).await()
        setState(lang, ModelState.NotDownloaded)
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        setState(lang, ModelState.Error(e.message ?: e.javaClass.simpleName))
        Result.failure(e)
    }

    override suspend fun translate(text: String, from: SourceLang, to: String): Result<String> {
        val source = from.mlKitCode()
        if (source == to) return Result.success(text)
        return try {
            Result.success(client(source, to).translate(text).await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Synchronized
    private fun client(source: String, target: String): com.google.mlkit.nl.translate.Translator =
        clients.getOrPut(source to target) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
            )
        }

    @Synchronized
    private fun closeClientsFor(lang: String) {
        val it = clients.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.first == lang || e.key.second == lang) { e.value.close(); it.remove() }
        }
    }

    @Synchronized
    override fun close() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    private fun setState(lang: String, state: ModelState) = _packs.update { it + (lang to state) }

}
