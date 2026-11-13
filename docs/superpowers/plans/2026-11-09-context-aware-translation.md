# Context-Aware On-Device Translation Plan (Android & macOS)

> **Goal:** Upgrade VoiceTrans VI on **both Android and macOS** to support natural, context-aware translation using an ultra-lightweight on-device Small Language Model (**Qwen 2.5 0.5B Instruct GGUF, ~468 MB**) alongside the fast default engines (ML Kit on Android, OPUS-MT on macOS).

---

## 1. Architecture & Design Principles

### 1.1 The Context Problem
Traditional NMT engines (ML Kit, OPUS-MT) translate isolated sentences independently. Without conversational context:
- Pronouns (*you/I/they*) are translated inconsistently or incorrectly into Vietnamese.
- Technical terms and colloquialisms lose their situational meaning.
- Tone is robotic and rigid.

### 1.2 The Solution: Dual-Engine Architecture
1. **Engine A (Fast / Low-Footprint):**
   - Android: Google ML Kit (~30 MB/pack, default).
   - macOS: OPUS-MT ONNX / Apple Translation.
   - Zero additional memory, instant startup.
2. **Engine B (Natural / Context-Aware AI):**
   - **Model:** `qwen2.5-0.5b-instruct-q4_k_m.gguf` (~468 MB).
   - **Inference Runtime:** `llama.cpp` (Metal GPU on macOS; ARM NEON on Android Snapdragon 8 Elite).
   - **Context Window:** Injects the last 2–3 translated utterances into the prompt.
   - **Speed:** ~60–80 ms per sentence on Apple M1 & Snapdragon 8 Elite (>150 tokens/sec).
   - **RAM:** Only ~450 MB RAM when loaded.

---

## 2. Shared Interface & Context Injection

### 2.1 Extended `Translator` Interface
Backwards-compatible addition with default parameters so zero existing tests break:

**Android (Kotlin):**
```kotlin
interface Translator : Closeable {
    suspend fun translate(text: String, from: SourceLang, to: String): Result<String>
    
    /** Context-aware translation overload taking preceding conversational pairs. */
    suspend fun translate(
        text: String,
        from: SourceLang,
        to: String,
        context: List<UtteranceContext>
    ): Result<String> = translate(text, from, to)
}

data class UtteranceContext(
    val source: String,
    val translation: String,
    val sourceLang: SourceLang
)
```

**macOS (Swift):**
```swift
public struct UtteranceContext: Sendable, Equatable {
    public let source: String
    public let translation: String
    public let sourceLang: SourceLang
}

public protocol Translator: Sendable {
    func translate(_ text: String, from: SourceLang, to: String) async throws -> String
    func translate(_ text: String, from: SourceLang, to: String, context: [UtteranceContext]) async throws -> String
    var supportedTargets: Set<String> { get }
}

extension Translator {
    public func translate(_ text: String, from: SourceLang, to: String, context: [UtteranceContext]) async throws -> String {
        try await translate(text, from: from, to: to)
    }
}
```

### 2.2 System Prompt & Context Formatting (ChatML)
```text
<|im_start|>system
You are an expert bilingual live subtitle translator. Translate conversational speech into natural, colloquial Vietnamese.
Rules:
- Output ONLY the Vietnamese translation, nothing else.
- Do not explain or repeat the original sentence.
- Keep tone natural, fluent, and conversational, matching previous context.
- Keep common tech jargon unchanged when appropriate.
<|im_end|>
<|im_start|>user
Recent conversation context:
- EN: I just deployed the new model to the server.
  VI: Mình vừa deploy model mới lên máy chủ rồi.
- EN: Did you test the latency?
  VI: Bạn đã kiểm tra độ trễ chưa?

Translate this utterance into Vietnamese:
EN: It runs in under two hundred milliseconds.
<|im_end|>
<|im_start|>assistant
Nó chạy chỉ dưới hai trăm mili-giây thôi.
<|im_end|>
```

---

## 3. On-Device LLM Runtime (`llama.cpp`)

### 3.1 Model Spec
- **File:** `qwen2.5-0.5b-instruct-q4_k_m.gguf`
- **File Size:** ~468 MB (491,400,032 bytes)
- **Context Size:** 1024 tokens (plenty for 5 recent utterances + system prompt)
- **Quantization:** Q4_K_M (optimal balance between size and quality)
- **Source:** HuggingFace `Qwen/Qwen2.5-0.5B-Instruct-GGUF`

### 3.2 macOS Implementation (`macos/VoiceTransMac/Translate/LlamaContextTranslator.swift`)
- Compiled with Apple Metal acceleration (`GGML_USE_METAL=1`).
- Embeds a thin C/Swift wrapper (`LlamaBridge`) wrapping `llama_model`, `llama_context`, `llama_decode`.
- Memory usage: ~450 MB unified RAM.
- Speed on M1: ~150–200 tokens/sec.

### 3.3 Android Implementation (`feature/translate/llama/LlamaContextTranslator.kt`)
- `libllama.so` compiled via CMake + Android NDK 28.
- JNI wrapper `LlamaEngine.kt`.
- Hardware acceleration: ARM NEON + FP16 vectorization.
- Memory usage on Snapdragon 8 Elite: ~450 MB out of 12–16 GB RAM.
- Speed on Snapdragon 8 Elite: ~120–150 tokens/sec.

---

## 4. UI & Settings Integration

### 4.1 Settings Option: Translation Engine
- Radio group in Settings (both platforms):
  - **Standard Mode (Default):** Fast, ~30 MB, isolated sentence translation (ML Kit / OPUS-MT).
  - **AI Context Mode:** Natural contextual translation via Qwen 0.5B (~468 MB).
- Model Download Manager:
  - Download button with progress bar and size verification.
  - Delete model button to reclaim disk space.
  - Wi-Fi only download toggle.

---

## 5. Phased Implementation Tasks

| Task | Platform | Description | Status |
|---|---|---|---|
| **C1** | Shared | Create `UtteranceContext` and update `Translator` interface + `TranslationSession` on Android and macOS. | **DONE** |
| **C2** | Shared | Create `ContextPromptBuilder` with ChatML format on both Kotlin & Swift. | **DONE** |
| **C3** | Shared | Create `scripts/fetch-qwen-model.sh` for `qwen2.5-0.5b-instruct-q4_k_m.gguf` (~468 MB). | **DONE** |
| **C4** | Shared | Create build scripts `scripts/build-llama-macos.sh` (Metal) and `scripts/build-llama-android.sh` (NDK). | **DONE** |
| **C5** | macOS | Implement `LlamaBridge` and `LlamaContextTranslator.swift` using compiled `libllama.dylib`. | Next |
| **C6** | Android | Implement JNI `LlamaEngine` and `LlamaContextTranslator.kt`. | Next |
| **C7** | Both | Settings UI engine toggle and end-to-end verification. | Next |
