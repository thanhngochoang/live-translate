import AppKit
import SwiftUI

/// Custom NSPanel optimized for always-on-top, non-intrusive floating subtitles.
public final class SubtitlePanel: NSPanel {
    public init(contentRect: NSRect) {
        super.init(
            contentRect: contentRect,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )

        // Float above full-screen apps and video players
        level = .screenSaver
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]

        // Transparent background so SwiftUI view handles rounded borders and opacity
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false

        // Never steal keyboard focus or activate the application
        hidesOnDeactivate = false
        isMovableByWindowBackground = true
    }

    public override var canBecomeKey: Bool { false }
    public override var canBecomeMain: Bool { false }

    public func setLocked(_ locked: Bool) {
        ignoresMouseEvents = locked
        alphaValue = locked ? 0.85 : 1.0
        isMovableByWindowBackground = !locked
    }
}
