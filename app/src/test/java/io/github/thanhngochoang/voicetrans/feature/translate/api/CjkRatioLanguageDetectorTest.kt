package io.github.thanhngochoang.voicetrans.feature.translate.api

import org.junit.Assert.assertEquals
import org.junit.Test

class CjkRatioLanguageDetectorTest {
    private val d: LanguageDetector = CjkRatioLanguageDetector()

    @Test fun english() = assertEquals(SourceLang.EN, d.detect("good morning everyone"))
    @Test fun chinese() = assertEquals(SourceLang.ZH, d.detect("大家早上好"))
    @Test fun mixedMostlyChinese() = assertEquals(SourceLang.ZH, d.detect("我们明天开meeting"))
    @Test fun mixedMostlyEnglish() = assertEquals(SourceLang.EN, d.detect("let's go to the 公司 now please"))
    @Test fun blank() = assertEquals(SourceLang.EN, d.detect("   "))
}
