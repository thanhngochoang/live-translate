package io.github.thanhngochoang.voicetrans.feature.translate.llama

class LlamaBridge {
    external fun nativeInit(modelPath: String, nCtx: Int, nGpuLayers: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxNewTokens: Int): String?
    external fun nativeDestroy(handle: Long)

    companion object {
        init {
            try {
                System.loadLibrary("c++_shared")
                System.loadLibrary("ggml-base")
                System.loadLibrary("ggml-cpu")
                System.loadLibrary("ggml")
                System.loadLibrary("llama")
                System.loadLibrary("llama_jni")
            } catch (t: Throwable) {
                // Graceful fallback in pure JVM unit tests where native .so is not loaded
            }
        }
    }
}
