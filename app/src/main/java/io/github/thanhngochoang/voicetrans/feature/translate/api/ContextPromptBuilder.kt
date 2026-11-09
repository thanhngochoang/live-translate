package io.github.thanhngochoang.voicetrans.feature.translate.api

object ContextPromptBuilder {
    const val SYSTEM_PROMPT =
        "You are an expert bilingual live subtitle translator. Translate conversational speech into natural, colloquial Vietnamese.\n" +
        "Rules:\n" +
        "- Output ONLY the Vietnamese translation, nothing else.\n" +
        "- Do not explain or repeat the original sentence.\n" +
        "- Keep tone natural, fluent, and conversational, matching previous context.\n" +
        "- Keep common tech jargon unchanged when appropriate."

    fun buildPrompt(text: String, from: SourceLang, context: List<UtteranceContext>): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n").append(SYSTEM_PROMPT).append("<|im_end|>\n")
        sb.append("<|im_start|>user\n")
        if (context.isNotEmpty()) {
            sb.append("Recent conversation context:\n")
            for (c in context) {
                sb.append("- ").append(c.sourceLang.name).append(": ").append(c.source).append("\n")
                sb.append("  VI: ").append(c.translation).append("\n")
            }
            sb.append("\n")
        }
        sb.append("Translate this utterance into Vietnamese:\n")
        sb.append(from.name).append(": ").append(text).append("\n")
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
