package io.github.thanhngochoang.voicetrans.feature.ui.live

import io.github.thanhngochoang.voicetrans.feature.session.displayCase
import io.github.thanhngochoang.voicetrans.feature.translate.api.SourceLang
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveFormatTest {
    @Test fun wallClock_isFormattedInTheGivenZone_notUtc() {
        val instant = Instant.parse("2026-11-07T04:27:00Z").toEpochMilli()
        assertEquals("11:27", formatWallClock(instant, ZoneId.of("Asia/Ho_Chi_Minh")))
        assertEquals("04:27", formatWallClock(instant, ZoneId.of("UTC")))
    }

    @Test fun displayCase_sentenceCasesAllCapsEnglish() {
        assertEquals("Talk about the weather.", displayCase("TALK ABOUT THE WEATHER.", SourceLang.EN))
        assertEquals("Good morning everyone. How are you? Fine!", displayCase("GOOD MORNING EVERYONE. HOW ARE YOU? FINE!", SourceLang.EN))
    }

    @Test fun displayCase_fixesStandalonePronounI() {
        assertEquals("Today I think I'm late and I'll go", displayCase("TODAY I THINK I'M LATE AND I'LL GO", SourceLang.EN))
        assertEquals("I am here", displayCase("I AM HERE", SourceLang.EN))
        assertEquals("This is it", displayCase("THIS IS IT", SourceLang.EN))
    }

    @Test fun displayCase_leavesChineseAlone() = assertEquals("大家早上好", displayCase("大家早上好", SourceLang.ZH))

    @Test fun displayCase_keepsAlreadyCasedText() {
        assertEquals("Good Morning iPhone", displayCase("Good Morning iPhone", SourceLang.EN))
    }

    @Test fun displayCase_handlesEmptyAndSpaces() {
        assertEquals("", displayCase("", SourceLang.EN))
        assertEquals("  ", displayCase("  ", SourceLang.EN))
    }
}
