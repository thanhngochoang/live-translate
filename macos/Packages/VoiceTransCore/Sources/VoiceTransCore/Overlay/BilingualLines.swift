import Foundation

public enum OverlayMode: String, Sendable, Codable, CaseIterable {
    case bilingual = "Bilingual"
    case translationOnly = "TranslationOnly"
    case sourceOnly = "SourceOnly"
}

public enum LineOrder: String, Sendable, Codable, CaseIterable {
    case sourceTop = "SourceTop"
    case translationTop = "TranslationTop"
}

public struct SubtitleBlock: Identifiable, Sendable, Equatable {
    public let id: Int64
    public let kind: UtteranceKind
    public let sourceText: String
    public let sourceLang: SourceLang
    public let targetLang: String
    public let translationText: String?
    public let isNewest: Bool
    public let failed: Bool

    public init(
        id: Int64,
        kind: UtteranceKind,
        sourceText: String,
        sourceLang: SourceLang,
        targetLang: String,
        translationText: String?,
        isNewest: Bool,
        failed: Bool
    ) {
        self.id = id
        self.kind = kind
        self.sourceText = sourceText
        self.sourceLang = sourceLang
        self.targetLang = targetLang
        self.translationText = translationText
        self.isNewest = isNewest
        self.failed = failed
    }
}

public enum BilingualLines {
    /// Formats recent utterances into subtitle blocks according to display mode and capacity.
    public static func blocks(
        utterances: [Utterance],
        partial: String? = nil,
        mode: OverlayMode = .bilingual,
        maxBlocks: Int = 2
    ) -> [SubtitleBlock] {
        let capacity = max(1, maxBlocks)
        let slice = utterances.suffix(capacity)

        guard let lastUtterance = slice.last else {
            return []
        }

        return slice.map { u in
            SubtitleBlock(
                id: u.id,
                kind: u.kind,
                sourceText: u.source,
                sourceLang: u.sourceLang,
                targetLang: u.targetLang,
                translationText: u.translation,
                isNewest: u.id == lastUtterance.id,
                failed: u.translationFailed
            )
        }
    }
}
