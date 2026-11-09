import AppKit
import SwiftUI
import VoiceTransCore

@MainActor
public final class SubtitlePanelController: ObservableObject {
    public static let shared = SubtitlePanelController()

    private var panel: SubtitlePanel?
    private var hostingView: NSHostingView<AnyView>?

    // Observable states
    @Published public var blocks: [SubtitleBlock] = []
    @Published public var partial: String? = nil
    @Published public var captureWarning: String? = nil

    @Published public var mode: OverlayMode = .bilingual
    @Published public var lineOrder: LineOrder = .sourceTop
    @Published public var isLocked: Bool = false {
        didSet { panel?.setLocked(isLocked) }
    }
    @Published public var isHidden: Bool = false {
        didSet { updateVisibility() }
    }
    @Published public var textSize: CGFloat = 26.0
    @Published public var bgOpacity: Double = 0.45

    private init() {
        setupPanel()
    }

    public func setupPanel() {
        guard let screen = NSScreen.main else { return }

        let screenFrame = screen.visibleFrame
        let geometry = PanelGeometry()
        let width = geometry.targetWidth(forScreenWidth: screenFrame.width)
        let height: CGFloat = 120.0
        let x = screenFrame.origin.x + (screenFrame.width - width) / 2.0
        let y = screenFrame.origin.y + 50.0

        let contentRect = NSRect(x: x, y: y, width: width, height: height)
        let panel = SubtitlePanel(contentRect: contentRect)

        let rootView = makeView()
        let hostingView = NSHostingView(rootView: AnyView(rootView))
        hostingView.autoresizingMask = [.width, .height]
        panel.contentView = hostingView

        self.panel = panel
        self.hostingView = hostingView
    }

    public func show() {
        isHidden = false
        refreshView()
        panel?.orderFrontRegardless()
    }

    public func hide() {
        isHidden = true
    }

    public func update(blocks: [SubtitleBlock], partial: String? = nil, captureWarning: String? = nil) {
        self.blocks = blocks
        self.partial = partial
        self.captureWarning = captureWarning
        refreshView()
    }

    private func updateVisibility() {
        if isHidden {
            panel?.orderOut(nil)
        } else {
            panel?.orderFrontRegardless()
        }
    }

    private func refreshView() {
        hostingView?.rootView = AnyView(makeView())
    }

    private func makeView() -> some View {
        BilingualSubtitleView(
            blocks: blocks,
            partial: partial,
            captureWarning: captureWarning,
            mode: Binding(get: { self.mode }, set: { self.mode = $0; self.refreshView() }),
            lineOrder: Binding(get: { self.lineOrder }, set: { self.lineOrder = $0; self.refreshView() }),
            isLocked: Binding(get: { self.isLocked }, set: { self.isLocked = $0; self.refreshView() }),
            textSize: Binding(get: { self.textSize }, set: { self.textSize = $0; self.refreshView() }),
            bgOpacity: Binding(get: { self.bgOpacity }, set: { self.bgOpacity = $0; self.refreshView() }),
            onHide: { [weak self] in self?.hide() }
        )
    }
}
