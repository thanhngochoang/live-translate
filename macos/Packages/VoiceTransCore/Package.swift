// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "VoiceTransCore",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "VoiceTransCore",
            targets: ["VoiceTransCore"]
        ),
    ],
    dependencies: [],
    targets: [
        .target(
            name: "VoiceTransCore",
            dependencies: []
        ),
        .testTarget(
            name: "VoiceTransCoreTests",
            dependencies: ["VoiceTransCore"]
        ),
    ]
)
