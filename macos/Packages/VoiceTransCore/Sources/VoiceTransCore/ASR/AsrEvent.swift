import Foundation

public enum AsrEvent: Sendable, Equatable {
    case partial(text: String)
    case `final`(text: String, startMs: Int64, endMs: Int64)
}
