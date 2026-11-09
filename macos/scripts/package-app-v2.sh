#!/usr/bin/env bash
# Packages VoiceTrans VI (Version 2 - Apple M4 & macOS 15/27 Edition)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MAC_DIR="$ROOT/macos"
APP_NAME="VoiceTrans VI (M4 V2)"
BUNDLE_DIR="$MAC_DIR/build/$APP_NAME.app"
BIN_SRC="$MAC_DIR/.build/arm64-apple-macosx/debug/VoiceTransMac"

echo "Building macOS binary with swift build..."
cd "$MAC_DIR"
swift build

echo "Packaging into $BUNDLE_DIR..."
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR/Contents/MacOS"
mkdir -p "$BUNDLE_DIR/Contents/Frameworks"
mkdir -p "$BUNDLE_DIR/Contents/Resources/models"

cp "$BIN_SRC" "$BUNDLE_DIR/Contents/MacOS/VoiceTransMac"
chmod +x "$BUNDLE_DIR/Contents/MacOS/VoiceTransMac"

# Copy M4 V2 dylibs to Frameworks
if [ -d "$MAC_DIR/Engines/bin-m4" ]; then
    echo "Copying M4 V2 engine dynamic libraries to Contents/Frameworks..."
    cp -a "$MAC_DIR/Engines/bin-m4/"*.dylib* "$BUNDLE_DIR/Contents/Frameworks/" 2>/dev/null || true
fi

cat << 'EOF' > "$BUNDLE_DIR/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>VoiceTransMac</string>
    <key>CFBundleIdentifier</key>
    <string>io.github.thanhngochoang.voicetrans.mac.v2</string>
    <key>CFBundleName</key>
    <string>VoiceTrans VI (M4 V2)</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>2.0.0</string>
    <key>CFBundleVersion</key>
    <string>2</string>
    <key>LSMinimumSystemVersion</key>
    <string>15.0</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSAudioCaptureUsageDescription</key>
    <string>VoiceTrans V2 cần quyền thu âm thanh đang phát trên macOS để dịch trực tiếp qua Metal Tensor Engine.</string>
</dict>
</plist>
EOF

# Copy shared models
if [ -d "$ROOT/app/src/main/assets/models" ]; then
    echo "Copying shared models from assets..."
    cp -R "$ROOT/app/src/main/assets/models/"* "$BUNDLE_DIR/Contents/Resources/models/"
fi

# Ad-hoc code sign
echo "Signing V2 application bundle..."
codesign --force --deep --sign - "$BUNDLE_DIR"

echo "Success! VoiceTrans VI V2 (Apple M4 / macOS 15/27) packaged at: $BUNDLE_DIR"
