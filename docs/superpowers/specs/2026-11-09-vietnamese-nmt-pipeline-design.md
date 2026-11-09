# Design: Vietnamese-only pipeline with SenseVoice ASR and dedicated NMT

Date: 2026-11-09. Status: draft for approval. Evidence: `docs/spikes/translation-quality.md`.

## 1. Intent

Subtitles must say what the video says. Today they mostly do not. The spike measured the current pipeline and found two failures stacked on each other:

- **ASR.** The streaming Zipformer bilingual zh-en (2023) model outputs ALL CAPS English with no punctuation and many invented words on accented speech.
- **Translation.** Since commit `baf6934`, every Vietnamese-target line goes to Qwen2.5 0.5B through `HybridTranslator`. It scored 9/54 on the test suite: it invents content, answers like a chatbot, copies English through, leaks labels, and can crash the process (`GGML_ASSERT`, prompts over `n_batch` 512).

The user's direction (2026-11-09): translation must be correct and optimized for Vietnamese, other target languages may be dropped, and dedicated translation models are preferred over LLMs.

## 2. Goals

1. **The target is always Vietnamese.** Sources stay English and Mandarin.
2. **Correct translations.** On the spike suite, on-device output must equal the reference output of the chosen models (section 8), which scored 44/54 against 9/54 today.
3. **Fast enough.** From an ASR Final to its translation on screen: under 1 s at p95 for lines of up to 20 words, on the S25, in airplane mode. End to end, a subtitle appears within 2 s at p95 after the sentence's audio ends.

**Correctness wins over speed** (Claude's call, from the user's "cần dịch được"). A latency miss does not block the work. It triggers tuning in this order: thread counts for NMT and ASR, then beam 2 if the hand score on the spike suite stays within 1 point of beam 4. Greedy decoding is not allowed, because it dropped words in the spike.
4. **Better transcripts.** Replace Zipformer with SenseVoice so source lines are cased and punctuated.
5. **Fully offline.** No network at runtime and no first-run downloads. All models ship in the APK.

## 3. Non-goals

- Target languages other than Vietnamese, and a target-language picker.
- Context-aware translation. The NMT models translate one sentence at a time, so `Translator` loses its `to` and context parameters (section 7.6).
- Fine-tuning or training any model (section 10, phase 2).
- Shrinking NLLB's vocabulary, and fp16 conversion (phase 2).
- The macOS app. It keeps its current engines for now. It is a separate Swift package (`macos/Packages/VoiceTransCore`) and Gradle builds only `:app`, so Kotlin interfaces can change freely without breaking it.
- Cantonese, Japanese or Korean sources, even though SenseVoice can recognize them.

## 4. Anti-goals

Any of these makes the work a failure even if it builds and runs:

- Fluent Vietnamese that says something the source did not (the LLM failure mode).
- Chinese characters, pinyin, `VI:`/`EN:` labels, markdown or quotes in a subtitle.
- A crash or ANR caused by the translator or its native code.
- Models stored twice on the device (APK asset plus an extracted copy), as `LlamaContextTranslator` does today.
- Any network request while a session runs.

## 5. Decisions

### Made by the user

- Dedicated NMT models, not an LLM.
- Vietnamese is the only target. Other targets are removed.
- The YouTube Short `fqyOhpYWEZw` is the reference real-world test clip.
- **The app is for personal use only,** so NLLB's CC-BY-NC-4.0 license is acceptable. If that ever changes, English moves to VinAI en2vi (AGPL-3.0).
- **ML Kit is removed completely,** together with the ModelSetup download flow, the target picker and the Google Play Services dependency. There is no ML Kit fallback.
- **Each build machine exports the models itself** with `scripts/export-nmt.py`. Nothing is hosted or uploaded.
- **Draft translation while speaking** (added after approval, same day): show a translation in real time while the sentence is spoken, then re-translate the whole sentence when it ends (section 7.6). This replaces the old rule "only `AsrEvent.Final` is translated".

### Made by Claude from the spike (check these while reading)

- **English → Vietnamese: `facebook/nllb-200-distilled-600M`,** ONNX, int8 dynamic per-channel quantization, beam 4.
- **Chinese → Vietnamese: `Helsinki-NLP/opus-mt-zh-vi`,** ONNX, **fp32, not quantized** (int8 measurably changed 3 of 10 lines), beam 4.
- **ASR: SenseVoice int8 `2024-07-17`** through sherpa-onnx `OfflineRecognizer` (the 2025-09-09 build is worse). The existing Silero VAD cuts segments.
- **Remove Qwen and llama.cpp entirely,** including native libraries and the GGUF asset.
- **Keep the CJK-ratio `LanguageDetector`** to route each sentence. SenseVoice's language tag is not used for routing.
- **One subtitle row per sentence.** An ASR Final that contains several sentences becomes several rows.

## 6. Architecture

```
playback capture (16 kHz mono float)
  → Silero VAD (segments)
  → SenseVoice offline recognizer  → Partial (shown, draft-translated) / Final (cased, punctuated)
  → sentence splitter              → one Utterance per sentence
  → CjkRatioLanguageDetector       → EN | ZH
  → NmtTranslator                  → EN: NLLB-600M int8   |  ZH: OPUS-MT zh-vi fp32
  → CjkFilter.purgeCjk (safety net) → Compose UI and floating overlay
```

The sequential translation queue, the junk-Final filter, the music/singing gate and the per-session lifecycle in `TranslationSession` stay as they are.

## 7. Components

### 7.1 SenseVoice ASR (`SenseVoiceAsrEngine : AsrEngine`)

- Built on `com.k2fsa.sherpa.onnx.OfflineRecognizer` with `OfflineSenseVoiceModelConfig` (present in the bundled sherpa-onnx 2.0.1 AAR), `useInverseTextNormalization = true`, language `auto`, `numThreads = 2`.
- Silero VAD segments the stream. The spike used minimum silence 0.5 s and maximum speech 12 s. 12 s means continuous speech gets no subtitle for 12 s, so the builder lowers the maximum (around 6 to 8 s) and tunes it against the Short. Segment boundaries keep the 300 ms pre-roll behaviour.
- **Partials:** SenseVoice is not streaming. While a segment is open, the engine re-decodes the audio collected so far at a fixed cadence (around every 0.8 s) and emits `AsrEvent.Partial`. The exact cadence is the builder's call.
- **Budget** (Claude's call): the segment maximum is the largest value, at most 8 s, that keeps the 2 s end-to-end goal on the Short. The partial cadence must keep the 30-minute screen-off run from README §9 passing. If both cannot hold, drop partials to a slower cadence before raising the segment maximum.
- `AsrEvent.Final` keeps its contract: text plus stream-position start and end in ms.
- `models.json` gains the archive `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` (`model.int8.onnx`, `tokens.txt`) with checksums. The Zipformer entries and files are removed.
- Pitfall seen in the spike: copy a VAD segment's samples before `pop()`, or the segment is empty.

### 7.2 Sentence splitter

Splits a Final on `. ! ?` followed by whitespace and on `。！？`, keeping the punctuation with its sentence. Fragments with no letters are dropped. Each sentence becomes its own `Pending`/`Utterance` with the parent Final's timestamps. The existing `isJunkFinal` check runs per sentence.

### 7.3 Translation (`NmtTranslator : Translator`)

- Routes by `SourceLang`: `EN` → NLLB engine, `ZH` → OPUS engine. Ignores the `to` argument and the context list.
- Both engines are encoder–decoder models run with ONNX Runtime on CPU, 4 intra-op threads, created once and released in `close()`.
- **Decoding must reproduce Hugging Face `generate()`** closely enough that outputs match the reference (section 8):
  - beam search with 4 beams, length penalty 1.0, early stopping, `max_new_tokens` 128;
  - NLLB: source is `eng_Latn` + text tokens + `</s>`. The decoder starts with `</s>` (id 2) and the first generated token is forced to `vie_Latn`;
  - OPUS: source is text tokens + `</s>`. The decoder starts with pad (id 65000), and pad is banned as an output token (`bad_words_ids`).
- **Tokenizers:** SentencePiece. OPUS encodes with `source.spm` and maps pieces through `vocab.json`, and decodes with the same vocabulary. NLLB uses `sentencepiece.bpe.model` with the fairseq id offset and its language tokens. The builder chooses the implementation (pure Kotlin or native). Token ids must be identical to the Hugging Face tokenizers (section 8).
- **ONNX Runtime:** the sherpa-onnx AAR already ships `libonnxruntime.so` (version 1.28.2) for arm64. The app must not package a second, different copy. Using `com.microsoft.onnxruntime:onnxruntime-android` of the **same version** with `packaging.jniLibs.pickFirsts`, or a small JNI layer over the ORT C API, are both acceptable. The builder verifies that sherpa-onnx still works with the chosen library.
- **Model loading:** models are bundled under `assets/models/nmt/`, uncompressed (`noCompress` already covers `onnx`). They must not be stored twice on the device and must not be read into the Java heap. How to get from an uncompressed asset to an ORT session within those limits is the builder's call.
- **Output:** the Vietnamese string, trimmed. `TranslationSession` already passes every translation through `CjkFilter.purgeCjk` and marks output with no letters as failed (commit `b0ac4c0`), and that stays.
- **Startup:** both engines load in the background when the app starts. Starting a session waits for them. The Live screen shows a short "loading models" state instead of today's ModelSetup gate.

### 7.4 Model export (`scripts/export-nmt.py`)

A reproducible script replaces hand steps:

- exports both models with optimum-onnx (`text2text-generation-with-past`);
- produces one decoder per model that supports both the first step and the cached steps (a merged decoder, or decoder plus decoder-with-past). The NLLB merge step failed in the spike, so the builder resolves it or ships the two-file form;
- quantizes NLLB with `quantize_dynamic(weight_type=QInt8, per_channel=True)` and leaves OPUS in fp32;
- writes the files to `app/src/main/assets/models/nmt/`, gitignored like the other large models, with checksums recorded in `models.json`;
- writes the reference outputs used by section 8.

`scripts/fetch-models.sh` runs this script when the NMT files are missing or fail their checksums. The script pins its Python dependencies (optimum-onnx, transformers, torch, onnxruntime) in a requirements file and runs in its own virtual environment. It downloads only the upstream Hugging Face checkpoints, and its output is deterministic enough for the recorded checksums to hold across machines. If checksums turn out to differ between machines, the builder records per-file checksums as advisory and relies on the goldens instead.

### 7.5 Removals

- Qwen/llama: `feature/translate/llama/*`, `feature/translate/hybrid/HybridTranslator.kt`, `ContextPromptBuilder` and its test, `app/src/main/cpp/llama_jni.cpp`, `app/src/main/jniLibs/`, the GGUF asset and its gitignore line, the `gguf` `noCompress` entry, `scripts/build-llama-android.sh` and `scripts/build-llama-jni-android.sh`, and `scripts/fetch-qwen-model.sh` if nothing else uses it.
- ML Kit: `MlKitTranslator`, `PackManager`, `ModelState` where unused, the ModelSetup screen and view model, the language picker and `TargetPicker`, the `mlkit.translate` and `play.services` coroutine dependencies if unused, and the Wi-Fi-only download setting.
- Settings: the target-language key is removed from `SettingsRepository`. `Utterance.targetLang` is always `vi`.
- `di/AppModule.kt` binds `Translator` to `NmtTranslator` and `AsrEngine` to `SenseVoiceAsrEngine`.

### 7.6 Draft translation while speaking

Added at the user's request ("dịch từng từ real time, nhưng sau khi hết câu có thể dịch lại cả câu hoàn chỉnh"). The parts marked as Claude's call are open to change.

- **What is drafted:** the whole partial text heard so far, not single words. Word-by-word Vietnamese is unreadable, so each draft re-translates the growing sentence (Claude's call).
- **Two modes:** `TranslateMode.DRAFT` uses greedy decoding and `max_new_tokens` 64. `TranslateMode.FINAL` uses beam 4 as in section 7.3. The spike measured greedy at roughly half the cost of beam 4, with dropped words that are acceptable in a draft because the Final replaces it.
- **Interface:** `Translator.translate(text: String, from: SourceLang, mode: TranslateMode = TranslateMode.FINAL): Result<String>`. The `to` and context parameters go away.
- **Scheduling:** one NMT worker runs everything. A queued Final always runs before a draft. Drafts are latest-wins: a newer partial replaces a queued draft, and a running draft is never interrupted. A draft is requested only when the partial has at least 3 words (EN) or 4 characters (ZH), and has grown by at least 2 words or 3 characters since the last draft (Claude's call).
- **State:** `TranslationSession.partialTranslation: StateFlow<String?>`, passed through `CjkFilter.purgeCjk`. Blank drafts are not shown.
- **Hand-over:** when a Final arrives, the current draft stays visible until the first row of that Final gets its translation or fails. Then it is cleared, unless a newer partial has already produced a newer draft. A Final classified as music or singing clears the draft.
- **Display:** the Live screen shows the draft under the partial source line, in the partial style. The floating subtitle shows the draft instead of the partial source line and instead of today's "Đang dịch…" placeholder for Chinese.
- **Switch:** Settings gets "Dịch tạm khi đang nói" (EN: "Draft translation while speaking"), on by default, for battery (Claude's call).
- **Budget:** drafts must not push the Final p95 over 1 s. If they do, raise the growth step before anything else.

### 7.7 UI and docs

- The UI-language switch (Vietnamese/English) stays.
- `docs/design/ui-spec.md`: "Translation language (dịch sang)" becomes a fixed statement, the ModelSetup section becomes the loading state, and Settings loses the target row.
- `AGENTS.md`: the settled decisions about ML Kit and a user-selectable target, and the rule "only `AsrEvent.Final` is translated", are replaced by this design, and the architecture line and model notes are updated. The coordinator makes these edits.

## 8. Verification

- **Tokenizer goldens (JVM):** for each model, the token ids of the 20 spike lines plus a larger set (at least 300 EN and 300 ZH lines of subtitle-style text) equal the Hugging Face tokenizer ids. The ids are generated by the export script.
- **Translation goldens (device):** an instrumented test translates the 20 spike lines and compares with the reference output produced by the export script with ONNX Runtime on the same model files. The test passes when at least 18 of 20 lines are identical (Claude's call: int8 kernels and beam ties can differ between the Mac and arm64). Each differing line is printed in the test log so a person can check it is still a correct translation. Tokenizer ids, by contrast, must match exactly.
- **Latency (S25, airplane mode):** p50 and p95 of Final-to-translation for the golden lines, logged through the existing `VT-Latency` hook. p95 under 1 s for lines of up to 20 words.
- **ASR:** an instrumented test feeds license-clean EN and ZH clips (existing `androidTest` clips, or SenseVoice's bundled test WAVs if their license allows) and checks cased, punctuated output. The Short is checked by hand only and is never committed.
- **Draft translation (JVM):** session tests for Final-before-draft priority, latest-wins drafts, the growth thresholds, the hand-over rule and the switch. Overlay tests for the draft line.
- **Offline:** no network requests during a session (existing acceptance check).
- **Endurance:** the 30-minute screen-off run from README §9, watching memory. Expected resident memory is roughly ASR 0.3 GB plus NMT 1.5 to 2 GB.
- Follow the project's rule to verify on the emulator before the phone.

## 9. Sizes

| Item | Today | After |
|---|---|---|
| ASR model | Zipformer about 190 MB | SenseVoice 228 MB |
| Translation models | Qwen 469 MB in the APK, plus a 469 MB copy in `filesDir` | NLLB about 1.15 GB plus OPUS about 580 MB, stored once |
| Native libraries | llama.cpp about 74 MB | none extra (reuses sherpa-onnx's ONNX Runtime) |

The APK grows to roughly 2 GB. Phase 2 can roughly halve the translation models.

**Size check first** (Claude's call). An APK this large has not been tried. The first task builds, signs and installs a debug APK with dummy assets of the final size on the emulator and the S25. Shipping all models in the APK is a hard rule (AGENTS.md: models are bundled in APK assets). If the install fails, NLLB vocabulary trimming moves from phase 2 into this work, rather than moving models out of the APK.

## 10. Phase 2 (not in this work)

- Trim NLLB's 256k vocabulary to English and Vietnamese tokens, and try OPUS in fp16, re-running the goldens.
- Fine-tune OPUS-MT zh-vi on Chinese drama subtitles to fix lines like zh09.
