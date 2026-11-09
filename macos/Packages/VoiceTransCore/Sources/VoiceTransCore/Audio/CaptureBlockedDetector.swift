import Foundation

public struct PlaybackState: Sendable, Equatable {
    public let active: Bool
    public let blocksCapture: Bool

    public init(active: Bool, blocksCapture: Bool) {
        self.active = active
        self.blocksCapture = blocksCapture
    }
}

public final class CaptureBlockedDetector: @unchecked Sendable {
    private let framesNeeded: Int
    private var zeroFrames: Int = 0

    public init(thresholdMs: Int64 = 5_000, frameMs: Int64 = 100) {
        self.framesNeeded = Int(thresholdMs / frameMs)
    }

    public func onFrame(frame: [Float], playback: PlaybackState) -> Bool {
        if !playback.active || frame.contains(where: { $0 != 0 }) {
            zeroFrames = 0
            return false
        }
        zeroFrames += 1
        return zeroFrames == framesNeeded
    }

    public func reset() {
        zeroFrames = 0
    }
}
