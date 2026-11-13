package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import io.github.thanhngochoang.voicetrans.feature.translate.api.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

class NmtTranslator(
    private val engines: suspend () -> Map<SourceLang, TextEngine>,
    private val dispatcher: CoroutineDispatcher,
) : Translator {

    fun interface TextEngine {
        fun translate(text: String, mode: TranslateMode): String
    }

    private val _state = MutableStateFlow<EngineState>(EngineState.Loading)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var engineMap: Map<SourceLang, TextEngine>? = null
    private var isClosed = false

    fun warmUp(scope: CoroutineScope) {
        scope.launch(dispatcher) {
            try {
                val loaded = engines()
                mutex.withLock {
                    engineMap = loaded
                    _state.value = EngineState.Ready
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _state.value = EngineState.Failed(t.message ?: "Failed to load models")
            }
        }
    }

    override suspend fun translate(
        text: String,
        from: SourceLang,
        mode: TranslateMode,
    ): Result<String> = runCatching {
        val currentState = state.first { it !is EngineState.Loading }
        if (currentState is EngineState.Failed) {
            error(currentState.message)
        }

        mutex.withLock {
            if (isClosed) {
                error("Translator is closed")
            }
            val engine = engineMap?.get(from)
                ?: error("No engine for $from")

            withContext(dispatcher) {
                engine.translate(text, mode)
            }
        }
    }

    override fun close() {
        runBlocking {
            mutex.withLock {
                if (!isClosed) {
                    isClosed = true
                    engineMap?.values?.forEach {
                        (it as? Closeable)?.close()
                    }
                    engineMap = null
                }
            }
        }
    }
}
