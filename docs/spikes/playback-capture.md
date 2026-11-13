# Playback-capture spike (plan Task 1, Step 6)

Status: **needs the user's Android 17 phone** (a person must tap the consent dialog and play audio). The spike code is ready; rows below marked TODO are not yet measured.

## How to run (on the Android 17 phone)

1. Connect the phone (USB debugging), take the device lock (`AGENTS.md`).
2. `scripts/run-capture-spike.sh --label youtube` installs the debug APK and opens `CaptureSpikeActivity` (debug build only). Start English audio (YouTube app) first, then tap **Start now** in the consent dialog. It records 10 s and pulls `spike-out/youtube.wav` and `spike-out/youtube.txt` (verdict, AudioRecord state, `allowedCapturePolicy` of the active playbacks).
3. Repeat per app with `--no-install --label chrome|brave|samsung-internet|samsung-video|netflix`. For (a) also try `--rate 48000 --ch 2`; for (b) try `--mic`.
4. Watch the status bar during each run for (c). Feed the WAV through the `SherpaSmokeTest` decoder for (f).

## Checklist

| # | Question | Android 17 phone | Emulator API 35 (arm64) |
|---|---|---|---|
| a | 16 kHz mono PCM16 accepted, or is 48 kHz stereo required? | TODO | Accepted: `AudioRecord` state INITIALIZED, recording state RECORDING, 10.0 s / 160 000 samples read (16 kHz mono PCM16). Nothing was playing, so the WAV is silent (expected). 48 kHz stereo not tried. |
| b | FGS type `mediaProjection` alone, or `\|microphone` needed? | TODO | `mediaProjection` alone: `startForeground` OK, `getMediaProjection` OK, capture ran. `\|microphone` not tried. |
| c | Does One UI show the green mic privacy dot (should not)? | TODO | Not observable on the emulator (no One UI). |
| d | Non-silent WAV for YouTube / Chrome / Brave / Samsung Internet / Samsung Video; silent for a DRM app (Netflix)? Record `allowedCapturePolicy` reported for Brave (does `PlaybackProbe.blocksCapture` detect it, or only the zeros-while-playing rule?) | TODO | Not testable (no video apps, no sound source). **If YouTube is silent on the phone: stop, the product premise fails on that device.** |
| e | What does the consent dialog offer (single app vs entire screen), and does the default-display config force entire screen? | TODO | With `createConfigForDefaultDisplay()` the dialog title is "Start recording or casting with VoiceTrans VI?" and offers a spinner pre-set to **Entire screen** (AOSP SystemUI, API 35). One UI 8.5 may differ. |
| f | `adb pull` of the WAV fed through the smoke-test decoder gives text? | TODO | Not applicable (silent WAV). |
| g | Audio-tagging model choice (sizes below; latency per 1 s window on the phone is TODO) | TODO latency | Sizes recorded below. Final choice deferred to Task 4A. |

Emulator results are API 35 only. Android-17-specific behaviour (consent flow, FGS rules, One UI privacy indicator, app capture policies) can only be confirmed on the phone.

## (g) Candidate audio-tagging models

From the sherpa-onnx `audio-tagging-models` release (all share the 527-class AudioSet `class_labels_indices.csv`, 14 675 bytes).

| Model | Download (tar.bz2) | `model.int8.onnx` | `model.onnx` (fp32) | Latency per 1 s window (phone) |
|---|---|---|---|---|
| `sherpa-onnx-ced-tiny-audio-tagging-2024-04-19` | 28 531 989 B (27.2 MB) | 6 133 417 B (5.8 MB) | 22 256 662 B | TODO |
| `sherpa-onnx-ced-mini-audio-tagging-2024-04-19` | 47 658 399 B (45.5 MB) | 10 451 715 B (10.0 MB) | 39 062 294 B | TODO |
| `sherpa-onnx-zipformer-small-audio-tagging-2024-04-15` | 111 034 072 B (105.9 MB) | 27 038 066 B (25.8 MB) | 91 932 865 B | TODO |

Not added to `models.json` or `scripts/fetch-models.sh` yet: Task 4A picks the smallest model that passes its accuracy test and adds it then.

## Spike code

- `app/src/debug/java/io/github/thanhngochoang/voicetrans/CaptureSpikeActivity.kt`: consent request (`MediaProjectionConfig.createConfigForDefaultDisplay()` on API 34+), starts the FGS.
- `app/src/debug/java/io/github/thanhngochoang/voicetrans/CaptureSpikeService.kt`: calls `startForeground` (type `mediaProjection`, or with `|microphone` via `--mic`) before `getMediaProjection`, records 10 s with `AudioPlaybackCaptureConfiguration` (usages MEDIA, GAME, UNKNOWN) to `filesDir/spike.wav`, and writes `filesDir/spike.txt`.
- `app/src/debug/AndroidManifest.xml`: debug-only permissions, activity and service (declares `mediaProjection|microphone` so both can be tested). Not in release builds.
