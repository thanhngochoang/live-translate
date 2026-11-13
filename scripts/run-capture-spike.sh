#!/usr/bin/env bash
# Plan Task 1 Step 6 helper: install the debug APK, launch the debug CaptureSpikeActivity, wait for the
# 10 s recording, then pull spike.wav and spike.txt into ./spike-out/.
#
# A human must tap "Start now" in the screen-capture consent dialog and have audio playing (YouTube etc).
# Usage: scripts/run-capture-spike.sh [-s SERIAL] [--mic] [--rate 16000] [--ch 1] [--no-install] [--label NAME]
#   --mic       FGS type mediaProjection|microphone instead of mediaProjection alone
#   --rate/--ch audio format to request (default 16000 / 1; try 48000 / 2 if rejected)
#   --label     output file prefix, e.g. youtube, chrome, brave (default: capture)
# Env: ADB (default: $ANDROID_SDK_ROOT/platform-tools/adb or sdk.dir from local.properties, else PATH adb)
# Take the device lock first if another agent may use the device (AGENTS.md).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=io.github.thanhngochoang.voicetrans
SERIAL=""; MIC=false; RATE=16000; CH=1; INSTALL=1; LABEL=capture
while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2;;
    --mic) MIC=true; shift;;
    --rate) RATE="$2"; shift 2;;
    --ch) CH="$2"; shift 2;;
    --no-install) INSTALL=0; shift;;
    --label) LABEL="$2"; shift 2;;
    *) echo "unknown arg $1" >&2; exit 2;;
  esac
done
if [ -z "${ADB:-}" ]; then
  SDK="$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null || true)"
  if [ -n "$SDK" ] && [ -x "$SDK/platform-tools/adb" ]; then ADB="$SDK/platform-tools/adb"; else ADB=adb; fi
fi
A=("$ADB"); [ -n "$SERIAL" ] && A+=(-s "$SERIAL")

if [ "$INSTALL" = 1 ]; then
  ( cd "$ROOT" && ./gradlew :app:assembleDebug )
  "${A[@]}" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi
"${A[@]}" logcat -c
"${A[@]}" shell am start -n "$PKG/.CaptureSpikeActivity" --ez auto true --ez mic "$MIC" --ei rate "$RATE" --ei ch "$CH"
echo ">>> Tap 'Start now' in the consent dialog, with audio playing. Recording is 10 s."
sleep 4
for _ in $(seq 1 60); do
  if "${A[@]}" shell run-as "$PKG" ls files/spike.txt >/dev/null 2>&1; then break; fi
  sleep 1
done
sleep 12
OUT="$ROOT/spike-out"; mkdir -p "$OUT"
"${A[@]}" exec-out run-as "$PKG" cat files/spike.wav > "$OUT/$LABEL.wav"
"${A[@]}" exec-out run-as "$PKG" cat files/spike.txt > "$OUT/$LABEL.txt"
echo "--- $OUT/$LABEL.txt"; cat "$OUT/$LABEL.txt"
echo "--- wav: $(wc -c < "$OUT/$LABEL.wav") bytes at $OUT/$LABEL.wav"
echo "--- privacy-dot check: look at the status bar for a green MIC dot (should NOT appear; a camera/screen-share chip is expected)."
