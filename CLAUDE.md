# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

VoiceTrans VI: an offline Android app that listens to English or Mandarin speech, transcribes it, and translates it into Vietnamese. **No code exists yet.** The repo contains only `README.md`, which is the implementation spec (written in Vietnamese). Read it in full before you implement anything, especially the "Quyết định đã chốt" (settled decisions) and "Câu hỏi mở" (open questions, §11) sections. Ask the user about any open question that a task depends on: TTS in the MVP, conversation history, app name, package name, and minSdk.

## Settled decisions (do not revisit)

- Target devices are Samsung phones with Google Play Services. **ML Kit Translation is the translation engine.** The ONNX/OPUS-MT fallback is phase 2 and is not needed for the MVP.
- The app is distributed as a local APK (adb or sideload), not through Google Play. The ASR and VAD models are **bundled in the APK assets**, and the 200 MB base-APK limit and Play Asset Delivery do not apply.
- Build only for `arm64-v8a`.
- Additions beyond the README (decided 2026-11-07):
  - The **translation target is user-selectable** (any ML Kit language, default `vi`). Source stays EN/ZH, because the ASR model supports only those two.
  - The **UI language is switchable between Vietnamese and English** through per-app locales. All strings go in `res/values` (vi, default) and `res/values-en`.
  - **The audio source is sound playing on the phone (AudioPlaybackCapture via MediaProjection), not the microphone.** This replaces the README's `AudioRecord`/`VOICE_RECOGNITION` mic design. It needs `RECORD_AUDIO`, a fresh screen-capture consent each session, and a `mediaProjection` FGS that calls `startForeground` before `getMediaProjection`. Calls and DRM apps can't be captured.
  - The theme is **dark-first** with one accent color. The UI source of truth is `docs/design/ui-spec.md`, and the implementation plan is `docs/superpowers/plans/2026-11-07-voicetrans-android17-mvp.md` (target device: Android 17 / One UI 8.5).

## Build environment

- SDK path: `local.properties` → `sdk.dir=/Users/macbook/Documents/Projects/MINE/photo-booking/booking/.android-sdk` (gitignored). `$ANDROID_HOME` in `~/.zshrc` points to a moved folder, and `local.properties` wins.
- Toolchain: **Azul Zulu JDK 21 LTS arm64** (auto-provisioned by Gradle into `~/.gradle/jdks` via `gradle/gradle-daemon-jvm.properties`; refresh after each quarterly Java security release with `./gradlew updateDaemonJvm --jvm-version=21 --jvm-vendor=azul`), Gradle 9.6.0 (wrapper), AGP 9.5.0, build-tools 36.0.0, NDK 28.2.13676358, platform `android-37.0`. Bytecode target is 17. Run Gradle with `export JAVA_HOME=~/.gradle/jdks/azul_systems__inc_-21-aarch64-os_x.2/zulu21.54.107-ca-jdk21.0.13-macosx_aarch64/Contents/Home`. Don't use the old Oracle 17.0.2 (unpatched, x86_64) or Homebrew openjdk 23 (EOL).
- The host is an **Apple M1 (arm64)**. Instrumented tests run on the physical phone or an arm64 emulator. Only an API 35 arm64 image is installed.
- In the Claude Code sandbox, Gradle downloads fail with `UnknownHostException` because the JVM ignores the sandbox proxy. Run downloading Gradle commands with the sandbox disabled.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

The pipeline runs on its own coroutine dispatcher, never on the main thread:

Playback capture (16 kHz mono PCM16, about 100 ms chunks, converted to float [-1, 1]; originally the mic in the README) → Silero VAD → sherpa-onnx streaming Zipformer bilingual zh-en (int8) → `LanguageDetector` → `Translator` (ML Kit) → Compose subtitle UI

Key rules that span modules:
- **Only `AsrEvent.Final` is translated.** `Partial` results are only displayed, in grey at the end of the list.
- Final sentences go into one sequential translation queue (a `Channel`), so translations never run in parallel.
- Every layer talks through an interface (`Translator`, `LanguageDetector`, and later `Speaker` for TTS), so engines can be swapped without touching the UI. The reference signatures for `AsrEvent`, `SourceLang`, `Translator` and `Utterance` are in README §4.
- Language detection is a heuristic: if 30% or more of the characters are CJK (`一-鿿`), the sentence is ZH; otherwise it is EN.
- Create native engines once, reuse them, and release them in `onDestroy`. ASR uses `numThreads = 2`.
- `TranslationSession` runs inside a Foreground Service with `foregroundServiceType="mediaProjection"`. It must be started from the foreground right after the user's capture consent. Its notification has a Stop button and shows the latest translation.

Planned modules: `app/`, `core/audio`, `core/model`, `feature/asr`, `feature/translate/{api,mlkit,onnx}`, `feature/tts`, `feature/session`, `feature/ui`. The stack is Kotlin, Compose with Material 3, Hilt, Coroutines with Flow, and DataStore. Room is optional, for history.

## Models

- The `.onnx` files and `tokens.txt` go in `app/src/main/assets/models/...`, with `androidResources { noCompress += "onnx" }`. sherpa-onnx loads them directly from `AssetManager`, so do not copy them to `filesDir`.
- ML Kit packs (`ENGLISH`, `CHINESE`, `VIETNAMESE`) are downloaded once through `RemoteModelManager` on the ModelSetup screen, which exposes `Flow<ModelState>` (`NotDownloaded / Downloading / Ready / Error`). **Block the Live screen until all three packs are Ready.**
- Keep model URLs and checksums in one versioned config file and never hard-code them in several places. The model names and dependency versions in the README are placeholders, so check the latest sherpa-onnx releases and Maven before you declare them.

## Samsung / One UI

One UI kills foreground services aggressively. Request `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` when the first session starts, and tell the user to set Battery to Unrestricted and to keep the app off the Sleeping apps lists. The acceptance criteria (README §9) include 30 minutes running with the screen off, a sentence translated in under 1 s in airplane mode, at least 95% correct language badges, and no network requests during translation.

## Planned tests

- Unit tests: `LanguageDetector`, `ModelManager` (checksum and state), and the translation queue.
- Instrumented tests: the pipeline fed from sample EN and ZH WAV files, not the microphone.
