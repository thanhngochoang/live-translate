import Foundation

public enum AudioClass: String, Sendable, Codable, CaseIterable {
    case speech = "SPEECH"
    case singing = "SINGING"
    case music = "MUSIC"
    case other = "OTHER"
}

public enum ClassThresholds {
    public static let speechStrong: Float = 0.50
    public static let singingMin: Float = 0.05
    public static let speechMin: Float = 0.20
    public static let musicMin: Float = 0.40
    public static let musicMaxSpeech: Float = 0.30
}
