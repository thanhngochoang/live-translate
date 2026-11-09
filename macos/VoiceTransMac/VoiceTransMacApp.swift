import SwiftUI
import AppKit
import VoiceTransCore

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Run as a lightweight accessory menu bar app
        NSApplication.shared.setActivationPolicy(.accessory)

        // Show the bilingual subtitle panel on launch
        Task { @MainActor in
            SubtitlePanelController.shared.show()
        }
    }
}

@main
struct VoiceTransMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        MenuBarExtra("VoiceTrans VI", systemImage: "captions.bubble.fill") {
            MenuBarContent()
        }
    }
}
