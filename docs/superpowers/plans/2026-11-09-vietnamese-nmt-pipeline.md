# Vietnamese NMT Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Zipformer ASR and the Qwen/ML Kit translators with SenseVoice ASR plus two dedicated on-device NMT models (NLLB-600M for English, OPUS-MT zh-vi for Chinese), Vietnamese as the only target, with a live draft translation while a sentence is spoken.

**Architecture:** VAD segments feed a SenseVoice offline recognizer that emits partials (re-decoded) and cased, punctuated finals. Finals are split into sentences and routed by the CJK-ratio detector to one of two ONNX Runtime encoder–decoder engines behind `NmtTranslator`. One NMT worker runs beam-4 final translations first and greedy drafts of the current partial when idle.

**Tech Stack:** Kotlin, Compose, Hilt, Coroutines/Flow, sherpa-onnx 2.0.1 AAR (ASR, VAD, and its bundled ONNX Runtime 1.28.2), `com.microsoft.onnxruntime:onnxruntime-android` 1.28.2 (Java API only), Python 3 with optimum-onnx for model export.

**Spec:** `docs/superpowers/specs/2026-11-09-vietnamese-nmt-pipeline-design.md`. Evidence and reference numbers: `docs/spikes/translation-quality.md`.

**Path shorthand:** `<pkg>` = `app/src/main/java/io/github/thanhngochoang/voicetrans`, `<test>` = `app/src/test/java/io/github/thanhngochoang/voicetrans`, `<atest>` = `app/src/androidTest/java/io/github/thanhngochoang/voicetrans`, `<assets>` = `app/src/main/assets/models`.

**Starting point:** `main` at `b0ac4c0` (Translator bound to `MlKitTranslator`, CJK threshold 0.40, `TranslationSession` already purges CJK from every translation).

## Global Constraints

- Target language is always `vi`. Sources are `EN` and `ZH`, routed by `CjkRatioLanguageDetector` (threshold **0.40**, as on `main`).
- English model: `facebook/nllb-200-distilled-600M`, ONNX, int8 dynamic **per-channel** quantization. Source prefix token `eng_Latn`, decoder start id 2 (`</s>`), first generated token forced to `vie_Latn`, eos 2, pad 1.
- Chinese model: `Helsinki-NLP/opus-mt-zh-vi`, ONNX **fp32, not quantized**. Decoder start id 65000 (pad), eos 0, pad 65000 banned as output (`bad_words_ids [[65000]]`), eos forced at max length.
- `TranslateMode.FINAL`: beam 4, `max_new_tokens` 128. `TranslateMode.DRAFT`: greedy, `max_new_tokens` 64. Greedy is never used for FINAL.
- ONNX Runtime: CPU, 4 intra-op threads, 1 inter-op thread. Exactly one `libonnxruntime.so` in the APK, version 1.28.2 (the one sherpa-onnx ships).
- Model files: each model ships an encoder and **one merged decoder** (with and without cache). Each `.onnx` is self-contained (no `.onnx_data`) and under 2 GB. Size targets: NLLB ≤ 1.2 GB, OPUS ≤ 650 MB.
- Models live in `<assets>/nmt/` and `<assets>/asr-sv/`, uncompressed (`noCompress` covers `onnx`), gitignored, produced by `scripts/export-nmt.py` (NMT) and `scripts/fetch-models.sh` (ASR). They are never copied to `filesDir` and never read into the Java heap.
- ASR: SenseVoice int8 `2024-07-17` only (`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`), `useInverseTextNormalization = true`, language `auto`, `numThreads = 2`. VAD maximum speech ≤ 8 s.
- Latency: Final → translation p95 < 1 s for lines ≤ 20 words; speech end → subtitle p95 < 2 s. Correctness wins over speed (spec §2).
- No network at runtime. The app has no download flow after this work.
- Licenses: personal use only (NLLB is CC-BY-NC-4.0). Do not commit third-party audio or the Short's transcript.
- Every user-visible string goes in `res/values` (vi) and `res/values-en`.
- **Git and lanes:** local commits only, never push. Hot shared files (`gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `res/values*/strings.xml`, `di/AppModule.kt`, `<assets>/models.json`, `scripts/fetch-models.sh`) get minimal additive edits in separate `chore(shared): …` commits. The physical phone is Lane A's only; the emulator is lock-guarded (`AGENTS.md`).

## Review Focus

1. **Session stopped while a native translation is running** (ORT `run` cannot be cancelled). Expected: `stop()` returns at once, the running call finishes in the background, `close()` waits for it before releasing sessions, nothing crashes. Test: `NmtTranslatorTest.closeDuringTranslate_waitsThenReleases` (Task 5).
2. **A long run-on Final with no punctuation** (SenseVoice's 12 s segments in the Short had 40+ words and one period). Expected: the whole line is translated, not cut at the token limit. Test: `NmtEngineGoldenTest.longLine_notTruncated` (Task 4).
3. **The ASR revises a partial so it gets shorter** (common with re-decoding). Expected: no new draft until the text grows past the threshold again, and the old draft stays visible. Test: `TranslationSessionDraftTest.shrinkingPartial_noNewDraft` (Task 7).
4. **The user taps Start while models are still loading** (first launch after install). Expected: the Live screen shows the loading state, the session starts translating once the engines are ready, and nothing is lost or crashes. Tests: `NmtTranslatorTest.translateBeforeReady_suspendsUntilReady` (Task 5) and `AppNavTest.loading_thenLive` (Task 9).
5. **Mixed-language lines** ("我们明天开meeting", "let's go to the 公司 now"). Expected: routed by the 0.40 ratio, the translation is non-empty, contains no Chinese characters and keeps the English word. Test: `NmtEngineGoldenTest.codeSwitchedLines_translated` (Task 4).

## Lanes and order

Assigned by the user on 2026-11-09: **Lane B (Antigravity) does the work.** The physical phone stays Lane A's only (user rule of 2026-11-07), so the phone steps are split out.

| Task | Lane | Depends on |
|---|---|---|
| 1 APK size check and big emulator (Steps 1–3, 5) | B | – |
| 1P APK size check on the S25 (Step 4) | A | 1 |
| 2 Model export script and goldens | B | – |
| 3 SentencePiece tokenizers | B | 2 |
| 4 ONNX seq2seq engine | B | 2, 3 |
| 5 `NmtTranslator` and interface change | B | 4 |
| 6 Sentence splitter | B | 5 |
| 7 Draft translation in the session | B | 5, 6 |
| 8 SenseVoice ASR engine | B | – |
| 9 UI: draft line, loading state, switch | B | 7, 8 |
| 10 Removals and docs | B (code), coordinator (docs) | 9 |
| 11 Device acceptance (Step 1 on the emulator: B; Steps 2–6 on the S25: A) | B, A | 10 |

Lane B works through the rows in order on `agent/lane-b`. The coordinator reviews each task and merges into `main` after Tasks 2, 5, 8 and 10. Task 11 runs on the fully merged `main`.

---

### Task 1: APK size check and a big emulator

**Files:** none committed. Results go in `docs/spikes/translation-quality.md` (new section "APK size check") and the board.

- [ ] **Step 1:** Create an arm64 AVD `vt-big-35` from `system-images/android-35/google_apis_playstore/arm64-v8a` with `disk.dataPartition.size=8G` (the current emulator has about 500 MB free). Take the emulator lock as `AGENTS.md` says.
- [ ] **Step 2:** In the lane worktree only, add a throwaway 2.1 GB file of random bytes as `<assets>/sizecheck/blob.onnx` (random so the APK cannot shrink it) and run `./gradlew :app:assembleDebug`.
- [ ] **Step 3:** Install with `adb -s <emulator> install -r app/build/outputs/apk/debug/app-debug.apk`, launch with `adb shell am start -n io.github.thanhngochoang.voicetrans/.MainActivity`, then check `adb shell pidof io.github.thanhngochoang.voicetrans`. Expected: `Success`, and a pid.
- [ ] **Step 4:** Repeat Step 3 on the S25 (Lane A only).
- [ ] **Step 5:** Delete the blob, confirm `git status` is clean, release the lock, and record both results. If either install fails, set the board row to `blocked` with `INTERFACE CHANGE: APK too large, NLLB vocabulary trimming needed` and stop. The coordinator then adds a trimming task before Task 4.

### Task 2: Model export script and goldens

**Files:**
- Create: `scripts/export-nmt.py`, `scripts/nmt-requirements.txt`
- Modify: `scripts/fetch-models.sh`, `.gitignore`, `<assets>/models.json` (shared, `chore(shared)`)
- Output (gitignored): `<assets>/nmt/nllb-600m/`, `<assets>/nmt/opus-zh-vi/`, `app/src/test/resources/nmt/`, `app/src/androidTest/assets/nmt/`

**Interfaces:**
- Produces per model directory: `encoder_model.onnx`, `decoder_model_merged.onnx`, tokenizer files (NLLB: `sentencepiece.bpe.model`; OPUS: `source.spm`, `target.spm`, `vocab.json`) and `meta.json`:
  ```json
  {"model": "nllb-600m", "eos": 2, "pad": 1, "unk": 3, "decoderStart": 2, "forcedBos": <id of vie_Latn>,
   "srcLangToken": <id of eng_Latn>, "badWords": [], "forcedEosAtMax": false,
   "lengthPenalty": 1.0, "earlyStopping": true, "numLayers": 12, "numHeads": 16, "headDim": 64,
   "pastInputNames": ["past_key_values.0.decoder.key", "..."]}
  ```
  Values come from the exported config and tokenizer. OPUS has `forcedBos` and `srcLangToken` null, `badWords [[65000]]` and `forcedEosAtMax true`. Kotlin reads every id from here and hard-codes none.
- Produces goldens:
  - `app/src/test/resources/nmt/tokenizer-<model>.json`: `[{"text": "...", "ids": [..]}]` for encoding, and `[{"ids": [..], "text": "..."}]` for decoding the reference outputs.
  - `app/src/androidTest/assets/nmt/translations.json`: `[{"id": "en01", "lang": "EN", "text": "...", "final": "...", "draft": "..."}]` from ONNX Runtime on the exported files, beam 4 and greedy.

- [ ] **Step 1: Pin the environment.** `scripts/nmt-requirements.txt` pins optimum-onnx, transformers 4.57.6, torch, onnxruntime 1.23.2 or newer, sentencepiece, sacremoses, huggingface_hub. The script creates and uses its own venv under `build/nmt-venv/`.
- [ ] **Step 2: Export.** For each model, `optimum-cli export onnx --task text2text-generation-with-past --no-post-process`, then `optimum.onnx.merge_decoders(decoder_model.onnx, decoder_with_past_model.onnx)` to `decoder_model_merged.onnx`.
- [ ] **Step 3: Quantize NLLB only.** `quantize_dynamic(path, out, weight_type=QuantType.QInt8, per_channel=True, extra_options={"EnableSubgraph": True})` on the encoder and the merged decoder. `EnableSubgraph` is needed because the merged decoder keeps both branches inside an `If` node; without it the decoder stays fp32 (seen in the spike). OPUS stays fp32.
- [ ] **Step 4: Verify sizes.** The script fails if any `.onnx` has a sibling `.onnx_data`, if NLLB's two files exceed 1.2 GB, or if OPUS's exceed 650 MB.
- [ ] **Step 5: Write `meta.json`.**
- [ ] **Step 6: Write goldens.** The tokenizer corpus is the 20 suite lines from the spike doc, these edge cases, and the first 300 `eng_Latn` and `zho_Hans` lines of FLORES-200 `dev` (downloaded at export time from `https://dl.fbaipublicfiles.com/nllb/flores200_dataset.tar.gz`, CC-BY-SA-4.0, never committed):
  ```
  EN: "", "   ", "OK.", "I'm 25 years old — no, 26!", "3.5 km/h", "COVID-19", "e-mail me @ 9:30am", "naïve café", "Wait... what?!", "😀 lol"
  ZH: "我们明天开meeting", "１２３", "２０２６年１１月９日", "你好！！", "“引号”", "（括号）", "一、二、三", "😀哈哈"
  ```
  Translations: the 20 suite lines plus the 5 lines of Review Focus 2 and 5 (a 45-word run-on English line without punctuation written for the test, and the two code-switched lines).
- [ ] **Step 7: Self-check.** The script translates the suite with PyTorch fp32 beam 4 and with the exported ONNX files, and prints `identical=<n>/10` per model. Run `python3 scripts/export-nmt.py`. Expected: OPUS `identical=10/10`, NLLB EN `identical>=7/10` (spike: 14/27 overall with per-channel int8).
- [ ] **Step 8: Wire up.** `fetch-models.sh` runs `export-nmt.py` when `<assets>/nmt/*/meta.json` is missing. `.gitignore` adds `app/src/main/assets/models/nmt/`, `app/src/test/resources/nmt/`, `app/src/androidTest/assets/nmt/`. `models.json` gets an `"nmt"` note pointing to the script (no URLs or checksums for generated files).
- [ ] **Step 9: Commit.** `git commit -m "feat(nmt): export script for NLLB-600M int8 and OPUS zh-vi fp32 with goldens"` plus the `chore(shared)` commit for `fetch-models.sh` and `models.json`.

### Task 3: SentencePiece tokenizers

**Files:**
- Create: `<pkg>/feature/translate/nmt/SubwordTokenizer.kt`, `<pkg>/feature/translate/nmt/NllbTokenizer.kt`, `<pkg>/feature/translate/nmt/MarianTokenizer.kt`, `<pkg>/feature/translate/nmt/NmtMeta.kt`
- Test: `<test>/feature/translate/nmt/TokenizerGoldenTest.kt`

**Interfaces:**
- Consumes: Task 2 tokenizer files, `meta.json`, tokenizer goldens.
- Produces:
  - `interface SubwordTokenizer { fun encode(text: String): IntArray; fun decode(ids: IntArray): String }`. `encode` returns the full encoder input including the language prefix (NLLB) and the trailing eos. `decode` skips special ids.
  - `class NllbTokenizer(spmModel: InputStream, meta: NmtMeta) : SubwordTokenizer`
  - `class MarianTokenizer(sourceSpm: InputStream, targetSpm: InputStream, vocabJson: InputStream, meta: NmtMeta) : SubwordTokenizer`
  - `data class NmtMeta(...)` with a `companion fun parse(json: String): NmtMeta`, one property per `meta.json` key.

- [ ] **Step 1: Write the failing tests.** For each model, read the model files from `src/main/assets/models/nmt/<model>/` and the goldens from `src/test/resources/nmt/` (fail with "run scripts/export-nmt.py" if missing).
  ```kotlin
  @Test fun nllb_encode_matchesHuggingFace() = goldens("nllb-600m").encode.forEach { assertArrayEquals(it.text, it.ids, nllb.encode(it.text)) }
  @Test fun nllb_decode_matchesHuggingFace() = goldens("nllb-600m").decode.forEach { assertEquals(it.text, nllb.decode(it.ids)) }
  @Test fun marian_encode_matchesHuggingFace() = goldens("opus-zh-vi").encode.forEach { assertArrayEquals(it.text, it.ids, marian.encode(it.text)) }
  @Test fun marian_decode_matchesHuggingFace() = goldens("opus-zh-vi").decode.forEach { assertEquals(it.text, marian.decode(it.ids)) }
  ```
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*TokenizerGoldenTest"`. Expected: FAIL (unresolved).
- [ ] **Step 3: Implement.** Pure Kotlin, no new native code: parse the SentencePiece `ModelProto` (pieces, scores, types, `normalizer_spec`), apply NFKC through `java.text.Normalizer` plus SentencePiece's whitespace rules, unigram Viterbi for Marian and BPE merges for NLLB, the fairseq id offset for NLLB, `vocab.json` mapping for Marian. If a golden still fails only on normalization, implement the `precompiled_charsmap` lookup instead of NFKC.
- [ ] **Step 4:** Rerun. Expected: PASS, every line.
- [ ] **Step 5:** `git commit -m "feat(nmt): SentencePiece tokenizers for NLLB and Marian matching HF ids"`

### Task 4: ONNX seq2seq engine

**Files:**
- Create: `<pkg>/feature/translate/nmt/Seq2SeqEngine.kt`, `<pkg>/feature/translate/nmt/AssetModelLoader.kt`, `<pkg>/feature/translate/nmt/BeamSearch.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts` (shared)
- Test: `<test>/feature/translate/nmt/BeamSearchTest.kt`, `<atest>/feature/translate/nmt/NmtEngineGoldenTest.kt`

**Interfaces:**
- Consumes: `SubwordTokenizer`, `NmtMeta` (Task 3).
- Produces:
  - `class Seq2SeqEngine(encoder: OrtSession, decoder: OrtSession, meta: NmtMeta) : Closeable` with `fun generate(input: IntArray, beams: Int, maxNewTokens: Int): IntArray`. Greedy is `beams = 1`.
  - `object AssetModelLoader { fun session(env: OrtEnvironment, assets: AssetManager, path: String, opts: OrtSession.SessionOptions): OrtSession }`
  - `fun interface StepScorer { fun logProbs(beamTokens: List<IntArray>): Array<FloatArray> }` and `fun beamSearch(scorer: StepScorer, meta: NmtMeta, beams: Int, maxNewTokens: Int): IntArray` in `BeamSearch.kt`, so beam logic is testable on the JVM.

- [ ] **Step 1: Add ONNX Runtime.** `onnxruntime-android` 1.28.2 in `libs.versions.toml`, and in `app/build.gradle.kts` `packaging { jniLibs { pickFirsts += "lib/arm64-v8a/libonnxruntime.so" } }`. Confirm that only one `libonnxruntime.so` is in the APK (`unzip -l app-debug.apk | grep libonnxruntime`) and that `SherpaSmokeTest` and `AsrEngineTest` still pass on the emulator. If sherpa-onnx breaks, write `INTERFACE CHANGE: ORT Java API incompatible with sherpa's libonnxruntime` and stop. Commit as `chore(shared)`.
- [ ] **Step 2: Write the failing JVM tests** in `BeamSearchTest` with a scripted `StepScorer`:
  - `greedy_picksArgmaxUntilEos`
  - `beam_prefersHigherFinalScoreOverGreedyPrefix` (a case where the greedy first token loses)
  - `forcedBos_isFirstToken` (NLLB meta)
  - `badWords_neverEmitted` (OPUS meta, pad has the highest raw score)
  - `forcedEosAtMax_endsWithEos` (OPUS meta, no eos before the limit)
  - `lengthPenalty_appliedAsHuggingFace`: finished score = sum of log-probs / length^lengthPenalty, with length counted as Hugging Face counts it.
- [ ] **Step 3:** Run `./gradlew testDebugUnitTest --tests "*BeamSearchTest"`. Expected: FAIL.
- [ ] **Step 4: Implement `beamSearch`** with Hugging Face semantics for the given meta: log-softmax over processed logits (renormalized after the bad-words and forced-token processors), 2×beams candidates per step, `earlyStopping` from meta, and the forced bos and eos rules.
- [ ] **Step 5:** Rerun. Expected: PASS.
- [ ] **Step 6: Write the failing instrumented test** `NmtEngineGoldenTest`, loading both engines from the target app's assets and the goldens from the test assets:
  - `finals_matchReference`: FINAL (beam 4, 128) for the 20 suite lines. At least 18 of 20 equal the golden `final`. Every differing line is logged as `GOLDEN-DIFF id expected actual`.
  - `drafts_nonEmpty_noCjk`: DRAFT (greedy, 64) for the same lines. Each is non-empty and `!CjkFilter.hasCjk(it)`.
  - `longLine_notTruncated`: the 45-word run-on line. The generated length is below 128 and ends with eos.
  - `codeSwitchedLines_translated`: both lines from Review Focus 5 give non-empty output without Chinese characters, and the ZH one contains `meeting`.
  - `latency_logged`: logs p50 and p95 per mode as `VT-NMT mode=<m> p50=<ms> p95=<ms>`. No assertion on the emulator.
- [ ] **Step 7:** Run `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.thanhngochoang.voicetrans.feature.translate.nmt.NmtEngineGoldenTest` on `vt-big-35`. Expected: FAIL.
- [ ] **Step 8: Implement `AssetModelLoader.session`**: `assets.openFd(path)`, then `FileInputStream(fd.fileDescriptor).channel.map(READ_ONLY, fd.startOffset, fd.length)`, then `env.createSession(buffer, opts)`. This needs the asset uncompressed, which `noCompress` guarantees.
- [ ] **Step 9: Implement `Seq2SeqEngine.generate`**: run the encoder once, then the merged decoder with `use_cache_branch = false` for step 1 and `true` afterwards, feeding `present.*` back as `past_key_values.*` by the names in `meta.pastInputNames`. Expand the encoder output and past tensors per beam, and reorder them by the selected beam indices.
- [ ] **Step 10:** Rerun Step 7. Expected: PASS. Record the `VT-NMT` lines in the board note.
- [ ] **Step 11:** `git commit -m "feat(nmt): ONNX Runtime seq2seq engine with HF-compatible beam search"`

### Task 5: `NmtTranslator` and the `Translator` interface change

**Files:**
- Create: `<pkg>/feature/translate/api/TranslateMode.kt`, `<pkg>/feature/translate/nmt/NmtTranslator.kt`, `<pkg>/feature/translate/nmt/NmtModule.kt`
- Modify: `<pkg>/feature/translate/api/Translator.kt`, `<pkg>/feature/translate/mlkit/MlKitTranslator.kt`, `<pkg>/feature/session/TranslationSession.kt`, `<pkg>/di/AppModule.kt` (shared), `<pkg>/VoiceTransApp.kt`
- Delete: `<pkg>/feature/translate/api/ContextPromptBuilder.kt`, `<test>/feature/translate/api/ContextPromptBuilderTest.kt`
- Test: `<test>/feature/translate/nmt/NmtTranslatorTest.kt`, `<test>/feature/session/TranslationSessionTest.kt` (fakes updated)

**Interfaces:**
- Consumes: `Seq2SeqEngine`, tokenizers (Tasks 3–4).
- Produces:
  - `enum class TranslateMode { DRAFT, FINAL }`
  - `interface Translator : Closeable { val state: StateFlow<EngineState>; suspend fun translate(text: String, from: SourceLang, mode: TranslateMode = TranslateMode.FINAL): Result<String> }`
  - `sealed interface EngineState { data object Loading; data object Ready; data class Failed(val message: String) }`, each implementing `EngineState`.
  - `class NmtTranslator(engines: suspend () -> Map<SourceLang, TextEngine>, dispatcher: CoroutineDispatcher) : Translator` with `fun interface TextEngine { fun translate(text: String, mode: TranslateMode): String }` and `fun warmUp(scope: CoroutineScope)`.
  - `NmtModule` provides the real `TextEngine`s (tokenizer + `Seq2SeqEngine`; FINAL = beam 4, 128; DRAFT = beam 1, 64) and a single-thread dispatcher named `nmt`.

- [ ] **Step 1: Write the failing tests** with fake `TextEngine`s:
  - `routesEnglishToNllb_chineseToOpus`
  - `finalMode_andDraftMode_passedThrough`
  - `translateBeforeReady_suspendsUntilReady` (Review Focus 4)
  - `loadFailure_setsFailed_andTranslateFails`
  - `closeDuringTranslate_waitsThenReleases` (Review Focus 1): a fake engine blocks on a latch, `close()` is called, the latch opens, and the release counter is incremented exactly once, after the call returns.
  - `callsAreSerialized`: two concurrent `translate` calls never overlap inside the engine.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*NmtTranslatorTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.** Change `Translator` to the signature above and adapt `MlKitTranslator` (fixed target `vi`, `state` always `Ready`; it is deleted in Task 10). `TranslationSession.translate` calls `translator.translate(p.text, row.sourceLang)` and drops the context list. `VoiceTransApp.onCreate` calls `nmtTranslator.warmUp(appScope)`. Bind `Translator` to `NmtTranslator` in `AppModule` (`chore(shared)` commit).
- [ ] **Step 4:** Run `./gradlew testDebugUnitTest`. Expected: PASS, including the updated `TranslationSessionTest`.
- [ ] **Step 5:** `git commit -m "feat(nmt): NmtTranslator with EN/ZH routing, modes and warm-up"`

### Task 6: Sentence splitter

**Files:**
- Create: `<pkg>/feature/session/SentenceSplitter.kt`
- Modify: `<pkg>/feature/session/TranslationSession.kt`
- Test: `<test>/feature/session/SentenceSplitterTest.kt`, `<test>/feature/session/TranslationSessionTest.kt`

**Interfaces:**
- Produces: `fun splitSentences(text: String): List<String>`

- [ ] **Step 1: Write the failing tests.**
  ```kotlin
  @Test fun english() = assertEquals(listOf("Hi there.", "How are you?"), splitSentences("Hi there. How are you?"))
  @Test fun chinese() = assertEquals(listOf("你好。", "你吃饭了吗？"), splitSentences("你好。你吃饭了吗？"))
  @Test fun noPunctuation_isOneSentence() = assertEquals(listOf("so we go"), splitSentences("so we go"))
  @Test fun decimalsAndEllipsis_notSplit() = assertEquals(listOf("It costs 3.5 dollars... maybe."), splitSentences("It costs 3.5 dollars... maybe."))
  @Test fun punctuationOnlyFragment_dropped() = assertEquals(listOf("OK."), splitSentences("OK. !"))
  ```
  Session test: `finalWithTwoSentences_makesTwoRows_inOrder`, both rows carrying the parent Final's `timestampMs` and `endMs`, each translated separately, and `isJunkFinal` applied per sentence.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*SentenceSplitterTest" --tests "*TranslationSessionTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.** Split after `.`, `!`, `?` followed by whitespace, and after `。`, `！`, `？`. In `onFinal`, queue one `Pending` per sentence.
- [ ] **Step 4:** Rerun. Expected: PASS.
- [ ] **Step 5:** `git commit -m "feat(session): one row per sentence"`

### Task 7: Draft translation in the session

**Files:**
- Create: `<pkg>/feature/session/DraftPolicy.kt`
- Modify: `<pkg>/feature/session/TranslationSession.kt`, `<pkg>/core/settings/SettingsRepository.kt`
- Test: `<test>/feature/session/DraftPolicyTest.kt`, `<test>/feature/session/TranslationSessionDraftTest.kt`, `<test>/core/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `Translator`, `TranslateMode` (Task 5).
- Produces:
  - `TranslationSession.partialTranslation: StateFlow<String?>`
  - `SettingsRepository.draftTranslation: Flow<Boolean>` (default `true`, key `draft_translation`) and `suspend fun setDraftTranslation(value: Boolean)`
  - `fun shouldDraft(text: String, lastDraftedText: String?, lang: SourceLang): Boolean` in `DraftPolicy.kt`

- [ ] **Step 1: Write the failing tests.**
  - `DraftPolicyTest`: `en_needsThreeWords`, `en_needsTwoNewWords`, `zh_needsFourChars`, `zh_needsThreeNewChars`, `firstDraft_noLastText`.
  - `TranslationSessionDraftTest`:
    - `partialGrows_draftShown`: partials "I didn't", "I didn't mean to", "I didn't mean to hurt you". One draft for the second, one for the third. `partialTranslation` equals the purged draft text.
    - `finalQueued_runsBeforeDraft`: with a Final queued and a partial pending, the fake translator sees FINAL first.
    - `drafts_latestWins`: three partials arrive while one draft runs. Only the newest is drafted next.
    - `shrinkingPartial_noNewDraft` (Review Focus 3).
    - `handOver_draftClearedWhenFirstRowTranslated`
    - `handOver_newerDraftKept`: a newer partial's draft replaced the old one before the Final's row was translated, so it is not cleared.
    - `musicFinal_clearsDraft`
    - `switchOff_noDrafts`
    - `stop_clearsDraft`
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*DraftPolicyTest" --tests "*TranslationSessionDraftTest" --tests "*SettingsRepositoryTest"`. Expected: FAIL.
- [ ] **Step 3: Implement.** One worker coroutine owns all translator calls. It takes the next Final from `translateQueue` when there is one, otherwise the latest pending draft from a conflated holder, otherwise it suspends. Partials update the holder only when `shouldDraft` is true and the switch is on.
- [ ] **Step 4:** Rerun. Expected: PASS, and the whole `TranslationSessionTest` still passes.
- [ ] **Step 5:** `git commit -m "feat(session): live draft translation with final-first scheduling"`

### Task 8: SenseVoice ASR engine (Lane B)

**Files:**
- Create: `<pkg>/feature/asr/SenseVoiceAsrEngine.kt`
- Modify: `<pkg>/feature/asr/AsrConfig.kt`, `<assets>/models.json` and `scripts/fetch-models.sh` (shared), `.gitignore`, `<pkg>/di/AppModule.kt` (shared)
- Delete: `<pkg>/feature/asr/SherpaAsrEngine.kt` and the Zipformer files under `<assets>/asr/`
- Test: `<atest>/feature/asr/AsrEngineTest.kt` (rewritten for `SenseVoiceAsrEngine`)

**Interfaces:**
- Consumes: `AsrEngine`, `AsrEvent` (unchanged).
- Produces: `class SenseVoiceAsrEngine(assets: AssetManager, config: AsrConfig = AsrConfig()) : AsrEngine` and `data class AsrConfig(val numThreads: Int = 2, val vadMinSilenceS: Float = 0.5f, val vadMaxSpeechS: Float = 8f, val partialEveryMs: Long = 800, val preRollMs: Int = 300)`

- [ ] **Step 1: Models.** Add the archive `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17` to `models.json` (files `model.int8.onnx` → `asr-sv/model.int8.onnx`, `tokens.txt` → `asr-sv/tokens.txt`, with SHA-256 recorded from the download), remove the Zipformer archive, and gitignore `asr-sv/model.int8.onnx`. Run `scripts/fetch-models.sh`. Expected: verified. Commit as `chore(shared)`.
- [ ] **Step 2: Rewrite the failing tests** (existing WAVs and helpers):
  - `english_finalIsCasedAndContainsMorning`: one Final whose text contains `"morning"` (case-insensitive) and at least one lowercase letter.
  - `chinese_emitsFinal`: contains `"早上好"`.
  - `partialsEmittedBeforeFinal`: `en_good_morning.wav` repeated to 4 s without gaps gives at least two Partials before the first Final.
  - `silenceOnly_emitsNothing`
  - `continuousSpeech_finalByMaxSpeech`: 25 s of back-to-back speech gives its first Final with `endMs ≤ 8_500`.
- [ ] **Step 3:** Run `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.thanhngochoang.voicetrans.feature.asr.AsrEngineTest`. Expected: FAIL.
- [ ] **Step 4: Implement.** sherpa `Vad` with the config's silence and max-speech values; `OfflineRecognizer` with `OfflineSenseVoiceModelConfig(model, language = "auto", useInverseTextNormalization = true)`. While a segment is open, re-decode the samples collected so far every `partialEveryMs` and emit a Partial when the text changed. On each finished VAD segment, **copy the samples before `pop()`**, decode once and emit a Final with stream-position timestamps that include the pre-roll. Native objects stay on the engine's single thread, as in the old engine.
- [ ] **Step 5:** Rerun. Expected: PASS. Bind `AsrEngine` to `SenseVoiceAsrEngine` in `AppModule` (`chore(shared)`).
- [ ] **Step 6:** `git commit -m "feat(asr): SenseVoice offline recognizer with VAD segments and re-decoded partials"`

### Task 9: UI: draft line, loading state, switch, fixed target

**Files:**
- Modify: `<pkg>/feature/ui/AppNav.kt`, `<pkg>/feature/ui/AppState.kt`, `<pkg>/feature/ui/AppRoot.kt`, `<pkg>/feature/ui/live/LiveModel.kt`, `<pkg>/feature/ui/live/LiveViewModel.kt`, `<pkg>/feature/ui/live/LiveScreen.kt`, `<pkg>/feature/ui/live/LiveRoute.kt`, `<pkg>/feature/overlay/OverlayController.kt`, `<pkg>/feature/overlay/SubtitleOverlay.kt`, `<pkg>/feature/ui/settings/SettingsScreen.kt`, `<pkg>/feature/ui/settings/SettingsViewModel.kt`, `<pkg>/feature/ui/settings/SettingsModels.kt`, `res/values/strings.xml`, `res/values-en/strings.xml` (shared)
- Test: `<atest>/feature/ui/LiveScreenTest.kt`, `<atest>/feature/ui/SettingsScreenTest.kt`, `<atest>/feature/ui/AppNavTest.kt` (new; the gate cases move here from `ModelSetupScreenTest`), `<atest>/feature/ui/AppNavAsyncFlagTest.kt` (adapted to `engineState`), `<atest>/feature/overlay/SubtitleOverlayTest.kt`, `<test>/feature/overlay/OverlayLinesTest.kt`

**Interfaces:**
- Consumes: `TranslationSession.partialTranslation` (Task 7), `Translator.state` (Task 5), `SettingsRepository.draftTranslation` (Task 7).
- Produces: `LiveModel.partialTranslation: StateFlow<String?>`, `LiveModel.engineState: StateFlow<EngineState>`, `AppUiState.engineState` replacing the pack-based `ready` flag, and `Routes.LOADING` replacing `Routes.MODEL_SETUP`. `LiveModel.targetName` and `onOpenTargetPicker` are removed.

- [ ] **Step 1: Write the failing tests.**
  - `AppNavTest.loading_thenLive` (Review Focus 4): `engineState = Loading` shows the loading screen (text `R.string.models_loading`). Switching to `Ready` navigates to background setup on first run, otherwise Live.
  - `AppNavTest.failed_showsError`: `Failed("x")` shows `R.string.models_failed` and no Start button.
  - `LiveScreenTest.draft_shownUnderPartial` (tag `partial_translation`) and `LiveScreenTest.noTargetChip`.
  - `SubtitleOverlayTest.draft_replacesPartialLine`: with a Chinese partial and a draft, the live line shows the draft and never Chinese or "Đang dịch…".
  - `SubtitleOverlayTest.noDraft_englishPartialShown`: today's behavior when there is no draft.
  - `SettingsScreenTest.draftSwitch_togglesSetting` and `SettingsScreenTest.noTargetLanguageRow`.
- [ ] **Step 2:** Run the JVM tests and `connectedDebugAndroidTest` for these classes on `vt-big-35`. Expected: FAIL.
- [ ] **Step 3: Implement.** Strings (vi / en): `models_loading` "Đang nạp mô hình dịch…" / "Loading translation models…"; `models_failed` "Không nạp được mô hình dịch. Hãy cài lại ứng dụng." / "Couldn't load the translation models. Please reinstall the app."; `settings_draft_translation` "Dịch tạm khi đang nói" / "Draft translation while speaking"; `settings_draft_translation_hint` "Hiện bản dịch nháp trong lúc câu chưa nói xong. Tắt để tiết kiệm pin." / "Shows a rough translation before the sentence ends. Turn off to save battery." The Live header shows "→ Tiếng Việt" / "→ Vietnamese" as plain text. Move the overlay's hard-coded "Đang dịch…" into `R.string.overlay_translating`.
- [ ] **Step 4:** Rerun. Expected: PASS.
- [ ] **Step 5:** `git commit -m "feat(ui): draft translation line, model loading state, fixed Vietnamese target"` plus `chore(shared)` for strings.

### Task 10: Removals and docs

**Files:**
- Delete: `<pkg>/feature/translate/llama/`, `<pkg>/feature/translate/hybrid/`, `<pkg>/feature/translate/mlkit/`, `<pkg>/feature/translate/api/PackManager.kt`, `<pkg>/feature/translate/api/ModelState.kt` (if unused), `<pkg>/feature/ui/modelsetup/`, `<pkg>/feature/ui/language/LanguagePicker*.kt`, `<pkg>/feature/ui/language/TargetPicker.kt`, `<pkg>/feature/ui/language/LanguageList.kt` (if unused), `app/src/main/cpp/`, `scripts/build-llama-android.sh`, `scripts/build-llama-jni-android.sh`, `scripts/fetch-qwen-model.sh`, the matching tests (`MlKitTranslatorTest`, `ModelSetupScreenTest`, `LanguagePickerScreenTest`, `LanguageListTest`)
- Modify: `app/build.gradle.kts` and `gradle/libs.versions.toml` (drop `mlkit.translate`, `kotlinx.coroutines.play.services` if unused, and `gguf` from `noCompress`), `.gitignore` (drop the GGUF and `jniLibs` lines), `<pkg>/core/settings/SettingsRepository.kt` (drop `target_language` and `wifi_only_download`), `<pkg>/feature/session/TranslationSession.kt` and `TranslationService.kt` (target is the constant `"vi"`), `<pkg>/di/AppModule.kt`
- Coordinator only: `AGENTS.md`, `docs/design/ui-spec.md`

- [ ] **Step 1: Delete and fix compile errors.** Remove `app/src/main/jniLibs/` and `<assets>/llm/` from the worktree (gitignored, so nothing to commit there).
- [ ] **Step 2: Verify.** Run `./gradlew assembleDebug testDebugUnitTest lintDebug`. Expected: all PASS, no new lint errors. `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "libllama|libggml|mlkit|\.gguf"` prints nothing.
- [ ] **Step 3:** `git commit -m "refactor: remove Qwen/llama.cpp, ML Kit, model setup and target picker"` and `chore(shared)` for the Gradle files.
- [ ] **Step 4 (coordinator): Docs.** In `AGENTS.md`, replace the ML Kit and selectable-target settled decisions, the "only `AsrEvent.Final` is translated" rule, the architecture line, the Models section and the airplane-mode test note with this design's equivalents. In `ui-spec.md`, update "Translation language", replace the ModelSetup section with the loading state, and add the draft line and the Settings switch. Commit on `main` as `docs: Vietnamese NMT pipeline replaces ML Kit/Qwen`.

### Task 11: Device acceptance (S25, Lane A)

**Files:** results appended to `docs/spikes/translation-quality.md` under "Device results".

- [ ] **Step 1: Emulator first.** Install the merged build on `vt-big-35` and play the Short in a browser that allows capture. Record the screen and check that drafts appear and are replaced by finals.
- [ ] **Step 2: Goldens on the S25.** Run `NmtEngineGoldenTest` and `AsrEngineTest`. Expected: PASS. Record the `VT-NMT` p50 and p95 per mode.
- [ ] **Step 3: Latency in a session.** Airplane mode on, play the Short and a Chinese drama clip for 5 minutes each, and collect `VT-Latency` from logcat. Expected: Final → translation p95 < 1 s for lines ≤ 20 words, speech end → subtitle p95 < 2 s. If not, tune in the spec's order (threads, then beam 2 after re-scoring) and record each step.
- [ ] **Step 4: Offline and endurance.** No network requests during the session. The 30-minute screen-off run passes, with `dumpsys meminfo` recorded at start and end.
- [ ] **Step 5: Quality.** Hand-score the Short's 7 sentences and the 20 suite lines from the device output on the spike's 0–2 scale. Expected: at least 40/54.
- [ ] **Step 6:** Commit the results doc and set the board row to `review`.
