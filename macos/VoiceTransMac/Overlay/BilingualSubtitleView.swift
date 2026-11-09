import SwiftUI
import VoiceTransCore

public struct BilingualSubtitleView: View {
    public let blocks: [SubtitleBlock]
    public let partial: String?
    public let captureWarning: String?

    @Binding public var mode: OverlayMode
    @Binding public var lineOrder: LineOrder
    @Binding public var isLocked: Bool
    @Binding public var textSize: CGFloat
    @Binding public var bgOpacity: Double

    public let onHide: () -> Void

    @State private var isHovering: Bool = false

    public init(
        blocks: [SubtitleBlock],
        partial: String? = nil,
        captureWarning: String? = nil,
        mode: Binding<OverlayMode>,
        lineOrder: Binding<LineOrder>,
        isLocked: Binding<Bool>,
        textSize: Binding<CGFloat>,
        bgOpacity: Binding<Double>,
        onHide: @escaping () -> Void
    ) {
        self.blocks = blocks
        self.partial = partial
        self.captureWarning = captureWarning
        self._mode = mode
        self._lineOrder = lineOrder
        self._isLocked = isLocked
        self._textSize = textSize
        self._bgOpacity = bgOpacity
        self.onHide = onHide
    }

    public var body: some View {
        ZStack(alignment: .topTrailing) {
            // Main Content Area
            VStack(alignment: .leading, spacing: 10) {
                if let warning = captureWarning {
                    warningBanner(text: warning)
                }

                if blocks.isEmpty && partial == nil && captureWarning == nil {
                    sampleOrEmptyState
                } else {
                    ForEach(blocks) { block in
                        renderBlock(block)
                    }

                    if let partialText = partial, !partialText.isEmpty {
                        renderPartial(partialText)
                    }
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.black.opacity(bgOpacity))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isLocked ? Color.white.opacity(0.1) : Color.white.opacity(0.2), lineWidth: 1)
            )

            // Hover Controls Bar (hidden when locked to allow click-through)
            if isHovering && !isLocked {
                OverlayHoverBar(
                    isLocked: $isLocked,
                    mode: $mode,
                    textSize: $textSize,
                    onHide: onHide
                )
                .padding(8)
                .transition(.opacity)
            }
        }
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.15)) {
                isHovering = hovering
            }
        }
    }

    @ViewBuilder
    private func renderBlock(_ block: SubtitleBlock) -> some View {
        if block.kind == .music {
            HStack(spacing: 6) {
                Text("♪")
                    .font(.system(size: textSize * 0.85, weight: .bold))
                    .foregroundColor(Color(red: 0.77, green: 0.66, blue: 1.0))
                Text("Đang phát nhạc")
                    .font(.system(size: textSize * 0.75, weight: .medium))
                    .foregroundColor(Color(red: 0.61, green: 0.65, blue: 0.75))
            }
            .padding(.vertical, 2)
        } else {
            VStack(alignment: .leading, spacing: 3) {
                if lineOrder == .sourceTop {
                    if mode != .translationOnly {
                        sourceLine(block: block)
                    }
                    if mode != .sourceOnly {
                        translationLine(block: block)
                    }
                } else {
                    if mode != .sourceOnly {
                        translationLine(block: block)
                    }
                    if mode != .translationOnly {
                        sourceLine(block: block)
                    }
                }
            }
            .opacity(block.isNewest ? 1.0 : 0.7)
        }
    }

    @ViewBuilder
    private func sourceLine(block: SubtitleBlock) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text(block.sourceLang.rawValue)
                .font(.system(size: max(9, textSize * 0.45), weight: .bold))
                .foregroundColor(.white)
                .padding(.horizontal, 5)
                .padding(.vertical, 1)
                .background(
                    RoundedRectangle(cornerRadius: 3)
                        .fill(block.sourceLang == .en ? Color(red: 0.0, green: 0.4, blue: 0.96) : Color(red: 0.49, green: 0.69, blue: 1.0))
                )

            Text(block.sourceText)
                .font(.system(size: textSize * 0.65, weight: .regular))
                .foregroundColor(Color(red: 0.61, green: 0.65, blue: 0.75))
                .shadow(color: .black.opacity(0.8), radius: 2, x: 0, y: 1)
        }
    }

    @ViewBuilder
    private func translationLine(block: SubtitleBlock) -> some View {
        if let translation = block.translationText {
            Text(translation)
                .font(.system(size: textSize, weight: .medium))
                .foregroundColor(Color(red: 0.91, green: 0.93, blue: 0.96))
                .shadow(color: .black.opacity(0.9), radius: 3, x: 0, y: 1.5)
        } else if block.failed {
            Text("⚠ Lỗi dịch")
                .font(.system(size: textSize * 0.8, weight: .regular))
                .foregroundColor(Color(red: 1.0, green: 0.54, blue: 0.54))
        } else {
            Text("…")
                .font(.system(size: textSize, weight: .bold))
                .foregroundColor(Color(red: 0.61, green: 0.65, blue: 0.75))
        }
    }

    @ViewBuilder
    private func renderPartial(_ partialText: String) -> some View {
        Text(partialText)
            .font(.system(size: textSize * 0.65, weight: .regular).italic())
            .foregroundColor(Color(red: 0.61, green: 0.65, blue: 0.75).opacity(0.8))
            .shadow(color: .black.opacity(0.8), radius: 2, x: 0, y: 1)
    }

    @ViewBuilder
    private func warningBanner(text: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(Color(red: 1.0, green: 0.54, blue: 0.54))
            Text(text)
                .font(.system(size: textSize * 0.7, weight: .medium))
                .foregroundColor(Color(red: 1.0, green: 0.8, blue: 0.8))
        }
        .padding(8)
        .background(Color(red: 0.16, green: 0.04, blue: 0.05))
        .cornerRadius(6)
    }

    private var sampleOrEmptyState: some View {
        HStack(spacing: 8) {
            Image(systemName: "captions.bubble.fill")
                .foregroundColor(Color(red: 0.49, green: 0.69, blue: 1.0))
            Text("Đang chờ âm thanh hội thoại…")
                .font(.system(size: textSize * 0.75, weight: .medium))
                .foregroundColor(Color(red: 0.61, green: 0.65, blue: 0.75))
        }
        .padding(.vertical, 4)
    }
}
