# AGENTS.md

Shared guidance for every coding agent working in this repository (Claude Code, Google Antigravity, and others). `CLAUDE.md` imports this file, and Antigravity reads it directly. **Edit the rules here, not in `CLAUDE.md`.**

## Project status

VoiceTrans VI: an offline Android app that captures English or Mandarin speech **playing on the phone** (videos), transcribes it, and translates it (Vietnamese by default). **Implementation is in progress**: track it on `.coord/board.md` and in the plan `docs/superpowers/plans/2026-11-07-voicetrans-android17-mvp.md`. `README.md` is the original spec (Vietnamese); where it conflicts with the decisions below or the plan, the decisions and the plan win. Still open: app name/package (placeholders `VoiceTrans VI` / `io.github.thanhngochoang.voicetrans`).

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
- **`connectedDebugAndroidTest` uninstalls the app and the test APK when it finishes**, which wipes app data, including the ML Kit translation packs downloaded for the app. A test that needs packs must download them in the same run, and an offline (airplane-mode) check must go: `./gradlew :app:installDebug :app:installDebugAndroidTest`, an online `adb shell am instrument -w -e class <Test> io.github.thanhngochoang.voicetrans.test/androidx.test.runner.AndroidJUnitRunner` to download, `adb shell cmd connectivity airplane-mode enable`, then `am instrument` again for the offline tests. Always `airplane-mode disable` afterwards and release the device lock.
- The Bash tool has no `timeout` command; use background runs with a monitor instead.

## Git and multiple agents (mandatory)

Two agents may work on this repo **at the same time**, for example Claude Code and Google Antigravity. They must never share a working directory, and **nothing reaches GitHub unless the user asks for it.**

### Pushing to GitHub
- **Never `git push`, open a PR, or merge into `main` unless the user explicitly asks in the current conversation.** An earlier "commit lên" or "push" covers that one time only, not later work.
- Commit **locally** on your own branch. The plan's per-task "Commit" steps mean a local commit. Batch the work; don't push after every task.
- Never force-push, never rewrite commits that are already on `origin`, and **workers** never commit on `main`. On `main` only the coordinator commits, and only merges plus docs/coordination changes (AGENTS.md, plan, spec, scripts).

### Isolation: one worktree and one branch per agent
- Each agent works in its own git worktree inside the project, so the sandbox can write to it:
  `git worktree add .worktrees/<lane> -b agent/<lane>` (`.worktrees/` is gitignored). If an agent has built-in worktree isolation (Claude Code), that works too, as long as the worktree is not shared.
- **Antigravity:** open the worktree folder (for example `.worktrees/lane-b`) as the Antigravity workspace, **never the repo root** while another agent works there. Antigravity reads this `AGENTS.md` from the worktree. In Antigravity's terminal settings, keep `git push` (and `git push --force`, `gh pr create`) **out of** the auto-run allow list, or add them to the deny list, so a push always needs a human click.
- `local.properties` is gitignored, so **copy it into each new worktree**, or Gradle can't find the SDK.
- Only touch your own worktree and branch. Never check out, rebase, reset, commit to or delete the other agent's branch or worktree.

### Roles
- **Coordinator: one Claude Code session in the repo root** (the main worktree, on `main`). It:
  - assigns plan tasks to lanes on the board and keeps the board's dependencies and statuses true;
  - reviews each task a worker marks `review`: reads the diff against the plan's Interfaces block, runs that task's tests in the lane worktree, then sets `done` or sends it back with notes;
  - **merges lane branches into the local `main`** at the sync points (below), only after the full JVM test suite passes on the merged result, and then tells the lanes to rebase;
  - decides `INTERFACE CHANGE` requests. It asks the user when a change alters the plan or the UI spec;
  - **never pushes** unless the user asks, and never writes feature code in the root. Feature code is written only in lane worktrees.
- **Workers: one per lane, each in its own worktree.**
  - **Lane A:** Claude Code (a second session, or a subagent the coordinator starts with worktree isolation) in `.worktrees/lane-a`.
  - **Lane B:** Google Antigravity with `.worktrees/lane-b` opened as its workspace.
  - Workers only take tasks the board assigns to their lane, in order. They never edit another lane's rows or decide merges.

### The board: `.coord/board.md`
- It lives in the **main repo root**, in `.coord/`, which is gitignored and never committed. It is outside `.git/` because Claude Code's sandbox blocks writes there. From any worktree, find it with:
  `COORD="$(git rev-parse --path-format=absolute --git-common-dir)/../.coord"`
- Only the coordinator adds rows, changes the `Lane` column, or sets `done`. Workers move their own rows `assigned → in-progress → review` (or `blocked`) and fill `Notes`.
- **Worker loop:**
  1. Read the board.
  2. Take the first row assigned to your lane whose `Deps` are all `done` **or are your own lane's rows in `review`** (don't wait idle for the coordinator's review; it runs in parallel and sends `FIX` if needed). Set it to `in-progress` with the time.
  3. Implement the task exactly as the plan says (TDD steps), then make a local commit on your branch.
  4. Set the row to `review`, with a handoff note in `Notes`: what changed, anything the other lane must know, and the test command you ran.
  5. Re-read your inbox (a `FIX` there comes before new work), then go back to step 1. Stop only when nothing assigned is ready, and say so. Don't start unassigned work.
- **Inbox:** the coordinator sends each lane instructions in `$COORD/inbox/<lane>.md` (append-only, newest message last, with `STATUS: WAIT | GO | STOP | FIX`). **Workers read their inbox at the start of every task and obey the latest STATUS.** `STOP` means finish nothing more, commit what is consistent, and report. `FIX` lists corrections to apply before continuing.
- **Interface changes:** if a task can't follow its plan Interfaces block, the worker writes `INTERFACE CHANGE: <what/why>` in Notes, sets the row to `blocked`, and **stops**. Only the coordinator unblocks it.
- **Sync points**, where the coordinator merges into the local `main` and the lanes rebase:
  1. after Task 1 (done by Lane A in `.worktrees/lane-a`), before Lane B's worktree is created, so Lane B starts from the scaffold;
  2. Task 6 needs Lane B's Tasks 2 and 5, so merge Lane B before Lane A starts 6;
  3. Task 10 needs Lane A's Tasks 6, 7 and 9, so merge Lane A before Lane B starts 10;
  4. Task 11 runs after everything is merged.
- **Hot shared files** both lanes may edit: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `res/values*/strings.xml`, `di/AppModule.kt`, `assets/models/models.json`, `scripts/fetch-models.sh`.
  - Keep edits to them minimal and additive, and don't reorder or reformat existing lines.
  - Put them in a separate commit (`chore(shared): …`) so conflicts are easy to resolve.
- `scripts/agents-status.sh` prints worktrees, how far each branch is ahead of or behind `main`, the board, and the device-lock holder. The coordinator runs it at the start of every coordination turn.

### Shared machine resources
- **Physical phone / emulator:** only one agent may install the APK or run `connectedAndroidTest` at a time. Take the lock with `mkdir "$COORD/device.lock"` and write your lane name into `$COORD/device.lock/owner`. If that fails, the other agent holds it, so wait and retry. Release it with `rm -rf "$COORD/device.lock"` when done, including after a failure. Pure JVM tests (`testDebugUnitTest`) need no lock. Lane B should prefer the arm64 emulator for Compose UI tests, and keep the phone for capture/ASR/device checks.
- **Don't run `./gradlew --stop`** (it kills the other agent's daemon and build), `adb kill-server` or `adb uninstall`, and don't clear the app's data while another agent may be testing.
- `~/.gradle` (caches, JDKs) is shared and safe for concurrent builds. Each worktree has its own `build/` output.

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
