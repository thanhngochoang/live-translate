import Foundation

public enum UtteranceKind: String, Sendable, Codable {
    case speech
    case music
}

public struct Utterance: Identifiable, Sendable, Codable, Equatable {
    public let id: Int64
    public let kind: UtteranceKind
    public let source: String
    public let sourceLang: SourceLang
    public let targetLang: String
    public let translation: String?
    public let timestampMs: Int64
    public let endMs: Int64
    public let translationFailed: Bool

    public init(
        id: Int64,
        kind: UtteranceKind,
        source: String,
        sourceLang: SourceLang,
        targetLang: String,
        translation: String?,
        timestampMs: Int64,
        endMs: Int64,
        translationFailed: Bool = false
    ) {
        self.id = id
        self.kind = kind
        self.source = source
        self.sourceLang = sourceLang
        self.targetLang = targetLang
        self.translation = translation
        self.timestampMs = timestampMs
        self.endMs = endMs
        self.translationFailed = translationFailed
    }
}
