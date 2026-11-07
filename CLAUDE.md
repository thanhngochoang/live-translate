# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

VoiceTrans VI: an offline Android app that listens to English or Mandarin speech, transcribes it, and translates it into Vietnamese. **No code exists yet.** The repo contains only `README.md`, which is the implementation spec (written in Vietnamese). Read it in full before you implement anything, especially the "Quyết định đã chốt" (settled decisions) and "Câu hỏi mở" (open questions, §11) sections. Ask the user about any open question that a task depends on: TTS in the MVP, conversation history, app name, package name, and minSdk.

## Settled decisions (do not revisit)

- Target devices are Samsung phones with Google Play Services. **ML Kit Translation is the translation engine.** The ONNX/OPUS-MT fallback is phase 2 and is not needed for the MVP.
- The app is distributed as a local APK (adb or sideload), not through Google Play. The ASR and VAD models are **bundled in the APK assets**, and the 200 MB base-APK limit and Play Asset Delivery do not apply.
- Build only for `arm64-v8a`.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

The pipeline runs on its own coroutine dispatcher, never on the main thread:

`AudioRecord` (16 kHz mono PCM16, about 100 ms chunks, `VOICE_RECOGNITION` source, converted to float [-1, 1]) → Silero VAD → sherpa-onnx streaming Zipformer bilingual zh-en (int8) → `LanguageDetector` → `Translator` (ML Kit) → Compose subtitle UI

Key rules that span modules:
- **Only `AsrEvent.Final` is translated.** `Partial` results are only displayed, in grey at the end of the list.
- Final sentences go into one sequential translation queue (a `Channel`), so translations never run in parallel.
- Every layer talks through an interface (`Translator`, `LanguageDetector`, and later `Speaker` for TTS), so engines can be swapped without touching the UI. The reference signatures for `AsrEvent`, `SourceLang`, `Translator` and `Utterance` are in README §4.
- Language detection is a heuristic: if 30% or more of the characters are CJK (`一-鿿`), the sentence is ZH; otherwise it is EN.
- Create native engines once, reuse them, and release them in `onDestroy`. ASR uses `numThreads = 2`.
- `TranslationSession` runs inside a Foreground Service with `foregroundServiceType="microphone"`. It must be started while the app is in the foreground (an Android 14 rule). Its notification has a Stop button and shows the latest translation.

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
