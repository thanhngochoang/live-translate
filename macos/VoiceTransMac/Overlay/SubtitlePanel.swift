import AppKit
import SwiftUI

/// Custom NSPanel optimized for always-on-top, non-intrusive floating subtitles.
public final class SubtitlePanel: NSPanel {
    private static let holdToDragDelay: TimeInterval = 0.35
    private static let holdMoveTolerance: CGFloat = 6.0

    private var isLockedState = false
    private var isHoldDragging = false
    private var holdWorkItem: DispatchWorkItem?
    private var downScreenPoint: NSPoint = .zero
    private var initialPanelOrigin: NSPoint = .zero
    private var globalMouseMonitor: Any?
    private var localMouseMonitor: Any?

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

    deinit {
        removeMonitors()
    }

    public override var canBecomeKey: Bool { false }
    public override var canBecomeMain: Bool { false }

    public func setLocked(_ locked: Bool) {
        isLockedState = locked
        cancelHoldState()
        ignoresMouseEvents = locked
        alphaValue = locked ? 0.85 : 1.0
        isMovableByWindowBackground = !locked
        if locked {
            installMonitorsIfNeeded()
        } else {
            removeMonitors()
        }
    }

    private func installMonitorsIfNeeded() {
        let mask: NSEvent.EventTypeMask = [.leftMouseDown, .leftMouseDragged, .leftMouseUp]
        if globalMouseMonitor == nil {
            globalMouseMonitor = NSEvent.addGlobalMonitorForEvents(matching: mask) { [weak self] event in
                DispatchQueue.main.async {
                    self?.handleMouseEvent(event)
                }
            }
        }
        if localMouseMonitor == nil {
            localMouseMonitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
                self?.handleMouseEvent(event)
                return event
            }
        }
    }

    private func removeMonitors() {
        if let global = globalMouseMonitor {
            NSEvent.removeMonitor(global)
            globalMouseMonitor = nil
        }
        if let local = localMouseMonitor {
            NSEvent.removeMonitor(local)
            localMouseMonitor = nil
        }
    }

    private func handleMouseEvent(_ event: NSEvent) {
        guard isLockedState, isVisible else { return }
        let mouseLocation = NSEvent.mouseLocation

        switch event.type {
        case .leftMouseDown:
            guard frame.contains(mouseLocation) else { return }
            cancelHoldState()
            downScreenPoint = mouseLocation
            initialPanelOrigin = frame.origin

            let work = DispatchWorkItem { [weak self] in
                guard let self, self.isLockedState else { return }
                self.isHoldDragging = true
                self.ignoresMouseEvents = false
            }
            holdWorkItem = work
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.holdToDragDelay, execute: work)

        case .leftMouseDragged:
            if isHoldDragging {
                let dx = mouseLocation.x - downScreenPoint.x
                let dy = mouseLocation.y - downScreenPoint.y
                setFrameOrigin(NSPoint(x: initialPanelOrigin.x + dx, y: initialPanelOrigin.y + dy))
            } else if holdWorkItem != nil {
                let dx = mouseLocation.x - downScreenPoint.x
                let dy = mouseLocation.y - downScreenPoint.y
                if hypot(dx, dy) > Self.holdMoveTolerance {
                    cancelHoldState()
                }
            }

        case .leftMouseUp:
            cancelHoldState()

        default:
            break
        }
    }

    private func cancelHoldState() {
        holdWorkItem?.cancel()
        holdWorkItem = nil
        if isHoldDragging {
            isHoldDragging = false
            ignoresMouseEvents = isLockedState
        }
    }
}
