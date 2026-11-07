package io.github.thanhngochoang.voicetrans.feature.translate

import io.github.thanhngochoang.voicetrans.feature.translate.api.ModelState
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import io.github.thanhngochoang.voicetrans.feature.translate.mlkit.MlKitTranslator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Needs Google Play services. The first run needs network to download packs; later runs work in airplane mode. */
class MlKitTranslatorTest {
    private lateinit var translator: MlKitTranslator

    @Before fun setUp() { translator = MlKitTranslator() }

    @After fun tearDown() = translator.close()

    private suspend fun ensure(vararg langs: String) {
        translator.refreshPacks()
        for (l in langs) if (translator.packs.value[l] != ModelState.Ready) {
            assertTrue("download $l: ", translator.download(l, wifiOnly = false).isSuccess)
        }
    }

    @Test fun download_thenReady() = runBlocking {
        assertTrue(translator.download("vi", wifiOnly = false).isSuccess)
        assertEquals(ModelState.Ready, translator.packs.value["vi"])
    }

    @Test fun translatesEnglishToVietnamese() = runBlocking {
        ensure("en", "vi")
        val r = translator.translate("good morning", SourceLang.EN, "vi")
        assertTrue("$r", r.isSuccess && Regex("[à-ỹđ]").containsMatchIn(r.getOrThrow()))
    }

    @Test fun translatesChineseToVietnamese() = runBlocking {
        ensure("zh", "vi")
        val r = translator.translate("早上好", SourceLang.ZH, "vi")
        assertTrue("$r", r.isSuccess && Regex("[à-ỹđ]").containsMatchIn(r.getOrThrow()))
    }

    @Test fun translatesEnglishToJapanese() = runBlocking {
        ensure("en", "ja")
        val r = translator.translate("good morning", SourceLang.EN, "ja")
        assertTrue("$r", r.isSuccess && Regex("[぀-ヿ一-鿿]").containsMatchIn(r.getOrThrow()))
    }

    @Test fun sameLanguage_passthrough() = runBlocking {
        assertEquals("hello", translator.translate("hello", SourceLang.EN, "en").getOrThrow())
    }

    @Test fun requiredPacks_includesTarget() {
        assertEquals(setOf("en", "zh", "ja"), translator.requiredPacks("ja"))
    }
}
