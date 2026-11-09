import Foundation

public struct CjkRatioLanguageDetector: LanguageDetector {
    public init() {}

    public func detect(text: String) -> SourceLang {
        var nonWhitespaceCount = 0
        var cjkCount = 0

        for scalar in text.unicodeScalars {
            if !CharacterSet.whitespacesAndNewlines.contains(scalar) {
                nonWhitespaceCount += 1
                // CJK Unified Ideographs range: 0x4E00...0x9FFF
                if scalar.value >= 0x4E00 && scalar.value <= 0x9FFF {
                    cjkCount += 1
                }
            }
        }

        if nonWhitespaceCount == 0 {
            return .en
        }

        let ratio = Float(cjkCount) / Float(nonWhitespaceCount)
        return ratio >= 0.30 ? .zh : .en
    }
}
