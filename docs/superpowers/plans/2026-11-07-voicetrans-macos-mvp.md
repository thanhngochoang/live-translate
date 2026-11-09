# VoiceTrans VI for macOS (Swift) MVP Implementation Plan

> **Layout:** Monorepo. Lives in `macos/` in this repository, alongside `app/` (Android). No separate branch. Reuses models and test assets from `app/src/main/assets/models/` and `app/src/androidTest/assets/`.
> **Proposed committed path:** `docs/superpowers/plans/2026-11-07-voicetrans-macos-mvp.md` (committed by coordinator on `main` per `AGENTS.md`).

**Goal:** A native macOS menu-bar app that captures EN/ZH speech **playing on the Mac** (browser videos, players, meetings, podcasts), transcribes it offline, translates it (Vietnamese default), and renders a **floating bilingual subtitle panel** (original line + translated line) above every application, including full-screen video.

**Architecture:** Core Audio process tap (ScreenCaptureKit fallback) → 16 kHz mono float → Silero VAD gate → sherpa-onnx streaming Zipformer zh-en (int8) → `AudioClassifier` gate (skip singing/music) → `LanguageDetector` (CJK ratio) → sequential translation queue → `Translator` (OPUS-MT ONNX on macOS 14; Apple Translation on macOS 15+) → `TranslationSession` (`@MainActor @Observable`) → bilingual `NSPanel` overlay + Menu Bar extra.

---

## Environment & Toolchain Status (on this machine)

Checked 2026-11-07:
- **macOS:** `14.4.1` (Darwin 23.4.0), Apple M1, 32 GB RAM.
- **Developer tools:** `Apple Swift version 5.10` via `/Library/Developer/CommandLineTools`.
  - `swift build` works.
  - `swift test` / `xcodebuild` fail with `XCTest not available` because **Xcode.app is not installed** (only Command Line Tools).
- **Prerequisite to build the `.app` bundle and run tests:** Install **Xcode** (Xcode 15.4 for macOS 14.x, or upgrade macOS to 15+ and install latest Xcode).
- **Pure Swift logic in `macos/Packages/VoiceTransCore`** can be scaffolded and built immediately with `swift build`.

---

## Settled Decisions

1. **Repo layout:** Monorepo. `macos/` directory in the project root. No separate git branch.
2. **Audio source:** Internal playback only, never the microphone. Core Audio process tap (`AudioHardwareCreateProcessTap`, macOS 14.2+) is primary; ScreenCaptureKit `SCStream` is fallback.
3. **Bilingual floating subtitle:** Always displays **both** the original line (with `EN`/`ZH` badge) and the translated line. Supports 3 display modes (Bilingual, Translation-only, Source-only) and interchangeable line order.
4. **Window level:** `NSPanel` at `.screenSaver` level with `.fullScreenAuxiliary` collection behavior so it remains visible over full-screen video and across Space switches, without stealing keyboard focus.
5. **Models:** Shared with Android. Never duplicated in git. Copied into the macOS app bundle during build from `app/src/main/assets/models/`.
6. **Translation engine:** Protocol-based `Translator`.
   - On macOS 14.x: **OPUS-MT ONNX** (`opus-mt-en-vi`, `opus-mt-zh-vi`/pivot).
   - On macOS 15+: **Apple Translation** framework (`TranslationSession`).
7. **Pipeline parameters:** Identical to Android MVP (endpoint rule2 = 0.6 s, rule3 = 12 s, CJK threshold ≥ 0.30, classifier thresholds match `ClassThresholds`).

---

## Directory Structure

```
macos/
├── project.yml                          # XcodeGen project spec (generates VoiceTransMac.xcodeproj)
├── VoiceTransMac/                       # App target (AppKit + SwiftUI)
│   ├── VoiceTransMacApp.swift          # @main, MenuBarExtra, WindowGroup
│   ├── Info.plist                       # NSAudioCaptureUsageDescription, LSUIElement=YES
│   ├── VoiceTransMac.entitlements
│   ├── Audio/
│   │   ├── SystemAudioSource.swift     # AudioSource protocol
│   │   ├── ProcessTapSource.swift      # Core Audio tap
│   │   └── ScreenCaptureSource.swift   # SCStream fallback
│   ├── Engines/
│   │   ├── SherpaAsrEngine.swift       # Zipformer streaming ASR
│   │   └── SherpaAudioClassifier.swift # Audio tagging (speech vs music/singing)
│   ├── Translate/
│   │   ├── AppleTranslator.swift       # macOS 15+ on-device Translation framework
│   │   ├── OpusMtTranslator.swift      # ONNX Runtime OPUS-MT
│   │   └── LanguagePackManager.swift
│   ├── Session/
│   │   ├── TranslationSession.swift    # @MainActor @Observable coordinator
│   │   └── PipelineActor.swift         # Dedicated background actor for audio/ASR
│   ├── Overlay/
│   │   ├── SubtitlePanel.swift         # NSPanel configuration (.screenSaver level)
│   │   ├── SubtitlePanelController.swift
│   │   ├── BilingualSubtitleView.swift # SwiftUI bilingual HUD
│   │   └── OverlayHoverBar.swift       # Quick controls on hover (lock, mode, size)
│   └── UI/
│       ├── MenuBarContent.swift        # Menu bar dropdown
│       ├── TranscriptWindow.swift      # History viewer + Copy + .srt/.txt export
│       ├── SettingsView.swift          # Preferences window
│       └── OnboardingView.swift        # Audio capture permission guide
├── Packages/
│   ├── VoiceTransCore/                  # Pure Swift package (no AppKit; logic + tests)
│   │   ├── Package.swift
│   │   ├── Sources/VoiceTransCore/
│   │   │   ├── Audio/Resampler.swift
│   │   │   ├── Audio/CaptureBlockedDetector.swift
│   │   │   ├── Translate/SourceLang.swift
│   │   │   ├── Translate/LanguageDetector.swift
│   │   │   ├── Translate/CjkRatioLanguageDetector.swift
│   │   │   ├── Translate/Translator.swift
│   │   │   ├── ASR/AsrEvent.swift
│   │   │   ├── ASR/AudioClass.swift
│   │   │   ├── ASR/ClassRules.swift
│   │   │   ├── ASR/ClassTimeline.swift
│   │   │   ├── Session/Utterance.swift
│   │   │   ├── Overlay/BilingualLines.swift
│   │   │   └── Overlay/BilingualLayout.swift
│   │   └── Tests/VoiceTransCoreTests/  # Unit tests ported 1:1 from Android JVM tests
│   └── SherpaOnnxKit/                   # Swift PM binary wrapper for sherpa-onnx macOS
│       ├── Package.swift
│       └── ...
└── scripts/
    ├── fetch-sherpa-macos.sh            # Downloads prebuilt macOS arm64 dylib/xcframework
    ├── copy-models.sh                   # Build phase to copy models from app/src/main/assets
    └── build-app.sh                     # CLI build script (xcodegen + xcodebuild)
```

---

## Detailed Task Breakdown

### Task M0: Xcode Toolchain Setup (User action)
- Install Xcode (15.4 for macOS 14, or upgrade to macOS 15+ with latest Xcode).
- Run `sudo xcode-select -s /Applications/Xcode.app`.
- Install helper CLI: `brew install xcodegen`.

### Task M1: Core Package & Scaffold
- Create `macos/Packages/VoiceTransCore` with `Package.swift`.
- Port pure types from Android:
  - `SourceLang` (`EN`, `ZH`)
  - `LanguageDetector` + `CjkRatioLanguageDetector` (ratio ≥ 0.30 CJK → ZH)
  - `Utterance` (id, kind, source, sourceLang, targetLang, translation, timestampMs, endMs)
  - `AsrEvent` (`partial(text:)`, `final(text:startMs:endMs:)`)
  - `AudioClass` (`speech`, `singing`, `music`, `other`) + `ClassRules` + `ClassTimeline`
  - `CaptureBlockedDetector` (silence detector while audio plays)
  - `Resampler` (downmix stereo to mono, resample 48 kHz → 16 kHz)
- Run `swift build` in `macos/Packages/VoiceTransCore`.

### Task M2: Floating Bilingual Subtitle Panel (Core UX)
**Bilingual Display Logic (`BilingualLines.swift`):**
- Data model `SubtitleBlock`:
  - `id: Int64`
  - `sourceText: String`
  - `sourceLang: SourceLang` (`EN` badge: `#0166F6`, `ZH` badge: `#7CB0FF`)
  - `translationText: String?` (nil while translating, shows ellipsis placeholder)
  - `isNewest: Boolean`
- Display modes:
  - `.bilingual`: Source line (muted, 65% size) + Translation line (bold, primary color).
  - `.translationOnly`: Translation line only.
  - `.sourceOnly`: Source line only.
- Line ordering: `.sourceTop` (default) or `.translationTop`.
- Capacity: shows newest 1–3 blocks (default 2); older blocks render at 70% opacity.
- Live partial: renders as italic grey text below the last block; never translated.
- Special handling:
  - Same language (source == target): shows one line.
  - Music/Singing: shows compact `♪ Đang phát nhạc`.
  - Capture blocked: shows warning banner.

**Window & Behavior (`SubtitlePanel.swift`):**
- Subclass of `NSPanel` with styles `[.borderless, .nonactivatingPanel]`.
- Level: `.screenSaver` (guaranteed above full-screen browser/video players).
- Collection behavior: `[.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]`.
- `becomesKeyOnlyIfNeeded = false`, never steals focus from active apps.
- Click-through lock mode: `ignoresMouseEvents = true`, panel opacity capped at 0.85.
- Hover overlay controls: Lock/Unlock button, mode switcher, font size adjustments (A− / A+), Hide button.
- Global hotkeys (via Carbon API):
  - `⌥⌘T`: Start / Stop session.
  - `⌥⌘H`: Show / Hide subtitle panel.
  - `⌥⌘L`: Lock / Unlock click-through.
  - `⌥⌘B`: Cycle bilingual / translation / source mode.

### Task M3: System Audio Capture Spike & Source
- Spike `AudioHardwareCreateProcessTap` vs `SCStream`:
  - Verify capture of YouTube (Safari, Chrome, Brave), Zoom, QuickTime, Spotify.
  - Verify silence behavior for DRM (Apple TV+ / Netflix).
  - Test AirPods connect/disconnect mid-stream (auto-rebuild tap on device change).
- Wrap inside `AudioSource` protocol yielding `AsyncThrowingStream<[Float], Error>` at 16 kHz mono.

### Task M4: sherpa-onnx Integration (ASR + Tagging)
- Add macOS prebuilt binary for `sherpa-onnx` (arm64, 16 KB page-aligned, C-API).
- `SherpaAsrEngine`: loads Zipformer zh-en int8 + Silero VAD from shared `app/src/main/assets/models/`.
- `SherpaAudioClassifier`: loads tagging model, applies `ClassRules` (music/singing gate).
- Measure on M1: target latency < 500 ms from end of speech to `final` event.

### Task M5: Translation Engines
- `Translator` protocol: `func translate(_ text: String, from: SourceLang, to: String) async throws -> String`.
- `AppleTranslator`: macOS 15+ implementation using system `TranslationSession`.
- `OpusMtTranslator`: macOS 14 fallback using ONNX Runtime with `opus-mt-en-vi`.
- Offline verification: verify translation works with network disconnected.

### Task M6: TranslationSession & Actor Pipeline
- `PipelineActor`: serial background actor consuming audio frames, driving VAD/ASR/Classifier.
- Sequential translation queue (`AsyncChannel` or bounded serial queue).
- `@MainActor @Observable class TranslationSession`: exposes `utterances`, `partial`, `sessionState`, `captureWarning`.

### Task M7: Menu Bar App, Transcript & Settings
- `MenuBarExtra` in menu bar with live caption icon (pulsing dot while running).
- **Transcript window:**
  - Full scrolling bilingual history.
  - Search / filter.
  - Export to `.txt` and `.srt` subtitle file (with start/end timestamps).
  - In-memory only; cleared on next session start.
- **Settings window:**
  - Subtitle styling (font size 18–56 pt, background opacity 0–100%, text shadow).
  - Target language picker.
  - Audio source selection (Process Tap vs ScreenCaptureKit).
  - Launch at login (`SMAppService`).

### Task M8: Packaging & Verification
- `scripts/build-app.sh`: generates Xcode project via XcodeGen, builds Release configuration, ad-hoc codesigns.
- End-to-end verification checklist on macOS (bilingual display, full-screen video, hotkeys, sleep/wake, display disconnect).

---

## Acceptance Criteria on macOS

1. **Bilingual overlay:** Plays an English or Chinese video on YouTube full-screen → floating panel appears on top of video, showing both English/Chinese text and Vietnamese translation simultaneously in < 800 ms.
2. **Click-through:** In locked mode, clicking on the subtitle passes clicks directly to video controls behind it.
3. **Multi-space / Full-screen:** Subtitle panel stays visible when switching Spaces or entering full-screen video.
4. **Offline operation:** Disconnecting Wi-Fi allows full transcription and translation with zero network requests.
5. **Resource usage:** CPU usage < 20% on Apple M1 during active transcription, memory < 1 GB.
