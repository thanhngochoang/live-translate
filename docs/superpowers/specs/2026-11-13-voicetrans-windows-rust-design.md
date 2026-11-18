# VoiceTrans VI for Windows (Rust) — Design Specification

**Date:** 2026-11-13  
**Targets:** Windows 10/11 (x64, `x86_64-pc-windows-msvc`), plus cross-platform consistency update on Android (`app/`) and macOS (`macos/`).

## 1. Goals

1. Build **VoiceTrans VI** in **Rust** for Windows (`windows/` in this monorepo), sharing bundled models from `app/src/main/assets/models/`.
2. **Offline pipeline:** WASAPI Loopback system audio capture → 16 kHz mono float → Silero VAD → Audio Tagging (`ced-tiny`) → SenseVoice ASR (`sherpa-onnx` / ONNX Runtime) → `LanguageDetector` → sequential translation queue → local Translator (ONNX / `llama.cpp`).
3. **Multi-monitor floating subtitle overlay:**
   - Free dragging across all monitors with Per-Monitor V2 DPI awareness (`DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2`).
   - Remembers relative position per monitor and clamps/falls back cleanly if a monitor is disconnected.
   - Quick monitor switcher in the overlay hover bar and system tray menu.
4. **Press-and-hold to move in ANY mode (Windows, macOS, and Android):**
   - **Unlocked mode:** Drag immediately on press + move.
   - **Locked (click-through) mode:** Quick clicks pass through to the underlying video/application; pressing and holding (`≥ 350 ms` on desktop, `500 ms` on Android) transitions into dragging the floating subtitle window anywhere, then restores lock state on release.

## 2. Workspace Structure

```text
windows/
├── Cargo.toml                         # Workspace root
└── crates/
    ├── voicetrans-core/               # Pure Rust domain logic + unit tests
    │   ├── Cargo.toml
    │   └── src/
    │       ├── lib.rs
    │       ├── audio.rs               # Resampler, CaptureBlockedDetector
    │       ├── asr.rs                 # AsrEvent, AudioClass, ClassRules, ClassTimeline
    │       ├── translate.rs           # SourceLang, CjkRatioLanguageDetector, Translator trait
    │       ├── session.rs             # Utterance, sequential TranslationQueue
    │       └── overlay.rs             # SubtitleBlock, OverlayMode, LineOrder, MonitorBounds
    └── voicetrans-win/                # Windows desktop application
        ├── Cargo.toml
        └── src/
            ├── main.rs                # Entry point, Per-Monitor V2 DPI setup, event loop
            ├── config.rs              # Persistent JSON config (%APPDATA%\VoiceTrans\config.json)
            ├── audio_wasapi.rs        # WASAPI loopback capture + device change recovery
            ├── pipeline.rs            # Silero VAD + SenseVoice + Translator worker
            ├── monitors.rs            # EnumDisplayMonitors, DPI helpers, monitor snap/clamp
            ├── hold_drag_hook.rs      # WH_MOUSE_LL press-and-hold drag when WS_EX_TRANSPARENT
            ├── overlay_window.rs      # eframe/egui transparent topmost non-activating overlay
            └── tray.rs                # tray-icon + global-hotkey integration
```

## 3. Multi-Monitor & Press-and-Hold Behavior

- **Window flags:** `WS_EX_TOPMOST | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_LAYERED`, with `WS_EX_TRANSPARENT` toggled by `is_locked`.
- **Monitor awareness:** `monitors.rs` enumerates active displays via `EnumDisplayMonitors`, tracks `(monitor_device_name, norm_x, norm_y)`, and clamps coordinates into the nearest valid monitor work area if display topology changes.
- **Lock-mode press-and-hold (`hold_drag_hook.rs`):**
  - Installs a `WH_MOUSE_LL` hook only while `is_locked == true` and overlay is visible.
  - On `WM_LBUTTONDOWN` inside the overlay `RECT`, records start time and cursor position without swallowing the click.
  - If cursor stays within `6 px` for `350 ms`, removes `WS_EX_TRANSPARENT` temporarily, enters `HoldDragging`, moves the overlay window smoothly across monitors via `SetWindowPos`, and restores `WS_EX_TRANSPARENT` on `WM_LBUTTONUP`.
  - If the button is released or dragged > `6 px` before `350 ms`, the timer cancels so normal clicks/drags pass through untouched to the underlying app.

## 4. CI/CD (`.github/workflows/build.yml`)

Automated builds on push/PR to `main` and `workflow_dispatch`:
- `android`: JDK 21 (Zulu) + Android SDK + `./gradlew testDebugUnitTest assembleDebug` → uploads `app-debug.apk`.
- `macos`: `swift test` (`VoiceTransCore`) + `swift build -c release` (`VoiceTransMac`) + `.app` packaging → uploads `VoiceTrans-macOS.zip`.
- `windows`: `cargo test --workspace` + `cargo build --release --workspace` → uploads `VoiceTrans.exe` + shared `models/`.
