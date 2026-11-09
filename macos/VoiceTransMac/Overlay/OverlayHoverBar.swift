import SwiftUI
import VoiceTransCore

public struct OverlayHoverBar: View {
    @Binding var isLocked: Bool
    @Binding var mode: OverlayMode
    @Binding var textSize: CGFloat
    let onHide: () -> Void

    public init(
        isLocked: Binding<Bool>,
        mode: Binding<OverlayMode>,
        textSize: Binding<CGFloat>,
        onHide: @escaping () -> Void
    ) {
        self._isLocked = isLocked
        self._mode = mode
        self._textSize = textSize
        self.onHide = onHide
    }

    public var body: some View {
        HStack(spacing: 8) {
            // Lock toggle
            Button {
                isLocked.toggle()
            } label: {
                Image(systemName: isLocked ? "lock.fill" : "lock.open.fill")
                    .foregroundColor(.white)
            }
            .buttonStyle(.plain)
            .help(isLocked ? "Mở khóa cảm ứng" : "Khóa cảm ứng (Click xuyên qua)")

            Divider()
                .frame(height: 12)
                .background(Color.white.opacity(0.3))

            // Mode switcher
            Button {
                cycleMode()
            } label: {
                Text(modeLabel)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundColor(.white)
            }
            .buttonStyle(.plain)
            .help("Chuyển chế độ: Song ngữ / Chỉ dịch / Chỉ gốc")

            Divider()
                .frame(height: 12)
                .background(Color.white.opacity(0.3))

            // Font size A- / A+
            Button {
                textSize = max(14, textSize - 2)
            } label: {
                Text("A−")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.white)
            }
            .buttonStyle(.plain)

            Button {
                textSize = min(48, textSize + 2)
            } label: {
                Text("A+")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.white)
            }
            .buttonStyle(.plain)

            Divider()
                .frame(height: 12)
                .background(Color.white.opacity(0.3))

            // Hide button
            Button(action: onHide) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundColor(.white.opacity(0.8))
            }
            .buttonStyle(.plain)
            .help("Ẩn phụ đề")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Color.black.opacity(0.75))
        .clipShape(Capsule())
        .overlay(Capsule().stroke(Color.white.opacity(0.2), lineWidth: 0.5))
    }

    private var modeLabel: String {
        switch mode {
        case .bilingual: return "Song ngữ"
        case .translationOnly: return "Chỉ dịch"
        case .sourceOnly: return "Chỉ gốc"
        }
    }

    private func cycleMode() {
        switch mode {
        case .bilingual: mode = .translationOnly
        case .translationOnly: mode = .sourceOnly
        case .sourceOnly: mode = .bilingual
        }
    }
}
