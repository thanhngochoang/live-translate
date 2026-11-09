import Foundation

public protocol LanguageDetector: Sendable {
    func detect(text: String) -> SourceLang
}
