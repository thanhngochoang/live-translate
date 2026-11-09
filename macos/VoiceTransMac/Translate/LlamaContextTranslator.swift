import Foundation
import VoiceTransCore

public enum LlamaError: LocalizedError {
    case libraryNotFound(String)
    case symbolNotFound(String)
    case modelInitFailed(String)
    case inferenceFailed(String)

    public var errorDescription: String? {
        switch self {
        case .libraryNotFound(let msg): return "Library not found: \(msg)"
        case .symbolNotFound(let msg): return "Symbol not found: \(msg)"
        case .modelInitFailed(let msg): return "Model initialization failed: \(msg)"
        case .inferenceFailed(let msg): return "Inference failed: \(msg)"
        }
    }
}

private struct SendablePointer: @unchecked Sendable {
    let raw: UnsafeMutableRawPointer
}

/// Context-aware, natural conversational translator powered by Qwen 2.5 0.5B Instruct GGUF
/// running locally on-device with Apple Metal GPU acceleration via llama.cpp.
public actor LlamaContextTranslator: Translator {
    private typealias CreateFn = @convention(c) (UnsafePointer<CChar>, Int32, Int32) -> UnsafeMutableRawPointer?
    private typealias GenerateFn = @convention(c) (UnsafeMutableRawPointer, UnsafePointer<CChar>, Int32) -> UnsafeMutablePointer<CChar>?
    private typealias FreeStrFn = @convention(c) (UnsafeMutablePointer<CChar>?) -> Void
    private typealias DestroyFn = @convention(c) (UnsafeMutableRawPointer?) -> Void

    private var dylibHandle: UnsafeMutableRawPointer?
    private var bridgeHandle: UnsafeMutableRawPointer?
    private var generateFn: GenerateFn?
    private var freeStrFn: FreeStrFn?
    private var destroyFn: DestroyFn?

    public let modelPath: String
    public nonisolated var supportedTargets: Set<String> { ["vi"] }
    private var isInitialized = false

    public init(modelPath: String) {
        self.modelPath = modelPath
    }

    deinit {
        if let bridgeHandle = bridgeHandle, let destroyFn = destroyFn {
            destroyFn(bridgeHandle)
        }
        if let dylibHandle = dylibHandle {
            dlclose(dylibHandle)
        }
    }

    private func ensureLoaded() throws {
        if isInitialized { return }

        let fm = FileManager.default
        let candidates = [
            Bundle.main.bundleURL.appendingPathComponent("Contents/Frameworks/libllama_bridge.dylib").path,
            (fm.currentDirectoryPath as NSString).appendingPathComponent("macos/Engines/bin-m4/libllama_bridge.dylib"),
            (fm.currentDirectoryPath as NSString).appendingPathComponent("macos/Engines/bin/libllama_bridge.dylib"),
            "/usr/local/lib/libllama_bridge.dylib"
        ]

        guard let dylibPath = candidates.first(where: { fm.fileExists(atPath: $0) }) else {
            throw LlamaError.libraryNotFound("libllama_bridge.dylib not found")
        }

        guard let lib = dlopen(dylibPath, RTLD_NOW) else {
            let err = dlerror().flatMap { String(cString: $0) } ?? "unknown"
            throw LlamaError.libraryNotFound("Failed to load libllama_bridge.dylib: \(err)")
        }

        guard let createSym = dlsym(lib, "llama_bridge_create"),
              let genSym = dlsym(lib, "llama_bridge_generate"),
              let freeStrSym = dlsym(lib, "llama_bridge_free_string"),
              let destroySym = dlsym(lib, "llama_bridge_destroy") else {
            dlclose(lib)
            throw LlamaError.symbolNotFound("Failed to resolve symbols in libllama_bridge.dylib")
        }

        let create = unsafeBitCast(createSym, to: CreateFn.self)
        self.generateFn = unsafeBitCast(genSym, to: GenerateFn.self)
        self.freeStrFn = unsafeBitCast(freeStrSym, to: FreeStrFn.self)
        self.destroyFn = unsafeBitCast(destroySym, to: DestroyFn.self)
        self.dylibHandle = lib

        guard let bridge = create(modelPath, 1024, 99) else {
            throw LlamaError.modelInitFailed("Failed to initialize llama bridge with model at: \(modelPath)")
        }
        self.bridgeHandle = bridge
        self.isInitialized = true
    }

    public func translate(
        _ text: String,
        from source: SourceLang,
        to target: String,
        context: [UtteranceContext]
    ) async throws -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return "" }

        try ensureLoaded()

        guard let bridge = bridgeHandle,
              let generate = generateFn,
              let freeStr = freeStrFn else {
            throw LlamaError.modelInitFailed("Translator engine is uninitialized")
        }

        let prompt = ContextPromptBuilder.buildPrompt(
            text: trimmed,
            from: source,
            context: context
        )

        let bridgePtr = SendablePointer(raw: bridge)
        let result: String = try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                guard let resPtr = generate(bridgePtr.raw, prompt, 96) else {
                    continuation.resume(throwing: LlamaError.inferenceFailed("Generation returned null"))
                    return
                }
                let raw = String(cString: resPtr).trimmingCharacters(in: .whitespacesAndNewlines)
                freeStr(resPtr)
                continuation.resume(returning: raw)
            }
        }

        return purgeCjk(result)
    }

    private func purgeCjk(_ text: String) -> String {
        var res = text.replacingOccurrences(of: #"[（\(][\u{4E00}-\u{9FFF}\u{3400}-\u{4DBF}\u{F900}-\u{FAFF}\s,.\-—–]+[）\)]"#, with: "", options: .regularExpression)
        res = res.replacingOccurrences(of: #"[\u{4E00}-\u{9FFF}\u{3400}-\u{4DBF}\u{F900}-\u{FAFF}]"#, with: "", options: .regularExpression)
        res = res.replacingOccurrences(of: #"[《》「」【】]"#, with: "", options: .regularExpression)
        res = res.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return res.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    public func translate(_ text: String, from source: SourceLang, to target: String) async throws -> String {
        return try await translate(text, from: source, to: target, context: [])
    }
}
