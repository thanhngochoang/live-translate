package io.github.thanhngochoang.voicetrans.feature.translate.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkFilterTest {

    @Test
    fun testHasCjk() {
        assertTrue(CjkFilter.hasCjk("你好"))
        assertTrue(CjkFilter.hasCjk("Hello 你好"))
        assertTrue(CjkFilter.hasCjk("Triệu 大人"))
        assertTrue(CjkFilter.hasCjk("（大人）"))
        assertFalse(CjkFilter.hasCjk("Xin chào các bạn"))
        assertFalse(CjkFilter.hasCjk("Hello, how are you?"))
    }

    @Test
    fun testPurgeCjkWithDirectTerms() {
        assertEquals("đại nhân", CjkFilter.purgeCjk("大人"))
        assertEquals("Triệu đại nhân", CjkFilter.purgeCjk("Triệu 大人"))
        assertEquals("hoàng thượng vạn tuế", CjkFilter.purgeCjk("皇上万岁"))
    }

    @Test
    fun testPurgeCjkWithParentheses() {
        assertEquals("đại nhân", CjkFilter.purgeCjk("đại nhân (大人)"))
        assertEquals("đại nhân", CjkFilter.purgeCjk("đại nhân（大人）"))
        assertEquals("công tử", CjkFilter.purgeCjk("công tử [公子]"))
    }

    @Test
    fun testPurgeCjkRemainingHanzi() {
        // Unknown Hanzi should be completely stripped
        val input = "Xin chào 这是一个测试 bạn"
        val output = CjkFilter.purgeCjk(input)
        assertFalse(CjkFilter.hasCjk(output))
        assertEquals("Xin chào bạn", output)
    }

    @Test
    fun testPurgeCjkPunctuation() {
        val input = "Xin chào，bạn có khoẻ không？"
        val output = CjkFilter.purgeCjk(input)
        assertEquals("Xin chào, bạn có khoẻ không?", output.replace(" ,", ",").replace(",b", ", b"))
        assertFalse(CjkFilter.hasCjk(output))
    }
}
