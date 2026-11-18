import SwiftUI
import VoiceTransCore

public struct MenuBarContent: View {
    @ObservedObject private var overlay = SubtitlePanelController.shared
    @State private var isRunning: Bool = false

    public init() {}

    public var body: some View {
        VStack {
            Button(isRunning ? "Dừng phiên dịch" : "Bắt đầu nghe & dịch") {
                isRunning.toggle()
                if isRunning {
                    overlay.show()
                }
            }
            .keyboardShortcut("t", modifiers: [.option, .command])

            Divider()

            Button(overlay.isHidden ? "Hiện phụ đề nổi" : "Ẩn phụ đề nổi") {
                if overlay.isHidden {
                    overlay.show()
                } else {
                    overlay.hide()
                }
            }
            .keyboardShortcut("h", modifiers: [.option, .command])

            Button(overlay.isLocked ? "Mở khóa cảm ứng" : "Khóa cảm ứng (Click xuyên qua)") {
                overlay.isLocked.toggle()
            }
            .keyboardShortcut("l", modifiers: [.option, .command])

            Menu("Chế độ hiển thị") {
                Button("Song ngữ (Gốc + Dịch)") {
                    overlay.mode = .bilingual
                }
                Button("Chỉ bản dịch") {
                    overlay.mode = .translationOnly
                }
                Button("Chỉ câu gốc") {
                    overlay.mode = .sourceOnly
                }
            }

            Menu("Thứ tự dòng") {
                Button("Câu gốc ở trên") {
                    overlay.lineOrder = .sourceTop
                }
                Button("Bản dịch ở trên") {
                    overlay.lineOrder = .translationTop
                }
            }

            Menu("Cỡ chữ (\(Int(overlay.textSize)) px)") {
                Button("Tăng cỡ chữ (+2)") {
                    overlay.textSize = min(48, overlay.textSize + 2)
                }
                Button("Giảm cỡ chữ (-2)") {
                    overlay.textSize = max(14, overlay.textSize - 2)
                }
                Button("Mặc định (26 px)") {
                    overlay.textSize = 26
                }
            }

            Divider()

            Text("VoiceTrans VI • V2 (M4 / OS 27 Ready)")
                .font(.caption2)
                .foregroundStyle(.secondary)

            Button("Thoát VoiceTrans VI") {
                NSApplication.shared.terminate(nil)
            }
            .keyboardShortcut("q", modifiers: .command)
        }
    }
}
