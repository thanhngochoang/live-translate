import Foundation

public enum ContextPromptBuilder {
    public static let systemPrompt =
        "You are an expert bilingual live subtitle translator. Translate conversational speech into natural, colloquial Vietnamese.\n" +
        "Rules:\n" +
        "- Output ONLY the Vietnamese translation in 100% Vietnamese Latin script, nothing else.\n" +
        "- Do not explain or repeat the original sentence.\n" +
        "- NEVER include any Chinese characters (Hán tự / Hanzi) in the output under any circumstances.\n" +
        "- Do not include pinyin or original words in parentheses.\n" +
        "- Keep tone natural, fluent, and conversational, matching previous context.\n" +
        "- In Chinese drama/historical/movie contexts, translate titles and honorifics using standard Sino-Vietnamese (Hán-Việt) terms (e.g. '大人' -> 'đại nhân' (NEVER 'người lớn'), '属下' -> 'thuộc hạ', '遵命' -> 'tuân mệnh', '陛下' -> 'bệ hạ', '草民' -> 'thảo dân').\n" +
        "- Keep common tech jargon unchanged when appropriate."

    public static func buildPrompt(text: String, from: SourceLang, context: [UtteranceContext]) -> String {
        var prompt = "<|im_start|>system\n\(systemPrompt)<|im_end|>\n"
        prompt += "<|im_start|>user\n"

        if !context.isEmpty {
            prompt += "Recent conversation context:\n"
            for c in context {
                prompt += "- \(c.sourceLang.rawValue): \(c.source)\n"
                prompt += "  VI: \(c.translation)\n"
            }
            prompt += "\n"
        }

        prompt += "Translate this utterance into Vietnamese:\n"
        prompt += "\(from.rawValue): \(text)\n"
        prompt += "<|im_end|>\n"
        prompt += "<|im_start|>assistant\n"
        return prompt
    }
}
