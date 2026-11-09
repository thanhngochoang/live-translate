package io.github.thanhngochoang.voicetrans.feature.translate.api

import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPromptBuilderTest {
    @Test
    fun `buildPrompt without context contains user utterance and assistant turn`() {
        val prompt = ContextPromptBuilder.buildPrompt(
            text = "Hello world",
            from = SourceLang.EN,
            context = emptyList()
        )

        assertTrue(prompt.contains(ContextPromptBuilder.SYSTEM_PROMPT))
        assertTrue(prompt.contains("EN: Hello world"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `buildPrompt with context includes previous pairs`() {
        val context = listOf(
            UtteranceContext(source = "First sentence", translation = "Câu đầu tiên", sourceLang = SourceLang.EN),
            UtteranceContext(source = "你好", translation = "Xin chào", sourceLang = SourceLang.ZH)
        )

        val prompt = ContextPromptBuilder.buildPrompt(
            text = "Next sentence",
            from = SourceLang.EN,
            context = context
        )

        assertTrue(prompt.contains("- EN: First sentence\n  VI: Câu đầu tiên"))
        assertTrue(prompt.contains("- ZH: 你好\n  VI: Xin chào"))
        assertTrue(prompt.contains("Translate this utterance into Vietnamese:\nEN: Next sentence"))
    }
}
