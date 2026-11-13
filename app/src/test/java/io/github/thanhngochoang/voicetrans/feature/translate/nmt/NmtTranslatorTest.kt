package io.github.thanhngochoang.voicetrans.feature.translate.nmt

import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.api.TranslateMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class NmtTranslatorTest {

    interface CloseableTextEngine : NmtTranslator.TextEngine, Closeable

    @Test
    fun routesEnglishToNllb_chineseToOpus() = runBlocking {
        val enEngine = NmtTranslator.TextEngine { text, _ -> "EN:$text" }
        val zhEngine = NmtTranslator.TextEngine { text, _ -> "ZH:$text" }
        val translator = NmtTranslator(
            engines = { mapOf(SourceLang.EN to enEngine, SourceLang.ZH to zhEngine) },
            dispatcher = Dispatchers.Unconfined
        )
        translator.warmUp(this)

        val enRes = translator.translate("hello", SourceLang.EN)
        val zhRes = translator.translate("你好", SourceLang.ZH)

        assertEquals("EN:hello", enRes.getOrThrow())
        assertEquals("ZH:你好", zhRes.getOrThrow())
    }

    @Test
    fun finalMode_andDraftMode_passedThrough() = runBlocking {
        var lastMode: TranslateMode? = null
        val engine = NmtTranslator.TextEngine { text, mode ->
            lastMode = mode
            "$text:$mode"
        }
        val translator = NmtTranslator(
            engines = { mapOf(SourceLang.EN to engine) },
            dispatcher = Dispatchers.Unconfined
        )
        translator.warmUp(this)

        translator.translate("line", SourceLang.EN, TranslateMode.DRAFT)
        assertEquals(TranslateMode.DRAFT, lastMode)

        translator.translate("line", SourceLang.EN, TranslateMode.FINAL)
        assertEquals(TranslateMode.FINAL, lastMode)
    }

    @Test
    fun translateBeforeReady_suspendsUntilReady() = runBlocking {
        val loadLatch = CompletableDeferred<Unit>()
        val engine = NmtTranslator.TextEngine { text, _ -> "translated:$text" }
        val translator = NmtTranslator(
            engines = {
                loadLatch.await()
                mapOf(SourceLang.EN to engine)
            },
            dispatcher = Dispatchers.Default
        )
        translator.warmUp(this)

        assertEquals(EngineState.Loading, translator.state.value)

        val deferred = async {
            translator.translate("wait", SourceLang.EN)
        }

        delay(50)
        assertFalse(deferred.isCompleted)

        loadLatch.complete(Unit)

        val result = deferred.await()
        assertEquals("translated:wait", result.getOrThrow())
        assertEquals(EngineState.Ready, translator.state.value)
    }

    @Test
    fun loadFailure_setsFailed_andTranslateFails() = runBlocking {
        val translator = NmtTranslator(
            engines = { error("Model file missing") },
            dispatcher = Dispatchers.Default
        )
        translator.warmUp(this)

        // Wait until failed
        delay(50)
        assertTrue(translator.state.value is EngineState.Failed)
        assertEquals("Model file missing", (translator.state.value as EngineState.Failed).message)

        val res = translator.translate("test", SourceLang.EN)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("Model file missing") == true)
    }

    @Test
    fun closeDuringTranslate_waitsThenReleases() = runBlocking {
        val releaseCount = AtomicInteger(0)
        val inTranslateLatch = CountDownLatch(1)
        val unblockTranslateLatch = CountDownLatch(1)

        class ReleasingEngine : CloseableTextEngine {
            override fun translate(text: String, mode: TranslateMode): String {
                inTranslateLatch.countDown()
                unblockTranslateLatch.await(5, TimeUnit.SECONDS)
                return "done:$text"
            }

            override fun close() {
                releaseCount.incrementAndGet()
            }
        }

        val engine = ReleasingEngine()
        val translator = NmtTranslator(
            engines = { mapOf(SourceLang.EN to engine) },
            dispatcher = Dispatchers.Default
        )
        translator.warmUp(this)

        val translateJob = async(Dispatchers.Default) {
            translator.translate("in-flight", SourceLang.EN)
        }

        assertTrue(inTranslateLatch.await(2, TimeUnit.SECONDS))
        assertEquals(0, releaseCount.get())

        val closeJob = launch(Dispatchers.Default) {
            translator.close()
        }

        delay(50)
        // close() must be waiting for translate to finish
        assertEquals(0, releaseCount.get())

        // Now unblock translate
        unblockTranslateLatch.countDown()

        translateJob.await()
        closeJob.join()

        assertEquals(1, releaseCount.get())
    }

    @Test
    fun callsAreSerialized() = runBlocking {
        val inFlight = AtomicInteger(0)
        val overlapDetected = AtomicBoolean(false)

        val engine = NmtTranslator.TextEngine { text, _ ->
            val current = inFlight.incrementAndGet()
            if (current > 1) {
                overlapDetected.set(true)
            }
            Thread.sleep(50)
            inFlight.decrementAndGet()
            "done:$text"
        }

        val translator = NmtTranslator(
            engines = { mapOf(SourceLang.EN to engine) },
            dispatcher = Dispatchers.Default
        )
        translator.warmUp(this)

        val job1 = async { translator.translate("call1", SourceLang.EN) }
        val job2 = async { translator.translate("call2", SourceLang.EN) }

        job1.await()
        job2.await()

        assertFalse("Calls should be serialized without overlapping", overlapDetected.get())
    }
}
