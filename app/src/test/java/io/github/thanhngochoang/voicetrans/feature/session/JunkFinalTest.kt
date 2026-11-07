package io.github.thanhngochoang.voicetrans.feature.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JunkFinalTest {
    @Test fun singleCjkCharacter_isJunk() = assertTrue(isJunkFinal("哥", 0, 2000))
    @Test fun twoCjkCharacters_areNotJunk() = assertFalse(isJunkFinal("你好", 0, 1000))
    @Test fun singleShortEnglishToken_isJunk() {
        assertTrue(isJunkFinal("A", 0, 2000))
        assertTrue(isJunkFinal("UH", 0, 2000))
    }
    @Test fun singleThreeLetterWord_isNotJunk() = assertFalse(isJunkFinal("YES", 0, 1000))
    @Test fun twoEnglishTokens_areNotJunk() = assertFalse(isJunkFinal("OK GO", 0, 1000))
    @Test fun shortAudioSpan_isJunk() = assertTrue(isJunkFinal("GOOD MORNING", 0, 499))
    @Test fun spanOf500ms_isNotJunk() = assertFalse(isJunkFinal("GOOD MORNING", 0, 500))
}
