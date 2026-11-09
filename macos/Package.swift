// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "VoiceTransMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(name: "VoiceTransCore", targets: ["VoiceTransCore"]),
        .executable(name: "VoiceTransMac", targets: ["VoiceTransMac"]),
    ],
    dependencies: [],
    targets: [
        .target(
            name: "VoiceTransCore",
            path: "Packages/VoiceTransCore/Sources/VoiceTransCore"
        ),
        .executableTarget(
            name: "VoiceTransMac",
            dependencies: ["VoiceTransCore"],
            path: "VoiceTransMac"
        ),
    ]
)
