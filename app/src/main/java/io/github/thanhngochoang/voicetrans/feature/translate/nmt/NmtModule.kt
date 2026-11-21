package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.thanhngochoang.voicetrans.core.model.ModelState
import io.github.thanhngochoang.voicetrans.core.model.ModelStore
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NmtModule {

    @Provides
    @Singleton
    @Named("nmt")
    fun provideNmtDispatcher(): CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "nmt").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    @Provides
    @Singleton
    fun provideNmtTranslator(
        models: ModelStore,
        @Named("nmt") dispatcher: CoroutineDispatcher,
    ): NmtTranslator {
        return NmtTranslator(
            engines = {
                // The models are downloaded files: stay in Loading until they are all on the device.
                models.state.first { it is ModelState.Ready }
                val nllbDir = File(models.root, "nmt/nllb-600m")
                val opusDir = File(models.root, "nmt/opus-zh-vi")
                runCatching { System.loadLibrary("onnxruntime") }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }

                // NLLB (English)
                val nllbMeta = NmtMeta.parse(File(nllbDir, "meta.json").readText())
                val nllbEnc = env.createSession(File(nllbDir, "encoder_model.onnx").path, opts)
                val nllbDec = env.createSession(File(nllbDir, "decoder_model_merged.onnx").path, opts)
                val nllbEngine = Seq2SeqEngine(nllbEnc, nllbDec, nllbMeta, env)
                val nllbTok = File(nllbDir, "sentencepiece.bpe.model").inputStream().use { NllbTokenizer(it, nllbMeta) }

                // OPUS (Chinese)
                val opusMeta = NmtMeta.parse(File(opusDir, "meta.json").readText())
                val opusEnc = env.createSession(File(opusDir, "encoder_model.onnx").path, opts)
                val opusDec = env.createSession(File(opusDir, "decoder_model_merged.onnx").path, opts)
                val opusEngine = Seq2SeqEngine(opusEnc, opusDec, opusMeta, env)
                val opusTok = File(opusDir, "source.spm").inputStream().use { src ->
                    File(opusDir, "target.spm").inputStream().use { tgt ->
                        File(opusDir, "vocab.json").inputStream().use { vocab -> MarianTokenizer(src, tgt, vocab, opusMeta) }
                    }
                }

                class RealEngine(
                    private val tokenizer: SubwordTokenizer,
                    private val engine: Seq2SeqEngine,
                    private val eosId: Int,
                ) : NmtTranslator.TextEngine, Closeable {
                    override fun translate(text: String, mode: TranslateMode): String {
                        val inputIds = tokenizer.encode(text)
                        val (beams, maxNewTokens) = when (mode) {
                            TranslateMode.FINAL -> 4 to 128
                            TranslateMode.DRAFT -> 1 to 64
                        }
                        val outputIds = engine.generate(inputIds, beams, maxNewTokens)
                        val trimmed = if (outputIds.isNotEmpty() && outputIds.last() == eosId) {
                            outputIds.copyOfRange(0, outputIds.size - 1)
                        } else {
                            outputIds
                        }
                        return tokenizer.decode(trimmed)
                    }

                    override fun close() {
                        engine.close()
                    }
                }

                mapOf(
                    SourceLang.EN to RealEngine(nllbTok, nllbEngine, nllbMeta.eos.toInt()),
                    SourceLang.ZH to RealEngine(opusTok, opusEngine, opusMeta.eos.toInt()),
                )
            },
            dispatcher = dispatcher,
        )
    }
}
