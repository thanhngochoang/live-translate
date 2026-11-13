#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MAC_DIR="$ROOT/macos"
APP_NAME="VoiceTrans VI"
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

# Copy dylibs to Frameworks
if [ -d "$MAC_DIR/Engines/bin" ]; then
    echo "Copying engine dynamic libraries to Contents/Frameworks..."
    cp -a "$MAC_DIR/Engines/bin/"*.dylib "$BUNDLE_DIR/Contents/Frameworks/" 2>/dev/null || true
fi

cat << 'EOF' > "$BUNDLE_DIR/Contents/Info.plist"
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key>
    <string>VoiceTransMac</string>
    <key>CFBundleIdentifier</key>
    <string>io.github.thanhngochoang.voicetrans.mac</string>
    <key>CFBundleName</key>
    <string>VoiceTrans VI</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>1.0</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>LSUIElement</key>
    <true/>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSAudioCaptureUsageDescription</key>
    <string>VoiceTrans cần quyền thu âm thanh đang phát trên máy để dịch trực tiếp.</string>
</dict>
</plist>
EOF

# Copy shared models
if [ -d "$ROOT/app/src/main/assets/models" ]; then
    echo "Copying shared models from Android assets..."
    cp -R "$ROOT/app/src/main/assets/models/"* "$BUNDLE_DIR/Contents/Resources/models/"
fi

# Ad-hoc code sign
echo "Signing application bundle..."
codesign --force --deep --sign - "$BUNDLE_DIR"

echo "Success! Application packaged at: $BUNDLE_DIR"
