import Foundation

public struct PanelGeometry: Sendable, Equatable {
    public let width: CGFloat
    public let minWidth: CGFloat
    public let maxWidth: CGFloat
    public let maxHeightFraction: CGFloat

    public init(
        width: CGFloat = 720,
        minWidth: CGFloat = 480,
        maxWidth: CGFloat = 1100,
        maxHeightFraction: CGFloat = 0.35
    ) {
        self.width = width
        self.minWidth = minWidth
        self.maxWidth = maxWidth
        self.maxHeightFraction = maxHeightFraction
    }

    /// Calculates ideal width clamped within bounds given screen width.
    public func targetWidth(forScreenWidth screenWidth: CGFloat) -> CGFloat {
        let desired = screenWidth * 0.60
        return min(max(desired, minWidth), min(maxWidth, screenWidth - 32))
    }
}

public enum BilingualLayout {
    /// Drops older blocks if total height exceeds max allowable height, always keeping the newest translation block.
    public static func fitBlocks(
        blocks: [SubtitleBlock],
        blockHeights: [CGFloat],
        maxHeight: CGFloat
    ) -> [SubtitleBlock] {
        guard blocks.count == blockHeights.count, !blocks.isEmpty else {
            return blocks
        }

        var totalHeight: CGFloat = blockHeights.reduce(0, +)
        if totalHeight <= maxHeight {
            return blocks
        }

        var resultBlocks = blocks
        var resultHeights = blockHeights

        // Always keep the last item (newest)
        while resultBlocks.count > 1 && totalHeight > maxHeight {
            totalHeight -= resultHeights.removeFirst()
            resultBlocks.removeFirst()
        }

        return resultBlocks
    }
}
