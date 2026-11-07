# VoiceTrans VI MVP (Android 17 / One UI 8.5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the README's MVP as an APK that runs correctly on a Samsung phone with Android 17 (API 37) and One UI 8.5. It captures EN/ZH speech **playing on the phone** (videos; not the microphone), transcribes it offline, skips music and songs, and shows a translation (Vietnamese by default, any ML Kit language selectable) in under 1 s. The app is dark-first and keeps running in the background. Translations show in the app, in a floating subtitle over other apps (adjustable opacity, size and position), and in the notification. The UI is Vietnamese/English.

**Architecture:** The user grants screen-capture consent (MediaProjection) from a visible activity, which starts a `mediaProjection` foreground service that owns one `TranslationSession`. The session pipes **audio playing on the phone** (`PlaybackCaptureSource`, AudioPlaybackCapture; no microphone) through `AsrEngine` (Silero VAD gate plus sherpa-onnx streaming Zipformer zh-en), with an `AudioClassifier` in parallel that marks music and singing so they are not translated, then `LanguageDetector`, then a sequential `Channel` into `MlKitTranslator`, and exposes `StateFlow<List<Utterance>>`. Compose screens and the floating subtitle only observe that flow.

**Tech Stack:** Kotlin, Jetpack Compose with Material 3, Hilt, Coroutines/Flow, DataStore, sherpa-onnx Android AAR, `com.google.mlkit:translate`, JUnit4, kotlinx-coroutines-test, Turbine, AndroidX Test.

**Spec:** `README.md` (Vietnamese) and `CLAUDE.md`.

**Path shorthand:** `<pkg>` = `app/src/main/java/io/github/thanhngochoang/voicetrans`, `<test>` = `app/src/test/java/io/github/thanhngochoang/voicetrans`, `<atest>` = `app/src/androidTest/java/io/github/thanhngochoang/voicetrans`.

## Global Constraints

- `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26`. The README leaves minSdk open (§11 q5). 26 stays because nothing below needs a higher value.
- applicationId `io.github.thanhngochoang.voicetrans`, app name "VoiceTrans VI". These are placeholders for open question 5 and can be renamed in Task 1.
- `ndk { abiFilters += "arm64-v8a" }` and `androidResources { noCompress += "onnx" }`.
- Single Gradle module `app/`. The README module list maps to packages (`core.audio`, `feature.asr`, `feature.translate`, `feature.session`, `feature.ui`), not Gradle modules.
- ASR model: `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`, int8 encoder and joiner, under `app/src/main/assets/models/asr/`. VAD: `silero_vad.onnx` under `app/src/main/assets/models/vad/`. Load directly from `AssetManager` and never copy to `filesDir`.
- **Audio source: internal playback only, never the microphone.** Use `AudioPlaybackCapture` with usages MEDIA, GAME and UNKNOWN, 16 000 Hz mono PCM16 (resample if needed), 1 600-sample (100 ms) chunks, converted to float in [-1, 1]. `RECORD_AUDIO` is still required, because Android demands it for playback capture. Phone/VoIP calls cannot be captured (Android rule), and apps that opt out of capture (many DRM video apps) yield silence.
- ASR: `numThreads = 2`. Endpoint rule2 `minTrailingSilence = 0.6f` s (the 1.2 s default would break the < 1 s budget). Rule3 `minUtteranceLength = 20f` s.
- Language: CJK `一-鿿` share ≥ 0.30 means `ZH`, otherwise `EN`.
- Only `AsrEvent.Final` is translated, one translation at a time, and only when the audio over that Final's time range is classified SPEECH or OTHER. SINGING and MUSIC are shown as "♪", never translated, in the MVP. A dedicated lyrics mode is phase 2: `docs/design/lyrics-mode.md`.
- UI: follow `docs/design/ui-spec.md`. Dark theme by default, a single accent color, every user-visible string in `res/values` (vi, default) and `res/values-en`, and none hard-coded.
- Source languages are fixed to EN/ZH by the ASR model. The target language is a setting (default `vi`).
- **Consent + FGS rule (Android 14+, unchanged in 16/17):** every session needs a fresh MediaProjection consent (`createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())` on API 34+), requested from a visible activity. The token is single-use and is never cached or reused. The service is `foregroundServiceType="mediaProjection"` (plus `|microphone` only if the Task 1 spike proves it is required), and it must call `startForeground` **before** `getMediaProjection`. It is never started from a boot receiver, an alarm or a `START_STICKY` restart, and `onStartCommand` returns `START_NOT_STICKY`.
- **Android 17 native loading:** load native libraries only through `System.loadLibrary` from the APK's `jniLibs`. Never extract `.so` files and `System.load()` them.
- **Android 17 PendingIntents:** all of them `FLAG_IMMUTABLE`. Activity-launching ones use `ActivityOptions` with `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` where a creator option is needed.
- Large screens (Fold/Tab): no `screenOrientation` or `resizeableActivity=false` in the manifest, because API 37 ignores them at sw ≥ 600dp. The UI must work in any size and orientation.
- Edge-to-edge is enforced: call `enableEdgeToEdge()` and apply `WindowInsets` padding.
- Audio never leaves the device, and there is no network traffic except ML Kit model downloads.

## Review Focus

1. **The playing app blocks capture** (DRM/streaming apps set `ALLOW_CAPTURE_BY_NONE`), so a video plays but we capture only silence. Expected: after 5 s the Live banner and the overlay say "App này không cho phép thu âm thanh — thử app YouTube, Chrome hoặc Samsung Internet", and the session keeps running (it recovers if the user switches app). Tests: `CaptureBlockedDetectorTest` (Task 3) and `TranslationSessionTest.zerosWhilePlaying_setsBlockedWarning_keepsRunning` (Task 6).
2. **An ML Kit pack is missing while offline**, for example after Play Services cleared it, or the user picked a new target while offline. Expected: the utterance shows the source text with a translation-error marker, and later sentences still translate. Test: `TranslationSessionTest.translatorFailure_marksUtterance_andQueueContinues` (Task 6).
3. **A long monologue with no pause.** Expected: a Final is emitted by about 20 s (rule3) instead of growing forever. Test: `AsrEngineTest.continuousSpeech_emitsFinalBeforeEnd` (Task 4).
4. **Rotation or fold/unfold during a session, especially a landscape full-screen video.** Expected: the conversation list, scroll position and running state survive. The overlay re-lays out inside the safe area (clear of the cutout and side nav bar), stays ≤ 35% of the height, and uses its own landscape position. Tests: `LiveScreenTest.recreateActivity_keepsUtterances`, `LiveScreenTest.rotation_keepsScrollPosition` (Task 10), and `OverlayControllerTest.rotation_relayoutsWithoutReadd` / `rotation_usesPerOrientationPosition` (Task 9).
5. **Consent denied, or capture stopped from the system "sharing" status-bar chip mid-session.** Expected: on deny, a clear message and a retry button. On chip stop, the session ends as `PROJECTION_ENDED`, the notification offers "chạm để tiếp tục", and nothing crashes. Tests: `TranslationSessionTest.stopWithReason_setsState` (Task 6) and `SessionNotificationsTest.stopped_projectionEnded_text` (Task 7).
6. **Speech over background music** (vlogs, news with a music bed) is misclassified as MUSIC, so real speech goes untranslated. Expected: it is still translated. Tests: `ClassRulesTest.speechOverMusic_isSpeech`, the `speech_over_music` folder in `AudioClassifierTest` (Task 4A), and `TranslationSessionTest.speechOverMusic_translated` (Task 6).

## Build environment (verified on this Mac, 2026-11-07)

| Item | Value | Status |
|---|---|---|
| Android SDK | `/Users/macbook/Documents/Projects/MINE/photo-booking/booking/.android-sdk`, set in **`local.properties` → `sdk.dir`** (gitignored) | ✓ done. `$ANDROID_HOME` in `~/.zshrc` still points to the old `MINE/booking` path, and `local.properties` overrides it for this project. |
| Platform | `platforms/android-37.0` (Android 17, rev 2) | ✓ installed |
| Build tools | `36.0.0` (AGP 9.5 minimum and default) | ✓ installed |
| NDK | `28.2.13676358` (AGP 9.5 default) | ✓ installed |
| JDK | **Azul Zulu 21.0.13 LTS, arm64** (security patch of 2026-10-20). Gradle auto-provisions it into `~/.gradle/jdks` from `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=21`, `toolchainVendor=AZUL`, foojay URLs) using the `foojay-resolver-convention` plugin in `settings.gradle.kts`. AGP 9 supports a JDK 17 or 21 daemon; 21 was chosen for its longer security support. **Do not use** the old Oracle 17.0.2 in `/Library/Java` (2022, unpatched, x86_64/Rosetta) or Homebrew openjdk 23.0.2 (non-LTS, EOL since 2025-03, no patches). **Security updates:** after each quarterly Java CPU (Jan/Apr/Jul/Oct), run `./gradlew updateDaemonJvm --jvm-version=21 --jvm-vendor=azul` and commit the changed properties file. | ✓ done |
| Gradle | **9.6.0** wrapper (`gradlew`, `gradle/wrapper/*`, regenerated with `./gradlew wrapper`). This is the AGP 9.5 minimum. | ✓ done, `./gradlew --version` OK |
| AGP | **9.5.0**, the latest. API 37 support requires AGP ≥ 9.1.1. | pin in Task 1 Step 1 |
| Kotlin | **2.4.10** is the version the AGP docs pair with 9.5.0. Verify in Step 1 whether AGP 9's built-in Kotlin replaces `org.jetbrains.kotlin.android`. The Compose compiler plugin version always equals the Kotlin version. | pin in Task 1 Step 1 |
| Host | macOS 14.4.1 on **Apple M1 (arm64)**. An earlier note said x86_64; that came from the Intel-build Oracle JDK running under Rosetta, not from the Mac. Instrumented tests run on the physical Android 17 phone. An **arm64 emulator** is also possible; only `system-images/android-35/google_apis_playstore/arm64-v8a` is installed, so add an API 37 arm64 image if you want an Android 17 emulator. | note |

Shell prerequisite for every Gradle command in this plan (the launcher JVM; the daemon picks Zulu 21 by itself). It is a patched arm64 LTS, so it replaces the unpatched Oracle 17.0.2 and Homebrew 23:
```bash
export JAVA_HOME=~/.gradle/jdks/azul_systems__inc_-21-aarch64-os_x.2/zulu21.54.107-ca-jdk21.0.13-macosx_aarch64/Contents/Home
```
In Claude Code's sandbox the JVM can't resolve hosts, because it ignores the sandbox proxy. Gradle commands that download (wrapper, dependencies, the first build) must run with the sandbox disabled.

---

### Task 1: Project scaffold + sherpa-onnx spike on API 37

Merges README Phase 0 ("chạy sherpa-onnx trên máy thật, chốt version").

**Files:**
- Already present: `settings.gradle.kts` (repositories + `rootProject.name`), `gradlew`, `gradle/wrapper/*`, `gradle/gradle-daemon-jvm.properties`, `local.properties`, `.gitignore`. Modify `settings.gradle.kts` to `include(":app")`.
- Create: `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `<pkg>/VoiceTransApp.kt` (`@HiltAndroidApp`), `<pkg>/MainActivity.kt` (empty Compose screen with `enableEdgeToEdge()`)
- Create: `app/libs/sherpa-onnx-<ver>.aar`, `app/src/main/assets/models/asr/{encoder-epoch-99-avg-1.int8.onnx,decoder-epoch-99-avg-1.onnx,joiner-epoch-99-avg-1.int8.onnx,tokens.txt}`, `app/src/main/assets/models/vad/silero_vad.onnx`
- Create: `scripts/fetch-models.sh` (downloads and extracts the models above from the sherpa-onnx GitHub releases and verifies SHA-256)
- Create: `app/src/main/assets/models/models.json` (URLs and SHA-256 per file; the README's "versioned config" rule)
- Test: `<atest>/SherpaSmokeTest.kt`, `app/src/androidTest/assets/wav/{en_good_morning.wav,zh_zaoshanghao.wav}`

**Interfaces:**
- Produces: a buildable app, the pinned versions in `libs.versions.toml`, and the model assets at the paths above.

- [ ] **Step 1: Pin versions in `gradle/libs.versions.toml`.** AGP `9.5.0` and Gradle `9.6.0` (already in the wrapper) are fixed. Look up and pin the latest stable Compose BOM, Hilt (a version that supports AGP 9), KSP (matching the Kotlin version), `com.google.mlkit:translate`, DataStore, `core-splashscreen`, and the latest sherpa-onnx release (it must state 16 KB page-size support). Then decide two things by making `./gradlew :app:assembleDebug` pass with the empty app:
  - (a) **Kotlin setup:** first try AGP 9 built-in Kotlin, with **no** `org.jetbrains.kotlin.android` plugin, plus `org.jetbrains.kotlin.plugin.compose` at the Kotlin version. If AGP rejects that, apply `org.jetbrains.kotlin.android` `2.4.10`. Record which one worked in `libs.versions.toml` comments.
  - (b) **compileSdk syntax for the `android-37.0` platform:** try `compileSdk = 37`. If AGP can't find the platform, use `compileSdk { version = release(37) }`. Set `targetSdk = 37`, `buildToolsVersion` unset (default 36.0.0), `ndkVersion` unset (default 28.2.13676358), plus `kotlin { jvmToolchain(21) }` with bytecode target 17 (`compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }` and `kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }`).

  If the AAR does not contain the `com.k2fsa.sherpa.onnx` Kotlin classes, copy `OnlineRecognizer.kt`, `Vad.kt`, `FeatureConfig.kt` and `AudioTagging.kt` from that release's `sherpa-onnx/kotlin-api/` into `app/src/main/java/com/k2fsa/sherpa/onnx/`.
- [ ] **Step 2:** Write `scripts/fetch-models.sh` and `models.json`, run the script, and confirm that the 5 files exist.
- [ ] **Step 3:** Generate the test WAVs on macOS:
  ```bash
  say -v Samantha "good morning everyone" -o /tmp/en.aiff && afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/en.aiff app/src/androidTest/assets/wav/en_good_morning.wav
  say -v Tingting "大家早上好" -o /tmp/zh.aiff && afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/zh.aiff app/src/androidTest/assets/wav/zh_zaoshanghao.wav
  ```
- [ ] **Step 4: Write the failing smoke test.** `SherpaSmokeTest.decodesEnglishWav` builds an `OnlineRecognizer` from the instrumentation context's `assets`, feeds `en_good_morning.wav` plus 1 s of zeros, and asserts `getResult(stream).text.lowercase()` contains `"morning"`. `decodesChineseWav` does the same with `zh_zaoshanghao.wav` and asserts that the text contains `"早上好"`.
- [ ] **Step 5:** Run `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.thanhngochoang.voicetrans.SherpaSmokeTest` on the Android 17 phone. Expected: PASS. If it fails with `UnsatisfiedLinkError`, `IllegalAccessException` or a JNI crash, the AAR has an Android 17 incompatibility (native DCL or static-final JNI writes). Stop and report the error together with the sherpa-onnx version.
- [ ] **Step 6: Playback-capture spike (decision gate).** Add a debug-only `app/src/debug/java/.../CaptureSpikeActivity.kt` with one button. It requests consent with `createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())`, starts a debug `mediaProjection` FGS, and records 10 s of `AudioPlaybackCapture` to `filesDir/spike.wav`. On the Android 17 phone, play an English YouTube video and run it. Record in `docs/spikes/playback-capture.md`:
  - (a) whether `AudioFormat` 16 kHz mono PCM16 is accepted or capture requires 48 kHz stereo;
  - (b) whether the FGS type `mediaProjection` alone works, or `|microphone` is needed;
  - (c) whether One UI shows the green **mic** privacy dot (it should not);
  - (d) whether the WAV is non-silent for the YouTube app, Chrome, **Brave**, Samsung Internet and Samsung Video, and silent for one DRM app (e.g. Netflix). Brave is expected to be **silent**: brave-browser issue #47011 (open since 2025-06) reports that screen recorders get no audio from Brave on Android, while Chrome works despite having the same `allowAudioPlaybackCapture="false"` manifest flag. Record what `AudioPlaybackConfiguration.audioAttributes.allowedCapturePolicy` reports for Brave, so we know whether `PlaybackProbe.blocksCapture` detects it or only the zeros-while-playing rule does;
  - (e) what the consent dialog offers (single app vs entire screen) and that the default-display config forces entire screen;
  - (f) that `adb pull` of the WAV fed through `SherpaSmokeTest`'s decoder gives text.

  - (g) **audio-tagging model choice:** download `ced_tiny`, `ced_mini` (int8) and `zipformer-small-audio-tagging` int8 (26 MB) from the sherpa-onnx `audio-tagging-models` release. Record each one's size and per-1 s-window latency on the phone. Pick the smallest that passes Task 4A's accuracy test, and add it to `models.json` and `scripts/fetch-models.sh`.

  If (d) is silent for YouTube, **stop** and report: the product premise fails on this device.
- [ ] **Step 7:** Verify the 16 KB alignment with `$ANDROID_HOME/build-tools/<latest>/zipalign -c -P 16 -v 4 app/build/outputs/apk/debug/app-debug.apk`. Expected: "Verification successful". Note the APK size in the commit message.
- [ ] **Step 8: Commit.** `git commit -m "build: scaffold app targeting API 37 with sherpa-onnx, bundled models and playback-capture spike"`. Commit the model binaries only if each one is under 100 MB (GitHub's limit). Otherwise add `app/src/main/assets/models/**/*.onnx` to `.gitignore` and rely on `scripts/fetch-models.sh`.

### Task 2: LanguageDetector

**Files:**
- Create: `<pkg>/feature/translate/api/SourceLang.kt`, `<pkg>/feature/translate/api/LanguageDetector.kt`, `<pkg>/feature/translate/api/CjkRatioLanguageDetector.kt`
- Test: `<test>/feature/translate/api/CjkRatioLanguageDetectorTest.kt`

**Interfaces:**
- Produces: `enum class SourceLang { EN, ZH }`, `interface LanguageDetector { fun detect(text: String): SourceLang }`, `class CjkRatioLanguageDetector @Inject constructor() : LanguageDetector`

- [ ] **Step 1: Write the failing tests.**
  ```kotlin
  @Test fun english() = assertEquals(SourceLang.EN, d.detect("good morning everyone"))
  @Test fun chinese() = assertEquals(SourceLang.ZH, d.detect("大家早上好"))
  @Test fun mixedMostlyChinese() = assertEquals(SourceLang.ZH, d.detect("我们明天开meeting"))
  @Test fun mixedMostlyEnglish() = assertEquals(SourceLang.EN, d.detect("let's go to the 公司 now please"))
  @Test fun blank() = assertEquals(SourceLang.EN, d.detect("   "))
  ```
  The ratio is CJK chars divided by non-whitespace chars, and it is ZH when the ratio is ≥ 0.30.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*CjkRatioLanguageDetectorTest"`. Expected: FAIL (unresolved).
- [ ] **Step 3:** Implement it.
- [ ] **Step 4:** Rerun. Expected: PASS.
- [ ] **Step 5:** `git commit -m "feat: CJK-ratio language detector"`

### Task 3: PlaybackCaptureSource + CaptureBlockedDetector

The audio source is **sound playing on the phone** (videos), captured with `AudioPlaybackCapture`. **No microphone.**

**Files:**
- Create: `<pkg>/core/audio/AudioSource.kt`, `<pkg>/core/audio/PlaybackCaptureSource.kt`, `<pkg>/core/audio/Pcm.kt`, `<pkg>/core/audio/PlaybackProbe.kt`, `<pkg>/core/audio/CaptureBlockedDetector.kt`
- Test: `<test>/core/audio/PcmTest.kt`, `<test>/core/audio/CaptureBlockedDetectorTest.kt`, `<test>/core/audio/PlaybackProbeTest.kt` (Robolectric)

**Interfaces:**
- Produces:
  - `fun ShortArray.toFloatPcm(length: Int = size): FloatArray` (divides by 32768f)
  - `interface AudioSource { fun frames(): Flow<FloatArray> }`
  - `class PlaybackCaptureSource(projection: MediaProjection) : AudioSource`. It is a cold flow on `Dispatchers.IO`, built with `AudioRecord.Builder().setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(USAGE_MEDIA).addMatchingUsage(USAGE_GAME).addMatchingUsage(USAGE_UNKNOWN).build())` and the format the Task 1 spike recorded (target: 16 kHz mono PCM16; if capture only gives 48 kHz stereo, downmix and decimate to 16 kHz here). It releases in `finally`, and throws `AudioUnavailableException` when state ≠ INITIALIZED or `read` < 0.
  - `data class PlaybackState(val active: Boolean, val blocksCapture: Boolean)` and `fun interface PlaybackProbe { fun state(): PlaybackState }`. `AudioManagerPlaybackProbe` reads `AudioManager.activePlaybackConfigurations`, filtered to the usages above. `blocksCapture` = any config whose `audioAttributes.allowedCapturePolicy != ALLOW_CAPTURE_BY_ALL`.
  - `class CaptureBlockedDetector(thresholdMs: Long = 5_000, frameMs: Long = 100) { fun onFrame(frame: FloatArray, playback: PlaybackState): Boolean }`. It returns `true` once when consecutive all-zero frames reach the threshold **while `playback.active`**. It resets on any non-zero sample. Silence while nothing plays is normal (a paused video) and never fires.

- [ ] **Step 1: Write the failing tests.**
  - `PcmTest`: `shortArrayOf(0, 16384, -32768).toFloatPcm()` equals `floatArrayOf(0f, 0.5f, -1f)`.
  - `CaptureBlockedDetectorTest`:
    - `zerosWhileNothingPlays_neverFires`: 200 zero frames with `PlaybackState(false, false)` all give `false`.
    - `zerosWhilePlaying_firesAtFiveSeconds`: with `PlaybackState(true, true)`, frames 1–49 give `false` and frame 50 gives `true`.
    - `soundResets`: 40 zero frames, 1 frame with `0.01f`, then 49 zero frames (playing) all give `false`.
    - `firesOnceUntilSound`: frame 51 gives `false`. After a non-zero frame and 50 more zero frames, it fires again.
  - `PlaybackProbeTest` (Robolectric, shadow `AudioManager` with fake configs): a USAGE_MEDIA config with `ALLOW_CAPTURE_BY_NONE` gives `PlaybackState(true, true)`. A USAGE_VOICE_COMMUNICATION config only gives `active == false` (calls are outside our usages).
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*core.audio*"`. Expected: FAIL.
- [ ] **Step 3:** Implement the files. `PlaybackCaptureSource` reads 100 ms frames (1 600 samples after resampling).
- [ ] **Step 4:** Rerun. Expected: PASS.
- [ ] **Step 5:** `git commit -m "feat: playback-capture audio source and blocked-capture detector"`

### Task 4: AsrEngine (VAD gate + streaming Zipformer)

**Files:**
- Create: `<pkg>/feature/asr/AsrEvent.kt`, `<pkg>/feature/asr/AsrEngine.kt`, `<pkg>/feature/asr/SherpaAsrEngine.kt`, `<pkg>/feature/asr/AsrConfig.kt`
- Test: `<atest>/feature/asr/AsrEngineTest.kt`, `<atest>/WavReader.kt` (test helper: 16-bit mono WAV to `FloatArray`)

**Interfaces:**
- Consumes: `AudioSource.frames()` from Task 3.
- Produces:
  - `sealed interface AsrEvent { data class Partial(val text: String); data class Final(val text: String, val startMs: Long, val endMs: Long) }`, both implementing `AsrEvent`.
  - `interface AsrEngine : Closeable { fun transcribe(frames: Flow<FloatArray>): Flow<AsrEvent> }`
  - `class SherpaAsrEngine(assets: AssetManager, config: AsrConfig = AsrConfig()) : AsrEngine`. It creates the native `OnlineRecognizer` and `Vad` once in `init`, and `close()` releases both.
  - `data class AsrConfig(val numThreads: Int = 2, val rule2TrailingSilenceS: Float = 0.6f, val rule3MaxUtteranceS: Float = 20f, val preRollMs: Int = 300)`

- [ ] **Step 1: Write the failing tests.** Use the instrumentation `assets` for the models and the WAVs from Task 1, and feed 100 ms chunks through `flowOf(...)`.
  - `english_emitsFinalContainingMorning`: wav + 1 s zeros. Exactly one `Final`, whose `text.lowercase()` contains `"morning"`, and at least one `Partial` before it.
  - `chinese_emitsFinal`: the Final text contains `"早上好"`.
  - `silenceOnly_emitsNothing`: 5 s of zeros gives no events.
  - `continuousSpeech_emitsFinalBeforeEnd`: `en_good_morning.wav` repeated back-to-back for 25 s with no gaps. At least one `Final` is emitted before the input ends.
  - `endpointLatency_under900ms`: measure wall time from feeding the last speech chunk to receiving `Final`, while feeding the zero chunks in real time (`delay(100)`). Assert < 900 ms, which leaves translation 100 ms or more of the 1 s budget.
- [ ] **Step 2:** Run `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.thanhngochoang.voicetrans.feature.asr.AsrEngineTest`. Expected: FAIL.
- [ ] **Step 3: Implement `SherpaAsrEngine.transcribe`.** The tests don't determine this algorithm:
  ```
  keep a ring buffer of the last preRollMs of audio
  for each frame (on a single-thread dispatcher; native objects are not thread-safe):
    vad.acceptWaveform(frame)
    if (!inSpeech && vad.isSpeechDetected()) { inSpeech = true; stream.acceptWaveform(ring + frame, 16000); startMs = now(frames) }
    else if (inSpeech) stream.acceptWaveform(frame, 16000)
    else { ring.push(frame); continue }        // skip decoding during silence (battery)
    while (recognizer.isReady(stream)) recognizer.decode(stream)
    text = recognizer.getResult(stream).text.trim()
    if (text.isNotEmpty() && text != lastPartial) emit Partial(text)
    if (recognizer.isEndpoint(stream)) {
       if (text.isNotEmpty()) emit Final(text, startMs, now(frames))
       recognizer.reset(stream); inSpeech = vad.isSpeechDetected(); lastPartial = ""
    }
  ```
  `now(frames)` is the stream position in ms (frames × 100), not wall clock. Set the model type to `"zipformer"`, `enableEndpoint = true`, rule1 at the default 2.4 s, rule2 and rule3 from `AsrConfig`.
- [ ] **Step 4:** Rerun. Expected: PASS. If the latency test fails, report the measured value; don't loosen the assertion.
- [ ] **Step 5:** `git commit -m "feat: VAD-gated streaming zipformer ASR engine"`

### Task 4A: AudioClassifier (speech / singing / music)

Keeps music and songs from being turned into garbage subtitles. It uses the sherpa-onnx **audio tagging** API (AudioSet labels) on the same captured frames.

**Files:**
- Create: `<pkg>/feature/asr/AudioClass.kt`, `<pkg>/feature/asr/AudioClassifier.kt`, `<pkg>/feature/asr/SherpaAudioClassifier.kt`, `<pkg>/feature/asr/ClassTimeline.kt`
- Create: `app/src/main/assets/models/tagging/` (the model chosen in Task 1 Step 6g + `class_labels_indices.csv`)
- Test: `<test>/feature/asr/ClassRulesTest.kt`, `<test>/feature/asr/ClassTimelineTest.kt`, `<atest>/feature/asr/AudioClassifierTest.kt`, `app/src/androidTest/assets/clips/{speech,singing,music,speech_over_music}/` (≥ 5 clips each, 16 kHz mono, 5–10 s, CC0 sources listed in `clips/SOURCES.md`)

**Interfaces:**
- Consumes: 100 ms `FloatArray` frames (Task 3).
- Produces:
  - `enum class AudioClass { SPEECH, SINGING, MUSIC, OTHER }`
  - `fun classify(scores: Map<String, Float>): AudioClass`. Label families: speech = {Speech, Male speech, Female speech, Child speech, Conversation, Narration, monologue}; singing = {Singing, Choir, Rapping, Chant, Song}; music = {Music}. A family's score is the max of its members. The rules, in order:
    - SINGING when singing ≥ 0.40 and singing > speech;
    - SPEECH when speech ≥ 0.40;
    - MUSIC when music ≥ 0.50 and speech < 0.30 and singing < 0.30;
    - OTHER otherwise.

    The thresholds are constants in `ClassThresholds`, and the values above are starting points tuned in Step 4.
  - `interface AudioClassifier : Closeable { fun classify(window: FloatArray): Map<String, Float> }`. `SherpaAudioClassifier(assets)` uses top-k = 10 and 1 thread.
  - `class ClassTimeline(windowMs: Long = 1_000)` with `fun add(startMs: Long, cls: AudioClass)` and `fun majority(startMs: Long, endMs: Long): AudioClass`. On a tie, SPEECH wins, so speech is never dropped by mistake. It keeps only the last 60 s.

- [ ] **Step 1: Write the failing tests.**
  - `ClassRulesTest`:
    - `speechOverMusic_isSpeech`: `{Speech: 0.6, Music: 0.7}` gives SPEECH.
    - `singing_beatsSpeech`: `{Singing: 0.55, Speech: 0.42, Music: 0.8}` gives SINGING.
    - `pureMusic`: `{Music: 0.9, Speech: 0.05}` gives MUSIC.
    - `quiet_isOther`: `{}` gives OTHER.
    - `childLabelCounts`: `{"Female speech, woman speaking": 0.5}` gives SPEECH.
  - `ClassTimelineTest`: `majority_ofWindowsInRange` and `tie_prefersSpeech`.
  - `AudioClassifierTest` (instrumented, on the `clips/` set): per folder, ≥ 80% of 1 s windows map to the expected class (speech_over_music → SPEECH). Log the per-clip confusion so thresholds can be tuned. Also `latency_perWindow_under150ms` on the phone (median over 50 windows).
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*ClassRulesTest" --tests "*ClassTimelineTest"`, then the instrumented test. Expected: FAIL.
- [ ] **Step 3:** Implement. Use the sherpa-onnx Kotlin `AudioTagging` API from the AAR or `kotlin-api` (verify the class name in Task 1). Label names come from `class_labels_indices.csv`.
- [ ] **Step 4:** Rerun. Expected: PASS. If a folder is below 80%, adjust `ClassThresholds` (and record the final values and confusion table in `docs/spikes/audio-classification.md`). Do not lower the 80% bar.
- [ ] **Step 5:** `git commit -m "feat: on-device speech/singing/music classifier"`

### Task 5: MlKitTranslator, language packs, settings store

Merges README Phase 0 ("test ML Kit en→vi, zh→vi offline"). The translation target is a setting (default `vi`), so packs are managed per language.

**Files:**
- Create: `<pkg>/feature/translate/api/Translator.kt`, `<pkg>/feature/translate/api/ModelState.kt`, `<pkg>/feature/translate/mlkit/MlKitTranslator.kt`, `<pkg>/core/settings/SettingsRepository.kt`, `<pkg>/core/settings/ThemeMode.kt`, `<pkg>/core/settings/OverlaySettings.kt`, `<pkg>/di/AppModule.kt`
- Test: `<atest>/feature/translate/MlKitTranslatorTest.kt`, `<test>/core/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `SourceLang` from Task 2.
- Produces:
  - `fun SourceLang.mlKitCode(): String` (`EN` → `TranslateLanguage.ENGLISH`, `ZH` → `TranslateLanguage.CHINESE`)
  - `interface Translator : Closeable { suspend fun translate(text: String, from: SourceLang, to: String): Result<String> }`, where `to` is an ML Kit language code. When `from.mlKitCode() == to`, it returns `Result.success(text)` without calling ML Kit.
  - `sealed interface ModelState { data object NotDownloaded; data object Downloading; data object Ready; data class Error(val message: String) }`
  - `class MlKitTranslator @Inject constructor() : Translator` with:
    - `val packs: StateFlow<Map<String, ModelState>>`
    - `suspend fun refreshPacks()` (from `RemoteModelManager.getDownloadedModels`)
    - `suspend fun download(lang: String, wifiOnly: Boolean): Result<Unit>`
    - `suspend fun delete(lang: String): Result<Unit>`
    - `fun requiredPacks(target: String): Set<String> = setOf(ENGLISH, CHINESE, target)`
    - `fun isReadyFor(target: String): Boolean`
  - `enum class ThemeMode { DARK, LIGHT, SYSTEM }`
  - `class SettingsRepository(dataStore: DataStore<Preferences>)` with:
    - `targetLanguage: Flow<String>` (default `"vi"`) and `suspend fun setTargetLanguage(code: String)`
    - `themeMode: Flow<ThemeMode>` (default `DARK`)
    - `subtitleSizeSp: Flow<Int>` (default 20, clamped 14..32)
    - `wifiOnlyDownload: Flow<Boolean>` (default true)
    - `backgroundSetupDone: Flow<Boolean>` (default false)
    - `autoStopMinutes: Flow<Int>` (default 30; allowed 0 = never, 10, 30, 60)
    - `consentExplainerSeen: Flow<Boolean>` (default false), so the pre-consent sheet is shown only once
    - `overlay: Flow<OverlaySettings>` with `data class OverlaySettings(val enabled: Boolean = true, val bgOpacityPercent: Int = 30 /* translating-mode background, 0..100, step 10 */, val textSizeSp: Int = 18 /* 14..28 */, val maxLines: Int = 2 /* 1..3 */, val showSource: Boolean = false, val position: OverlayPosition = OverlayPosition.BOTTOM, val customYPortrait: Float? = null, val customYLandscape: Float? = null /* fraction 0..1 of the safe height */, val autoHideSec: Int = 8 /* 0 = never, 5, 8, 15 */, val locked: Boolean = false)`, `enum class OverlayPosition { TOP, CENTER, BOTTOM }`, and `suspend fun updateOverlay(transform: (OverlaySettings) -> OverlaySettings)`. Setting a preset `position` resets both custom fractions to null. Fractions are clamped to 0..1.
    - a setter for each
  - The app UI language is **not** stored here. It lives in `AppCompatDelegate.getApplicationLocales()` (Task 8).
  - `AppModule` binds `LanguageDetector`, `Translator`, `AudioClassifier` → `SherpaAudioClassifier` and `PlaybackProbe` → `AudioManagerPlaybackProbe`, and provides singletons for `SherpaAsrEngine` (from `context.assets`), `SettingsRepository` and the DataStore. `ProjectionProvider` is bound in Task 7.

- [ ] **Step 1: Write the failing tests.**
  - `SettingsRepositoryTest` uses a temp-file `PreferenceDataStoreFactory`:
    - `defaults`: target `"vi"`, theme `DARK`, size 20, wifiOnly true, backgroundSetupDone false.
    - `subtitleSize_clamped`: `setSubtitleSizeSp(40)` reads back 32.
    - `autoStop_default30_andRejectsOtherValues`: the default is 30, and `setAutoStopMinutes(45)` keeps 30 (only 0/10/30/60 are accepted).
    - `overlay_defaults`: `OverlaySettings()` with enabled, 30%, 18sp, 2 lines, BOTTOM, autoHide 8, unlocked.
    - `overlay_opacityClampedAndStepped`: setting 73 reads back 70, and 140 reads back 100.
    - `overlay_presetResetsBothCustomPositions`: set `customYPortrait = 0.2f` and `customYLandscape = 0.7f`, then `position = TOP`. Both read back null.
    - `overlay_customYClamped`: setting `customYLandscape = 1.4f` reads back 1.0.
    - `targetLanguage_persists`: after `setTargetLanguage("ja")` a new repository on the same file reads `"ja"`.
  - `MlKitTranslatorTest` needs network on the first run:
    - `download_thenReady`: `download("vi", wifiOnly = false)` succeeds and `packs.value["vi"] == ModelState.Ready`.
    - `translatesEnglishToVietnamese`: `translate("good morning", EN, "vi")` returns a non-blank result with at least one Vietnamese diacritic (`Regex("[à-ỹđ]")`).
    - `translatesChineseToVietnamese`: same, for `"早上好"` with `ZH`.
    - `translatesEnglishToJapanese`: after `download("ja")`, `translate("good morning", EN, "ja")` contains at least one char in `぀-ヿ一-鿿`.
    - `sameLanguage_passthrough`: `translate("hello", EN, "en")` gives `"hello"`.
    - `requiredPacks_includesTarget`: `requiredPacks("ja") == setOf("en","zh","ja")`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*SettingsRepositoryTest"` and `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.thanhngochoang.voicetrans.feature.translate.MlKitTranslatorTest`. Expected: FAIL.
- [ ] **Step 3:** Implement it. Cache one ML Kit `Translator` client per `(source, target)` pair, created lazily and closed when that target's pack is deleted or on `close()`. Use `kotlinx-coroutines-play-services` `await()`, and `DownloadConditions.Builder().requireWifi()` when `wifiOnly`.
- [ ] **Step 4:** Rerun. Expected: PASS. Then enable airplane mode and rerun the four `translates*` tests. Expected: PASS (proves offline).
- [ ] **Step 5:** `git commit -m "feat: ML Kit translator with per-language packs and settings store"`

### Task 6: TranslationSession (pipeline + sequential queue)

**Files:**
- Create: `<pkg>/feature/session/Utterance.kt`, `<pkg>/feature/session/SessionState.kt`, `<pkg>/feature/session/TranslationSession.kt`
- Test: `<test>/feature/session/TranslationSessionTest.kt` (fakes for `AudioSource`, `AsrEngine` and `Translator`)

**Interfaces:**
- Consumes: `AudioSource`, `PlaybackProbe`, `CaptureBlockedDetector` (Task 3), `AudioClassifier`, `classify`, `ClassTimeline` (Task 4A), `AsrEngine`/`AsrEvent` (Task 4), `LanguageDetector` (Task 2), `Translator` and `SettingsRepository.targetLanguage` (Task 5).
- Produces:
  - `enum class UtteranceKind { SPEECH, MUSIC }`
  - `data class Utterance(val id: Long, val kind: UtteranceKind, val source: String, val sourceLang: SourceLang, val targetLang: String, val translation: String?, val timestampMs: Long, val endMs: Long, val translationFailed: Boolean = false)`. A MUSIC entry has an empty `source` and a `null` translation, and consecutive MUSIC entries are merged by extending `endMs`. `translation` is `null` while translating. When source equals target, `translation == source` and the UI hides the second line.
  - `sealed interface SessionState { Idle; Running; data class Stopped(val reason: StopReason) }` with `enum class StopReason { USER, AUDIO_ERROR, PROJECTION_ENDED, IDLE_TIMEOUT }`, plus `enum class CaptureWarning { BLOCKED_BY_APP }`
  - `@Singleton class TranslationSession @Inject constructor(asr: AsrEngine, classifier: AudioClassifier, detector: LanguageDetector, translator: Translator, settings: SettingsRepository, probe: PlaybackProbe)`. The audio source is per session, because it is built from that session's MediaProjection, exposing:
    - `val utterances: StateFlow<List<Utterance>>`, `val partial: StateFlow<String?>`, `val state: StateFlow<SessionState>`
    - `fun start(scope: CoroutineScope, audio: AudioSource)` and `fun stop(reason: StopReason = StopReason.USER)`
    - `val audioClass: StateFlow<AudioClass>` holds the latest 1 s window's class, for the "♪" indicator.
    - `val captureWarning: StateFlow<CaptureWarning?>`. It is set by `CaptureBlockedDetector`, cleared on the next non-zero frame, and never stops the session.
    - `val overlayHidden: StateFlow<Boolean>` and `fun setOverlayHidden(hidden: Boolean)`. This is a per-session flag, reset to false on `start()`.
  - `val translatedAt: SharedFlow<Pair<Utterance, Long>>`. This is the latency hook: it emits the utterance and the ms between `Final` and the translation result, and is also logged as `Log.i("VT-Latency", ...)`.

- [ ] **Step 1: Write the failing tests** using `runTest` and Turbine. The fake settings target is `"vi"` unless stated.
  - `partial_updatesPartial_neverTranslated`: the fake ASR emits `Partial("go")`. `partial.value == "go"` and `translator.calls` is empty.
  - `final_appendsUtteranceThenFillsTranslation`: `Final("good morning",0,1000)` first appends an `Utterance` with `translation == null` and `targetLang == "vi"`, then with `"chào buổi sáng"`, `sourceLang == EN`, and `partial.value == null`.
  - `translationsAreSequential`: the fake translator suspends on a `CompletableDeferred`. After 3 Finals, `translator.maxConcurrent == 1` and results arrive in id order.
  - `targetChangedMidSession_appliesToNextSentence`: Final #1 is translated to `"vi"`. Then set the fake target to `"ja"` and emit Final #2. Utterance[0].targetLang is `"vi"` and utterance[1].targetLang is `"ja"`. The target is read with `settings.targetLanguage.first()` per sentence, at dequeue time.
  - `translatorFailure_marksUtterance_andQueueContinues`: the first call returns `Result.failure`, the second succeeds. utterance[0] has `translationFailed == true` and `translation == null`, and utterance[1] is translated.
  - `blankFinal_ignored`: `Final("  ",0,0)` adds no utterance.
  - `musicFinal_notTranslated_mergedAsMusic`: the fake classifier returns MUSIC for 0–6 s, and the ASR emits `Final("la la",0,3000)` and `Final("oh oh",3000,6000)`. That gives exactly one MUSIC utterance with `endMs == 6000`, and `translator.calls` is empty.
  - `singingFinal_notTranslated`: same, with SINGING.
  - `speechOverMusic_translated`: the classifier returns SPEECH (scores had Music 0.8 too) and `Final("hello",0,1000)` is translated.
  - `classifierRunsOffAsrPath`: a fake classifier that sleeps 300 ms per window does not delay the ASR Final emission time by more than 50 ms.
  - `audioError_stopsSession`: the fake audio throws `AudioUnavailableException`, so `state.value == Stopped(AUDIO_ERROR)`.
  - `zerosWhilePlaying_setsBlockedWarning_keepsRunning`: with the fake probe `PlaybackState(true, true)` and 50 zero frames, `captureWarning.value == BLOCKED_BY_APP` and `state.value == Running`. One non-zero frame makes `captureWarning.value == null`.
  - `zerosWhileNothingPlays_noWarning`: with probe `(false, false)` and 200 zero frames, the warning is `null` and the state is Running.
  - `stopWithReason_setsState`: `stop(PROJECTION_ENDED)` gives `Stopped(PROJECTION_ENDED)`.
  - `noSpeechFor30Min_stopsWithIdleTimeout`: with virtual time and non-zero noise frames but no Final, after `advanceTimeBy(30.minutes)` the state is `Stopped(IDLE_TIMEOUT)`.
  - `finalResetsIdleTimer`: a Final at 29 min means the session is still `Running` at 31 min.
  - `autoStopZero_neverStops`: with `autoStopMinutes = 0`, the session is still Running after 3 h.
  - `startResetsOverlayHidden`: `setOverlayHidden(true)`, `stop()`, `start()`, and `overlayHidden.value == false`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*TranslationSessionTest"`. Expected: FAIL.
- [ ] **Step 3:** Implement it. Audio frames go through `CaptureBlockedDetector` (with `probe.state()` sampled once per second, not per frame), then the flow is shared (`shareIn`) between `asr.transcribe` and a classifier coroutine. The classifier coroutine batches 10 frames into a 1 s window, runs `classify` on its own single-thread dispatcher, and feeds `ClassTimeline`. Each dequeued Final checks `timeline.majority(startMs, endMs)` before translating. Finals go into `Channel<Utterance>(UNLIMITED)`, which one consumer coroutine drains. All work runs on `scope`, which the service supplies (`Dispatchers.Default` plus `SupervisorJob`). `utterances` survives `stop()` and is cleared only on the next `start()`, so returning to the app after a session still shows it.
- [ ] **Step 4:** Rerun. Expected: PASS.
- [ ] **Step 5:** `git commit -m "feat: translation session pipeline with sequential queue"`

### Task 7: Capture consent, mediaProjection foreground service, notification, permissions

**Design source:** the "Background running" section of `docs/design/ui-spec.md`.

**Files:**
- Create: `<pkg>/feature/session/TranslationService.kt`, `<pkg>/feature/session/CaptureConsent.kt`, `<pkg>/feature/session/ProjectionProvider.kt`, `<pkg>/feature/session/TranslationTileService.kt`, `<pkg>/feature/session/SessionNotifications.kt`, `<pkg>/feature/session/ExitReasonReporter.kt`, `<pkg>/feature/ui/PermissionGate.kt`
- Modify: `app/src/main/AndroidManifest.xml`. Add `RECORD_AUDIO`, `INTERNET`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` (and `FOREGROUND_SERVICE_MICROPHONE` only if the spike requires it), `POST_NOTIFICATIONS` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Declare `<service android:name=".feature.session.TranslationService" android:foregroundServiceType="mediaProjection" android:exported="false"/>`, and the tile service (`android.permission.BIND_QUICK_SETTINGS_TILE`, action `android.service.quicksettings.action.QS_TILE`, `ACTIVE_TILE` meta-data, icon `ic_stat_subtitles`).
- Modify: `app/src/main/res/values/strings.xml` and `app/src/main/res/values-en/strings.xml` (notification, exit-reason and permission strings in both languages)
- Test: `<test>/feature/session/ExitReasonReporterTest.kt`, `<test>/feature/session/SessionNotificationsTest.kt` (Robolectric), `<test>/feature/session/TranslationTileServiceTest.kt` (Robolectric), `<atest>/feature/ui/PermissionGateTest.kt`

**Interfaces:**
- Consumes: `TranslationSession` (including `audioClass`) (Task 6), `SettingsRepository.targetLanguage` (Task 5).
- Also: bind `ProjectionProvider` in a `SessionModule`.
- Produces:
  - `class CaptureConsent(activity: ComponentActivity)` with `fun request(onGranted: (resultCode: Int, data: Intent) -> Unit, onDenied: () -> Unit)`. It wraps `registerForActivityResult(StartActivityForResult())` around `MediaProjectionManager.createScreenCaptureIntent(...)`, and must be registered in `onCreate`.
  - `TranslationService.start(activity: Activity, resultCode: Int, data: Intent)` and `TranslationService.stop(context: Context)` (companion). `start` takes an `Activity` so callers can only start it from a visible UI right after consent.
  - `fun interface ProjectionProvider { fun get(resultCode: Int, data: Intent): MediaProjection }`. The default wraps `MediaProjectionManager.getMediaProjection`, and tests inject a fake.
  - `ExitReasonReporter.lastAbnormalExit(infos: List<ExitInfo>): Int?`. It returns a string-resource id for `REASON_LOW_MEMORY`, `REASON_OTHER` whose description contains `"MemoryLimiter"`, `REASON_USER_REQUESTED` (One UI "Sleeping apps"/force stop), and `REASON_EXCESSIVE_RESOURCE_USAGE`, and `null` otherwise. `ExitInfo(reason: Int, description: String?)` wraps `ApplicationExitInfo` so this stays a JVM test.
  - `SessionNotifications.running(context, last: Utterance?, targetName: String, overlay: OverlayNotifState, music: Boolean): Notification`. When `music` is true, the text is `R.string.notif_music` ("♪ Đang phát nhạc") under the last translation with `enum class OverlayNotifState { NONE, VISIBLE, HIDDEN, LOCKED }`, and `SessionNotifications.stopped(context, reason: StopReason): Notification`. Actions are Dừng, plus "Ẩn phụ đề" (VISIBLE) / "Hiện phụ đề" (HIDDEN) / "Mở khoá phụ đề" (LOCKED), at most 2.
  - `TranslationTileService : TileService`. While Running, the tile is `STATE_ACTIVE` and a click calls `TranslationService.stop`. While stopped, a click runs `startActivityAndCollapse(PendingIntent)` to `MainActivity` with extra `EXTRA_AUTO_START = true`. Live starts the session in `onResume` when that extra is present, then clears it.
  - `MainActivity.EXTRA_AUTO_START` Channel `"session"` has `IMPORTANCE_LOW`.
  - `@Composable fun PermissionGate(content: @Composable () -> Unit)` (RECORD_AUDIO only, with copy "Android yêu cầu quyền Ghi âm để thu **âm thanh đang phát trong máy**. App không dùng micro." Notifications and battery are handled by the BackgroundSetup screen in Task 8.)

- [ ] **Step 1: Write the failing tests.**
  - `ExitReasonReporterTest`: `ExitInfo(REASON_OTHER, "MemoryLimiter:AnonSwap")` gives `R.string.exit_memory`, and `ExitInfo(REASON_EXIT_SELF, null)` gives `null`.
  - `SessionNotificationsTest` (Robolectric):
    - `running_hasStopAction_andBigText`: `actions[0].title == "Dừng"`, and `EXTRA_BIG_TEXT` contains both source and translation.
    - `running_isSilentOngoing`: `FLAG_ONGOING_EVENT` is set, `FLAG_ONLY_ALERT_ONCE` is set, and the channel importance is `IMPORTANCE_LOW`.
    - `stopped_isDismissible_andOpensApp`: not ongoing, and `contentIntent != null`.
    - `stopped_idleTimeout_saysSilence`: `stopped(ctx, IDLE_TIMEOUT)` text contains `R.string.stopped_idle` formatted with 30.
    - `running_overlayStates_actions`: VISIBLE gives action titles `[Dừng, Ẩn phụ đề]`, HIDDEN gives `[Dừng, Hiện phụ đề]`, LOCKED gives `[Dừng, Mở khoá phụ đề]`, and NONE gives `[Dừng]`.
  - `TranslationTileServiceTest` (Robolectric): `tile_followsSessionState`: Running gives `STATE_ACTIVE`, and Stopped gives `STATE_INACTIVE`. `clickWhenStopped_opensMainWithAutoStart`: the started intent has `EXTRA_AUTO_START == true`, and **no** service start happens from the tile itself.
  - `TranslationServiceTest` (Robolectric, fake `ProjectionProvider` that records call order):
    - `startForegroundBeforeGetProjection`: the recorded order is `[startForeground(type=MEDIA_PROJECTION), getProjection]`.
    - `projectionOnStop_stopsSessionWithReason`: invoking the registered `MediaProjection.Callback.onStop()` gives `session.state == Stopped(PROJECTION_ENDED)` and the service stops itself.
    - `returnsNotSticky`: the result is `START_NOT_STICKY`.
  - `SessionNotificationsTest.running_music_showsNote`: with `music = true`, `EXTRA_BIG_TEXT` contains "♪ Đang phát nhạc".
  - `SessionNotificationsTest.stopped_projectionEnded_text`: `stopped(ctx, PROJECTION_ENDED)` text equals `R.string.stopped_projection`.
  - `PermissionGateTest.denied_showsOpenSettingsButton`: with RECORD_AUDIO revoked (`UiAutomation.revokeRuntimePermission`), the node with text `R.string.open_settings` is displayed. `granted_showsContent`: the content is displayed.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*feature.session*"` and the PermissionGate instrumented test. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `onStartCommand` calls `ServiceCompat.startForeground(this, ID, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` first. Then it calls `projectionProvider.get(resultCode, data)` and `projection.registerCallback(onStop → session.stop(PROJECTION_ENDED); stopSelf())`, then `session.start(lifecycleScope, PlaybackCaptureSource(projection))`, and returns `START_NOT_STICKY`. `onDestroy` calls `projection.stop()`.
  - Live's Start button and `EXTRA_AUTO_START` (tile) both go through `CaptureConsent.request`. On deny, Live shows `R.string.consent_denied` with a retry button.
  - If RECORD_AUDIO is not granted, it calls `stopSelf()` instead of `startForeground` (otherwise a `SecurityException` is thrown on 14+).
  - The service collects `session.translatedAt` and calls `NotificationManagerCompat.notify(ID, running(...))` per finished translation.
  - On `Stopped(PROJECTION_ENDED, AUDIO_ERROR or IDLE_TIMEOUT)` it posts `stopped(context, reason)` under a different id, then `stopSelf()`. Tapping that notification opens Live, where the user taps Start (new consent).
  - Notification text uses the service context, which follows the per-app locale on API 33+, so the language chosen in Task 8 applies.
  - `onDestroy` calls `session.stop()`, which cancels the `transcribe` collection. `SherpaAsrEngine.transcribe` releases its per-session `OnlineStream` and resets the VAD in `finally`. The recognizer and VAD models are app singletons that stay loaded (README: "tạo engine một lần và tái sử dụng").
  - `PermissionGate` requests RECORD_AUDIO. If denied, it shows the message and the "Mở Cài đặt" button (`ACTION_APPLICATION_DETAILS_SETTINGS`).
- [ ] **Step 4:** Rerun the tests. Expected: PASS. Then check on the device:
  - Run `adb shell dumpsys activity services io.github.thanhngochoang.voicetrans`. Expected: `isForeground=true` and `foregroundServiceType=0x20` (mediaProjection).
  - While a YouTube video plays, One UI shows the screen-sharing indicator, and **no** green mic dot.
- [ ] **Step 5:** `git commit -m "feat: capture consent, mediaProjection foreground service and session notification"`

### Task 8: Theme, app language, navigation, ModelSetup and BackgroundSetup

**Design source:** `docs/design/ui-spec.md` (Foundations, Languages, Background running, ModelSetup). Follow it exactly.

**Files:**
- Create: `<pkg>/feature/ui/theme/{Color.kt,Theme.kt,Type.kt}` (the token table, `dynamicColor = false`, `VoiceTransTheme(mode: ThemeMode)`), `<pkg>/feature/ui/AppNav.kt`, `<pkg>/feature/ui/AppLanguage.kt`, `<pkg>/feature/ui/modelsetup/{ModelSetupScreen.kt,ModelSetupViewModel.kt}`, `<pkg>/feature/ui/background/{BackgroundSetupScreen.kt,BackgroundStatus.kt}`
- Create: the app icon from `logo.png` per the UI spec's "App icon and splash": `res/mipmap-anydpi-v26/ic_launcher.xml` (background `#000209` + scaled foreground), `res/mipmap-*/ic_launcher*.png`, `res/drawable/ic_stat_subtitles.xml` (notification small icon), and a splash theme using `androidx.core:core-splashscreen`. Move `logo.png` to `design/logo-source.png` so it is not shipped twice.
- Create: `app/src/main/res/xml/locales_config.xml` (`vi`, `en`), and complete `res/values/strings.xml` (vi, default) and `res/values-en/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml` (`android:localeConfig="@xml/locales_config"`, plus the `AppLocalesMetadataHolderService` with `autoStoreLocales=true` for API < 33), and `<pkg>/MainActivity.kt` (extends `AppCompatActivity`, applies `VoiceTransTheme(settings.themeMode)`, hosts `PermissionGate { AppNav() }`)
- Test: `<test>/feature/ui/StringsParityTest.kt`, `<test>/feature/ui/theme/ThemeContrastTest.kt`, `<atest>/feature/ui/ModelSetupScreenTest.kt`, `<atest>/feature/ui/BackgroundSetupScreenTest.kt`, `<atest>/feature/ui/AppLanguageTest.kt`

**Interfaces:**
- Consumes: `MlKitTranslator.packs/download/refreshPacks/requiredPacks/isReadyFor` and `SettingsRepository` (Task 5), `PermissionGate` (Task 7).
- Produces:
  - `enum class AppLanguage(val tag: String?) { SYSTEM(null), VI("vi"), EN("en") }` with `fun AppLanguage.apply()` (calls `AppCompatDelegate.setApplicationLocales`) and `fun currentAppLanguage(): AppLanguage`
  - `data class BackgroundStatus(val notificationsGranted: Boolean, val batteryUnrestricted: Boolean, val overlayGranted: Boolean)` (`Settings.canDrawOverlays`) and `fun readBackgroundStatus(context: Context): BackgroundStatus`
  - Routes: `modelsetup` → `backgroundsetup` (only while `backgroundSetupDone == false`) → `live`, plus `settings` and `languagepicker`. The start destination is `modelsetup` when `!isReadyFor(target)`. `live` is unreachable until then.

- [ ] **Step 1: Write the failing tests.**
  - `ThemeContrastTest` (JVM): for every text role pair in `Color.kt` (onSurface/surface, onSurfaceVariant/surface, primary/surface, secondary/surface, error/surface, onPrimaryContainer/primaryContainer, and onError on the `#C60516` button) in both schemes, the WCAG contrast is ≥ 4.5. This keeps the logo-derived palette from regressing.
  - `StringsParityTest` (JVM, parses both XML files): the key sets of `values/strings.xml` and `values-en/strings.xml` are equal, and no value is empty.
  - `ModelSetupScreenTest.notReady_blocksLive`: with fake packs where `vi` is `NotDownloaded`, the `R.string.download_packs` button exists and `R.string.start_listening` does not.
  - `ModelSetupScreenTest.ready_goesToBackgroundSetupFirstTime`: all required packs `Ready` and `backgroundSetupDone=false` show the `R.string.background_title` screen.
  - `BackgroundSetupScreenTest.continueAlwaysEnabled`: with both statuses false, the "Tiếp tục" button is enabled. Tapping it sets `backgroundSetupDone=true` and shows Live.
  - `BackgroundSetupScreenTest.showsStatusTextNotJustColor`: with `batteryUnrestricted=false`, the text `R.string.battery_optimized` is displayed.
  - `BackgroundSetupScreenTest.overlayRowIsOptional`: the overlay row shows `R.string.optional`, and with it not granted "Tiếp tục" still works and no warning card mentions it.
  - `ModelSetupScreenTest.landscapeCompactHeight_primaryButtonReachable` and `BackgroundSetupScreenTest.landscapeCompactHeight_continueReachable`: in a `DeviceConfigurationOverride` of 800×360dp, `performScrollTo()` on the primary button succeeds and it is displayed.
  - `AppLanguageTest.switchToEnglish_updatesUi`: `AppLanguage.EN.apply()` then `scenario.recreate()`. The English `start_listening` text ("Start listening") is displayed. Reset with `SYSTEM.apply()` in `@After`.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*StringsParityTest" --tests "*ThemeContrastTest"` and `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=io.github.thanhngochoang.voicetrans.feature.ui`. Expected: FAIL.
- [ ] **Step 3:** Implement per the UI spec. Battery status comes from `PowerManager.isIgnoringBatteryOptimizations`, notification status from `NotificationManagerCompat.areNotificationsEnabled()`, and both are re-read on `ON_RESUME`.
- [ ] **Step 4:** Rerun. Expected: PASS.
- [ ] **Step 5:** `git commit -m "feat: dark-first theme, app language switch, model and background setup screens"`

### Task 9: Floating subtitle overlay (Phụ đề nổi)

**Design source:** "Where translations appear → Floating subtitle" and Settings §3 in `docs/design/ui-spec.md`.

**Files:**
- Create: `<pkg>/feature/overlay/OverlayLines.kt` (pure logic), `<pkg>/feature/overlay/OverlayLayout.kt` (pure geometry), `<pkg>/feature/overlay/SubtitleOverlay.kt` (Composable box), `<pkg>/feature/overlay/AppExit.kt`, `<pkg>/feature/overlay/OverlayController.kt` (WindowManager + `ComposeView` with its own `LifecycleOwner`/`SavedStateRegistryOwner`)
- Modify: `app/src/main/AndroidManifest.xml` (`<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`), `<pkg>/feature/session/TranslationService.kt` (calls the singleton `OverlayController.attach()` on start and `detach()` in `onDestroy`)
- Test: `<test>/feature/overlay/OverlayLinesTest.kt`, `<test>/feature/overlay/OverlayLayoutTest.kt`, `<atest>/feature/overlay/SubtitleOverlayTest.kt`, `<atest>/feature/overlay/OverlayControllerTest.kt`, `<atest>/feature/overlay/AppExitTest.kt`

**Interfaces:**
- Consumes: `TranslationSession.utterances/state` (Task 6), `OverlaySettings` from `SettingsRepository.overlay` (Task 5), and `TranslationService` (Task 7).
- Produces:
  - `fun overlayLines(utterances: List<Utterance>, maxLines: Int, showSource: Boolean): List<OverlayLine>` with `data class OverlayLine(val id: Long, val text: String, val isSource: Boolean, val isNewest: Boolean)`. It uses only utterances with a non-null translation, so translating, failed and partial ones are skipped.
  - `data class SafeArea(val left: Int, val top: Int, val right: Int, val bottom: Int)` (px, the screen minus `systemBars ∪ displayCutout` insets ignoring visibility) and `enum class Orientation { PORTRAIT, LANDSCAPE }`.
  - `fun overlayWidth(safe: SafeArea, density: Float): Int` = `min(safeWidth − 32dp, 720dp)`.
  - `fun overlayY(position: OverlayPosition, customY: Float?, orientation: Orientation, safe: SafeArea, boxHeightPx: Int, density: Float): Int`. It returns the box's top-y, always inside `safe`. With `customY` it maps the fraction to `safe.top + customY × (safeHeight − boxHeight)`. The BOTTOM margin is 24dp in portrait and 16dp in landscape.
  - `fun maxBoxHeight(safe: SafeArea): Int` = 35% of the safe height. `fun fitLines(lines: List<OverlayLine>, lineHeightsPx: List<Int>, maxPx: Int): List<OverlayLine>` drops the oldest lines first and always keeps the newest translation line.
  - `fun windowAlpha(locked: Boolean): Float`, which is `0.8f` when locked and `1f` otherwise.
  - `enum class OverlayMode { TRANSLATING, SETUP }`. In SETUP the background is `#000000` at 85% with a 1dp primary outline, a drag handle and sample text when `lines` is empty. In TRANSLATING the background is `bgOpacityPercent` with no outline or handle.
  - `enum class OverlayNotice { MUSIC, CAPTURE_BLOCKED }` and `fun overlayNotice(audioClass: AudioClass, warning: CaptureWarning?, lastSpeechAgoMs: Long): OverlayNotice?`. CAPTURE_BLOCKED wins. MUSIC applies when the class is MUSIC or SINGING and `lastSpeechAgoMs ≥ 2000`. Otherwise the result is null.
  - `@Composable fun SubtitleOverlay(lines: List<OverlayLine>, notice: OverlayNotice?, settings: OverlaySettings, mode: OverlayMode, onClose: () -> Unit, onExitApp: () -> Unit, onDrag: (dy: Float) -> Unit, onDragEnd: () -> Unit)`. A drag beyond touch slop calls `onDrag`. A long-press (500ms, no movement) shows the control row `[Đóng phụ đề] [Thoát app]` for 5 s. A tap does nothing.
  - `class AppExit(private val terminate: () -> Unit = { Process.killProcess(Process.myPid()) })` with `fun exit(context: Context)`. It stops the session and `TranslationService` (which stops the projection), detaches the overlay, cancels all notifications, runs `finishAndRemoveTask()` on every `appTasks` entry, closes `AsrEngine`, `AudioClassifier` and `Translator`, and then calls `terminate()`. Ending the process guarantees the next launch rebuilds every `@Singleton` fresh. That avoids closed engines lingering in the Hilt graph, and it matches "tắt hẳn app".
  - `class OverlayController(context: Context, settings: Flow<OverlaySettings>, session: TranslationSession)` with `fun attach()`, `fun detach()`, `fun setAppInForeground(visible: Boolean)` and `fun setSetupMode(active: Boolean)`. It observes `session.utterances`, `session.audioClass`, `session.captureWarning` and `session.overlayHidden`, and applies setting changes with `WindowManager.updateViewLayout`, never re-adding the view. Visibility rule: **attached when** `setupMode || (sessionRunning && !appInForeground && !overlayHidden && enabled && canDrawOverlays)`. The mode is SETUP while `setupMode`, while dragging, or for 2 s after a settings emission; otherwise it is TRANSLATING. Auto-hide is suspended in SETUP.
  - The controller is an app-level `@Singleton`, not owned by the service, so the Settings screen can show the setup overlay when no session is running.

- [ ] **Step 1: Write the failing tests.**
  - `OverlayLinesTest`:
    - `takesNewestTranslatedOnly`: 4 utterances (the last one still translating) and maxLines 2 give the 2nd and 3rd translations, with the 3rd marked `isNewest`.
    - `showSource_addsSourceAboveEachTranslation`: with maxLines 1, the result is `[source(isSource=true), translation]`.
    - `failedAndSameLanguage`: a failed utterance is skipped. A same-language one (`translation == source`) shows only one line even with `showSource`.
  - `OverlayLayoutTest`:
    - `bottomPreset_portrait_sitsAboveSafeBottom`: with `SafeArea(0, 80, 1080, 2280)`, box 200 and density 3, `overlayY(BOTTOM, null, PORTRAIT, …)` = `2280 − 72 − 200`.
    - `bottomPreset_landscape_uses16dp`: with `SafeArea(90, 0, 2280, 1080)` (cutout left, nav right), box 200 and density 3, the result is `1080 − 48 − 200`.
    - `customY_mapsWithinSafeArea`: `customY = 0f` gives `safe.top`, and `customY = 1f` gives `safe.bottom − boxHeight`.
    - `width_landscapePhone_capped720dp`: with a safe width of 2190px at density 3, the result is `720×3`.
    - `fitLines_dropsOldestKeepsNewest`: with 4 lines of 80px and max 200, the result is the last 2. With 1 line taller than max, that line is still kept.
    - `maxBoxHeight_is35Percent`: with a safe height of 1080, the result is 378.
    - `locked_capsAlphaAt0_8`: `windowAlpha(true) == 0.8f` and `windowAlpha(false) == 1f`.
  - `SubtitleOverlayTest` (Compose):
    - `music_showsNoteLine`: with `notice = MUSIC`, the box shows `R.string.overlay_music` ("♪ Đang phát nhạc") as the last line, and the previous translations are kept above it.
    - `captureBlocked_showsWarningLine`: with `notice = CAPTURE_BLOCKED`, the box shows `R.string.overlay_capture_blocked`.
  - `OverlayLinesTest.notice_rules`: `overlayNotice(MUSIC, null, 3000) == MUSIC`, `overlayNotice(MUSIC, null, 500) == null`, `overlayNotice(SPEECH, BLOCKED_BY_APP, 0) == CAPTURE_BLOCKED`, and `overlayNotice(SINGING, null, 2500) == MUSIC`.
    - `translatingMode_backgroundFollowsSetting`: with mode TRANSLATING and opacity 30, the background is `Color.Black.copy(alpha = 0.3f)` (exposed through a test semantics property) and there is no outline node. At 0, the background is transparent and the text node exists.
    - `setupMode_solidBackgroundOutlineHandle`: with mode SETUP and opacity 0, the background alpha is 0.85, and the nodes testTag `outline` and `drag_handle` exist. With empty `lines` the sample text is shown.
    - `textStyle_hasShadow`: the translation text style has a non-null `shadow`.
  - `OverlayControllerTest` (instrumented, permission granted with `appops set <pkg> SYSTEM_ALERT_WINDOW allow`):
    - `hiddenWhileAppInForeground`: after `setAppInForeground(true)` no overlay view is attached, and after `false` one is.
    - `noPermission_noCrash_noView`: after `appops ... deny`, `attach()` adds no view and does not throw.
    - `lockedMode_setsNotTouchableFlag`: the `LayoutParams.flags` of the added view contain `FLAG_NOT_TOUCHABLE` and `alpha == 0.8f`.
    - `settingsChange_appliesWhileAttached`: while attached, emitting `bgOpacityPercent = 20` and then `locked = true` updates the same view (no re-add) with the new alpha and flags within 1 frame.
    - `overlayHidden_detachesUntilShown`: `session.setOverlayHidden(true)` removes the view, and `false` re-adds it while the session still runs.
    - `setupMode_showsEvenInForegroundWithoutSession`: with the session Idle and the app in the foreground, `setSetupMode(true)` attaches the view in SETUP mode, and `setSetupMode(false)` detaches it.
    - `settingsChange_flashesSetupFor2s`: while TRANSLATING, emitting a new `textSizeSp` makes the mode SETUP, and it is TRANSLATING again after 2 s.
    - `rotation_relayoutsWithoutReadd`: with the view attached, `onConfigurationChanged` with a landscape config (from `UiDevice.setOrientationLeft()`) gives the same view instance with new `LayoutParams.width/y` matching `overlayWidth/overlayY` for the new `SafeArea`.
    - `rotation_usesPerOrientationPosition`: with `customYPortrait = 0.1f` and `customYLandscape = null`, rotating to landscape places the box at the landscape BOTTOM preset, and rotating back restores the 0.1 position.
    - `drag_inLandscape_savesLandscapeOnly`: dragging in landscape updates `customYLandscape`, and `customYPortrait` is unchanged.
  - `AppExitTest` (instrumented, `AppExit(terminate = { terminated = true })`): during a running session, `exit(ctx)` leaves no running `TranslationService`, removes the overlay view, leaves `NotificationManager.activeNotifications` empty and `appTasks` empty, `asrEngine.isReleased == true`, and `terminated == true`.
  - `SubtitleOverlayTest` (control row):
    - `longPress_showsCloseAndExit_thenHidesAfter5s`: after `performTouchInput { longClick(durationMillis = 600) }`, the nodes Đóng phụ đề and Thoát app are displayed, and Dừng/Mở app do not exist. After `mainClock.advanceTimeBy(5_000)` they are gone.
    - `tap_doesNothing`: after `performClick()` no control row is shown and no callback fires.
    - `closeAndExit_invokeCallbacks`: clicking Đóng phụ đề calls `onClose` once, and clicking Thoát app calls `onExitApp` once.
    - `drag_movesWithoutControlRow`: `performTouchInput { down(center); moveBy(Offset(0f, -300f)); up() }` invokes `onDrag` with a negative dy and then `onDragEnd`, and no control row is shown.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*feature.overlay*"` and `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=io.github.thanhngochoang.voicetrans.feature.overlay`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Window: `TYPE_APPLICATION_OVERLAY`, `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (positions are computed from `SafeArea` ourselves), `WRAP_CONTENT` height capped by `maxBoxHeight`, width `overlayWidth(...)`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_NO_LIMITS`, gravity `TOP or LEFT` with x centering inside `SafeArea`, and `y = overlayY(...)`.
  - The controller registers `ComponentCallbacks` (`onConfigurationChanged`). On each change it recomputes `SafeArea`, orientation, width and y, and calls `updateViewLayout` without re-adding the view.
  - A drag updates `y` live through `updateViewLayout`, switches to SETUP mode while dragging, and on release saves the fraction to `customYPortrait` or `customYLandscape` for the current orientation. Đóng phụ đề → `session.setOverlayHidden(true)` (translation continues, and the notification shows "Hiện phụ đề"). Thoát app → `AppExit.exit(context)`.
  - Auto-hide uses an `AnimatedVisibility` fade, driven by a timer reset on each new translation.
  - The `@Singleton` controller drives `setAppInForeground` itself from `ProcessLifecycleOwner` (`ON_START`/`ON_STOP`). `TranslationService` calls `attach()` on start and `detach()` in `onDestroy`, but `detach()` keeps the view when `setupMode` is active.
  - Check `Settings.canDrawOverlays(context)` before every attach, because the user can revoke it at any time.
- [ ] **Step 4:** Rerun. Expected: PASS. Then run the UI spec's floating-subtitle acceptance checks on the phone (YouTube, opacity 0%, locked mode, drag + rotate).
- [ ] **Step 5:** `git commit -m "feat: floating subtitle overlay with opacity, position, lines and lock settings"`

### Task 10: Live and Settings screens

**Design source:** `docs/design/ui-spec.md` (Live, Settings, Languages → translation language). Follow it exactly. The tests pin the parts most likely to regress.

**Files:**
- Create: `<pkg>/feature/ui/live/{LiveScreen.kt,LiveViewModel.kt,UtteranceCard.kt,ConsentExplainerSheet.kt}`, `<pkg>/feature/ui/about/AboutScreen.kt` (Settings → Giới thiệu, with "Vì sao không có phụ đề?": the capture table from the UI spec, plus app version and model versions from `models.json`), `<pkg>/feature/ui/settings/{SettingsScreen.kt,SettingsViewModel.kt,OverlaySettingsScreen.kt}`, `<pkg>/feature/ui/language/{LanguagePickerScreen.kt,LanguageList.kt}`
- Test: `<test>/feature/ui/language/LanguageListTest.kt`, `<atest>/feature/ui/LiveScreenTest.kt`, `<atest>/feature/ui/SettingsScreenTest.kt`, `<atest>/feature/ui/LanguagePickerScreenTest.kt`

**Interfaces:**
- Consumes: `TranslationSession.utterances/partial/state/captureWarning/audioClass` (Task 6), `CaptureConsent`, `TranslationService.start/stop` and `ExitReasonReporter` (Task 7), `AppLanguage`, `BackgroundStatus` and routes (Task 8), `MlKitTranslator` and `SettingsRepository` (Task 5), and `SubtitleOverlay` for the settings preview (Task 9).
- Produces:
  - `fun orderedTargetLanguages(all: List<String>, appLocale: Locale): List<LanguageItem>` with `data class LanguageItem(val code: String, val name: String, val pinned: Boolean)`. The pinned order is `vi, en, zh, ja, ko, th, id`, then the rest sorted by `name` with `Collator.getInstance(appLocale)`.

- [ ] **Step 1: Write the failing tests.** Use a Hilt test module with a fake session, fake translator and in-memory settings.
  - `LanguageListTest.pinnedFirst_thenAlphabetical`: the first 7 codes equal the pinned list, `pinned` is true for exactly those, and the rest are sorted.
  - `LanguageListTest.namesFollowAppLocale`: with `Locale("vi")` the name for `ja` is `"Tiếng Nhật"`, and with `Locale.ENGLISH` it is `"Japanese"`.
  - `LiveScreenTest.showsSourceBadgeAndTranslation`: utterance `("good morning", EN, "vi", "chào buổi sáng")` gives the nodes `"EN"`, `"good morning"` and `"chào buổi sáng"`.
  - `LiveScreenTest.musicUtterance_rendersCompactNoteRow`: a MUSIC utterance spanning 0–42 s renders one compact row "♪ Nhạc · 0:42" (testTag `music_row`), with no translation line and no language badge.
  - `LiveScreenTest.sameLanguage_hidesTranslationLine`: utterance `("hello", EN, "en", "hello")` gives exactly one `"hello"` node.
  - `LiveScreenTest.partialShownLast`: `partial = "hel"` puts the testTag `partial` node at the bottom.
  - `LiveScreenTest.recreateActivity_keepsUtterances`: after `scenario.recreate()`, `"chào buổi sáng"` is still displayed.
  - `LiveScreenTest.failedTranslation_showsMarker`: `translationFailed = true` shows `R.string.translation_failed`.
  - `LiveScreenTest.emptyState_shown`: no utterances while Idle shows testTag `empty`.
  - `LiveScreenTest.scrolledUp_newUtterance_doesNotAutoScroll_showsJumpChip`: with 30 utterances, scroll to 0 and append one. Index 0 stays visible and `R.string.new_sentence` is displayed. Clicking it shows the last item.
  - `LiveScreenTest.atBottom_newUtterance_autoScrolls`: the new item is displayed.
  - `LiveScreenTest.runningStatus_saysInternalAudio`: with `state = Running`, the text `R.string.status_running_internal` ("Đang nghe âm thanh trong máy · mở video bất kỳ") is displayed.
  - `LiveScreenTest.firstStart_showsConsentExplainer_once`: with `consentExplainerSeen=false`, tapping Bắt đầu shows the explainer sheet (testTag `consent_explainer`). Tiếp tục calls `CaptureConsent.request` and sets the flag. On the second Start, `request` is called directly with no sheet.
  - `LiveScreenTest.consentDenied_showsRetry`: when the fake consent calls `onDenied`, `R.string.consent_denied` and a Thử lại button are displayed. Thử lại calls `request` again.
  - `LiveScreenTest.captureBlocked_showsBanner`: with `captureWarning = BLOCKED_BY_APP`, the banner shows `R.string.capture_blocked` and the session stays Running.
  - `LiveScreenTest.exitReasonBanner_shown`: with a fake `ExitReasonReporter` returning `R.string.exit_memory`, the banner text is displayed once and is dismissible.
  - `SettingsScreenTest.about_explainsUncapturableSources`: Settings → Giới thiệu → "Vì sao không có phụ đề?" shows the rows for DRM apps, Brave and calls.
  - `LiveScreenTest.backgroundWarning_whenBatteryOptimized`: with fake `BackgroundStatus(true, false, true)`, the warning card is displayed, and tapping "Sửa" opens `backgroundsetup`.
  - `LiveScreenTest.cardSemantics_mergedForTalkBack`: `hasContentDescription("good morning", substring = true)` and `"chào buổi sáng"` are on the same merged node.
  - `LanguagePickerScreenTest.pickUndownloaded_startsDownload_keepsOldTarget`: picking `ja` (NotDownloaded) calls `download("ja", wifiOnly=true)`, and `targetLanguage` stays `"vi"` until the fake pack becomes `Ready`. Then it is `"ja"`.
  - `SettingsScreenTest.deletePack_requiresConfirm`: tapping the delete button for `ja` shows the dialog, and `delete("ja")` is called only after the dialog's confirm.
  - `SettingsScreenTest.cannotDeleteInUsePacks`: the rows for `en`, `zh` and the current target show `R.string.in_use` and no delete button.
  - `SettingsScreenTest.overlaySection_showsAllControlsWithDefaults`: the nodes for opacity ("30%"), text size, lines, show source, position (Dưới selected), auto-hide (8 s) and lock exist, as does the preview box (testTag `overlay_preview`).
  - `SettingsScreenTest.overlayScreen_entersSetupMode_andLeaves`: navigating to the Phụ đề nổi screen calls `overlayController.setSetupMode(true)`, and pressing back calls `setSetupMode(false)`.
  - `SettingsScreenTest.overlayOpacity_sliderShowsRealOpacityWhileDragging`: while the slider is dragged to 0, the overlay mode is TRANSLATING with alpha 0 and the outline is still shown. After release and 2 s, the mode is SETUP.
  - `SettingsScreenTest.overlayNoPermission_showsInAppPreview`: with `overlayGranted=false`, the testTag `overlay_preview` card is displayed and `setSetupMode` is not called.
  - `SettingsScreenTest.overlayControlsDisabled_withoutPermission`: with `overlayGranted=false`, the opacity slider is not enabled, and the "Cấp quyền" button is displayed.
  - `SettingsScreenTest.autoStop_optionsAndDefault`: the "Chạy nền" section shows Tự dừng with "30 phút" selected and options Không/10/30/60.
  - `LiveScreenTest.autoStartExtra_requestsConsentOnce`: launching `MainActivity` with `EXTRA_AUTO_START=true` and the fake session Idle calls `CaptureConsent.request` exactly once (after the explainer if it hasn't been seen), and after `recreate()` still once.
  - `LiveScreenTest.compactHeight_startStopIsFab_andListGetsSpace`: in an 800×360dp override, the testTag `start_stop_fab` exists, the 64dp bottom bar does not, and ≥ 4 utterance cards (single-line) are displayed.
  - `LiveScreenTest.rotation_keepsScrollPosition`: scroll to index 10 of 30, then `scenario.recreate()` with a landscape override. Index 10 is still the first visible item.
  - `SettingsScreenTest.deleteDialog_survivesRotation`: open the delete-pack dialog, then `scenario.recreate()`. The dialog is still displayed.
  - `LiveScreenTest.consentResult_afterRecreate_startsSession`: open the fake consent, `recreate()`, then deliver RESULT_OK through the `ActivityResultRegistry` test double. `TranslationService.start` is called once.
  - `LiveScreenTest.landscape_contentMaxWidth720`: in a 1000×400dp override, the list's width is 720dp and it is centered.
  - `SettingsScreenTest.themeSwitch_appliesImmediately`: choosing Sáng sets `themeMode == LIGHT`, and the background node color equals the light `background` token.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*LanguageListTest"` and `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=io.github.thanhngochoang.voicetrans.feature.ui`. Expected: FAIL.
- [ ] **Step 3:** Implement per the UI spec, including "Orientation & responsive". Use `currentWindowAdaptiveInfo().windowSizeClass` for **height** (compact → FAB layout). Layout uses `WindowInsets.safeDrawing` (+ `displayCutout`) padding, max width 720dp centered, and a single pane at every width (API 37 Fold must not letterbox). Never declare `screenOrientation` or `configChanges`; rely on saved state.
- [ ] **Step 4:** Rerun. Expected: PASS. Then run the UI spec's "Acceptance checks" manually on the phone.
- [ ] **Step 5:** `git commit -m "feat: live subtitles, settings and translation-language picker"`

### Task 11: Device acceptance (README §9) on Android 17 / One UI 8.5

**Files:**
- Create: `docs/acceptance/2026-11-android17-oneui85.md` (results table: criterion, measured value, pass/fail, date)
- Create: `app/src/androidTest/assets/wav/lang_badge_set/` (≥ 20 EN and ≥ 20 ZH short WAVs, generated with `say` as in Task 1 from varied sentences)
- Test: `<atest>/LanguageBadgeAccuracyTest.kt`

- [ ] **Step 1:** Write `LanguageBadgeAccuracyTest`. It runs every WAV in `lang_badge_set` through `SherpaAsrEngine` and then `CjkRatioLanguageDetector`, and asserts that ≥ 95% match the folder name (`en/` or `zh/`). Run it. Expected: PASS.
- [ ] **Step 2:** Build and install with `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`. Download the models, then enable airplane mode. Play a downloaded/offline EN video and a ZH video (e.g. saved in the Gallery or YouTube offline downloads). Read the latency with `adb logcat -s VT-Latency`. Expected: every value < 1000 ms (README criteria 1 and 2).
- [ ] **Step 3: Endurance.** Set Battery to Unrestricted, start a session, and play a 30-min EN video full-screen with the floating subtitle on. Then repeat with an audio-only podcast app playing with the screen off. Then check:
  - `adb shell dumpsys activity services io.github.thanhngochoang.voicetrans`. Expected: the service is still foreground.
  - `adb logcat | grep -E "MemoryLimiter|MediaProjection"`. Expected: no kill or stop lines.
  - The utterance list keeps growing over the whole 30 min.
  - Repeat once with Battery set to **Optimized** (One UI's default) and record whether One UI kills the service. That tells you whether the Settings guidance is mandatory.
- [ ] **Step 4:** Run `adb shell am memory-limiter status` during a session and record the app's limit next to its PSS (`adb shell dumpsys meminfo io.github.thanhngochoang.voicetrans`). Expected: PSS is well under the limit.
- [ ] **Step 5: Consent and blocked apps.**
  - Cancel the consent dialog. Expected: the message and a retry button.
  - Stop sharing from the status-bar chip mid-session. Expected: a `PROJECTION_ENDED` notification, and no crash.
  - Play a DRM app (Netflix) and a YouTube video in **Brave**. Expected for both: the blocked-capture banner and overlay message within 5 s. The same video in Chrome or Samsung Internet translates normally.
  - With headphones connected, capture still works.
  - Play a music video (song with vocals) and then a vlog with background music. Expected: the song shows "♪ Nhạc" and "♪ Đang phát nhạc" with no translated lyrics, and the vlog speech is translated.
- [ ] **Step 6: Network.** In the Android Studio Network Inspector, a 5-minute session shows zero requests (README criterion 6).
- [ ] **Step 7:** Fill in `docs/acceptance/2026-11-android17-oneui85.md` and `git commit -m "test: Android 17 / One UI 8.5 acceptance results"`.
