package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.Closeable
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
        @ApplicationContext context: Context,
        @Named("nmt") dispatcher: CoroutineDispatcher,
    ): NmtTranslator {
        return NmtTranslator(
            engines = {
                val assets = context.assets
                runCatching { System.loadLibrary("onnxruntime") }
                val env = OrtEnvironment.getEnvironment()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }

                // NLLB (English)
                val nllbMeta = NmtMeta.parse(assets.open("models/nmt/nllb-600m/meta.json").bufferedReader().readText())
                val nllbEnc = AssetModelLoader.session(env, assets, "models/nmt/nllb-600m/encoder_model.onnx", opts)
                val nllbDec = AssetModelLoader.session(env, assets, "models/nmt/nllb-600m/decoder_model_merged.onnx", opts)
                val nllbEngine = Seq2SeqEngine(nllbEnc, nllbDec, nllbMeta, env)
                val nllbTok = NllbTokenizer(assets.open("models/nmt/nllb-600m/sentencepiece.bpe.model"), nllbMeta)

                // OPUS (Chinese)
                val opusMeta = NmtMeta.parse(assets.open("models/nmt/opus-zh-vi/meta.json").bufferedReader().readText())
                val opusEnc = AssetModelLoader.session(env, assets, "models/nmt/opus-zh-vi/encoder_model.onnx", opts)
                val opusDec = AssetModelLoader.session(env, assets, "models/nmt/opus-zh-vi/decoder_model_merged.onnx", opts)
                val opusEngine = Seq2SeqEngine(opusEnc, opusDec, opusMeta, env)
                val opusTok = MarianTokenizer(
                    assets.open("models/nmt/opus-zh-vi/source.spm"),
                    assets.open("models/nmt/opus-zh-vi/target.spm"),
                    assets.open("models/nmt/opus-zh-vi/vocab.json"),
                    opusMeta,
                )

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
