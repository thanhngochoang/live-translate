import Foundation

public struct UtteranceContext: Sendable, Equatable {
    public let source: String
    public let translation: String
    public let sourceLang: SourceLang

    public init(source: String, translation: String, sourceLang: SourceLang) {
        self.source = source
        self.translation = translation
        self.sourceLang = sourceLang
    }
}

public protocol Translator: Sendable {
    func translate(_ text: String, from: SourceLang, to: String) async throws -> String
    func translate(_ text: String, from: SourceLang, to: String, context: [UtteranceContext]) async throws -> String
    var supportedTargets: Set<String> { get }
}

extension Translator {
    public func translate(_ text: String, from: SourceLang, to: String, context: [UtteranceContext]) async throws -> String {
        try await translate(text, from: from, to: to)
    }
}
