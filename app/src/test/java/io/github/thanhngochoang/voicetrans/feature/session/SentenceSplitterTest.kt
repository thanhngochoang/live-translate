package io.github.thanhngochoang.voicetrans.feature.session

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {
    @Test fun english() = assertEquals(listOf("Hi there.", "How are you?"), splitSentences("Hi there. How are you?"))
    @Test fun chinese() = assertEquals(listOf("你好。", "你吃饭了吗？"), splitSentences("你好。你吃饭了吗？"))
    @Test fun noPunctuation_isOneSentence() = assertEquals(listOf("so we go"), splitSentences("so we go"))
    @Test fun decimalsAndEllipsis_notSplit() = assertEquals(listOf("It costs 3.5 dollars... maybe."), splitSentences("It costs 3.5 dollars... maybe."))
    @Test fun punctuationOnlyFragment_dropped() = assertEquals(listOf("OK."), splitSentences("OK. !"))
}
