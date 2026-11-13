import Foundation

public final class ClassTimeline: @unchecked Sendable {
    private let windowMs: Int64
    private static let retainMs: Int64 = 60_000
    private var windows: [(startMs: Int64, cls: AudioClass)] = []
    private let lock = NSLock()

    public init(windowMs: Int64 = 1_000) {
        self.windowMs = windowMs
    }

    public func add(startMs: Int64, cls: AudioClass) {
        lock.lock()
        defer { lock.unlock() }

        windows.append((startMs, cls))
        let cutoff = startMs - Self.retainMs
        windows.removeAll { $0.startMs < cutoff }
    }

    public func majority(startMs: Int64, endMs: Int64) -> AudioClass {
        lock.lock()
        defer { lock.unlock() }

        let minStart = startMs - windowMs + 1
        let relevant = windows.filter { $0.startMs >= minStart && $0.startMs < endMs }
        if relevant.isEmpty {
            return .other
        }

        var counts: [AudioClass: Int] = [:]
        for item in relevant {
            counts[item.cls, default: 0] += 1
        }

        guard let maxCount = counts.values.max() else {
            return .other
        }

        let top = counts.filter { $0.value == maxCount }.map { $0.key }
        if top.contains(.speech) {
            return .speech
        }
        return top.first ?? .other
    }
}
