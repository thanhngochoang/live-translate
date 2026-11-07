# Lyrics mode (Lời bài hát): phase 2 design

Status: **phase 2, after the MVP.** The MVP already detects SINGING and shows "♪" without translating (plan Task 4A). This mode adds a best-effort translation of song lyrics. The user chose a dedicated mode over translating songs in the normal pipeline (2026-11-07).

## Why a separate mode

- **Singing is much harder than speech.** Whisper on classical songs: WER 0.56 sung vs 0.14 for the same lyrics spoken ([Lyrics Transcription in Western Classical Music with Whisper](https://aclanthology.org/2024.nlp4musa-1.3.pdf)). Our streaming Zipformer is trained on speech only and will do worse.
- **Separating vocals before ASR mostly helps segmentation, not accuracy.** Isolated vocals (demucs) *degraded* Whisper in most cases, but gave better segmentation ([Exploiting Music Source Separation for ALT with Whisper](https://pith.science/paper/2506.15514)).
- **Separation is offline-only.** sherpa-onnx source separation (`OfflineSourceSeparation`) processes whole clips, so the < 1 s latency target can't hold. A song mode can accept 5–10 s latency, which speech subtitles can't.
- **Lyrics lookup by fingerprint** (Shazam-like) needs the network and licensed lyric databases, so it's out because the app is offline.

## Pipeline (lyrics mode only)

```
capture (16 kHz mono) ──► buffer 8 s windows, 2 s overlap
      └► Spleeter 2-stem fp16 (vocals.fp16.onnx 19 MB; accompaniment model not needed)
             └► VAD on vocals → line segments
                    └► offline ASR on each segment (candidate: Whisper tiny/base int8 via sherpa-onnx, EN+ZH)
                           └► LanguageDetector → Translator (same as MVP)
                                  └► overlay/Live, lines tagged "♪"
```

Reference speeds (from sherpa-onnx docs, unnamed CPU, 1 thread):
- Spleeter fp16: RTF 0.07–0.08.
- On an RK3588 Cortex-A76: RTF 0.26 (1 thread) to 0.13 (4 threads).
- UVR MDX-Net (28–64 MB) is about 10× slower than Spleeter, so it's ruled out for phones.

## UX

- **How to turn it on:**
  - A toggle chip in Live's top bar: "Lời nói | Lời bài hát".
  - A suggestion in the overlay and Live when SINGING has lasted for ≥ 20 s: "Đang phát bài hát — bật chế độ Lời bài hát?" [Bật].
- **What changes in the UI:**
  - Each line is prefixed "♪", and lines appear about 8 s behind the audio. The UI says "Phụ đề lời bài hát có độ trễ ~8 giây và có thể sai".
  - Speech subtitles are paused while lyrics mode is on. Switching back is one tap.

## Open questions to settle before implementing

1. Is the sherpa-onnx Kotlin/Android API available for `OfflineSourceSeparation`? The docs show only C++ and Python. If not, a JNI binding or a direct ONNX Runtime call is needed.
2. Which offline ASR to use for sung EN/ZH, and is it small enough? Measure Whisper tiny vs base int8 WER on a 20-song test set, and RTF on the phone.
3. What does it cost in battery and heat to run separation + ASR continuously for a 4-minute song?
4. Is the extra model download (about 40–150 MB) worth it? It could be an optional download in Settings → Gói ngôn ngữ.

## Sources

- sherpa-onnx source separation: https://k2-fsa.github.io/sherpa/onnx/source-separation/index.html
- sherpa-onnx audio tagging: https://k2-fsa.github.io/sherpa/onnx/audio-tagging/index.html
- Lyrics Transcription for Humans (benchmark): https://arxiv.org/pdf/2408.06370
