#include <jni.h>
#include "llama.h"
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct LlamaState {
    llama_model* model = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* smpl = nullptr;
    llama_batch_ext* batch = nullptr;
    std::mutex mtx;
};

static void null_log_cb(ggml_log_level level, const char* text, void* user_data) {
    (void)level;
    (void)text;
    (void)user_data;
}

static void batch_set_tokens(llama_batch_ext* batch, const llama_token* tokens, int32_t n_tokens, llama_pos pos_0) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n_tokens; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        const llama_pos pos = pos_0 + i;
        llama_batch_ext_set_pos(batch, idx, &pos);
    }
    llama_batch_ext_set_output_logits(batch, n_tokens - 1, true);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_thanhngochoang_voicetrans_feature_translate_llama_LlamaBridge_nativeInit(
    JNIEnv* env,
    jobject /* thiz */,
    jstring jModelPath,
    jint nCtx,
    jint nGpuLayers
) {
    if (!jModelPath) return 0;
    const char* model_path = env->GetStringUTFChars(jModelPath, nullptr);

    llama_log_set(null_log_cb, nullptr);
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = nGpuLayers;

    llama_model* model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!model) {
        LOGE("Failed to load model from path");
        return 0;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx > 0 ? nCtx : 1024;
    ctx_params.n_batch = 512;
    ctx_params.no_perf = true;

    llama_context* ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create llama_context");
        llama_model_free(model);
        return 0;
    }

    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.8f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    llama_batch_ext* batch = llama_batch_ext_init(ctx);

    auto* state = new LlamaState();
    state->model = model;
    state->vocab = vocab;
    state->ctx = ctx;
    state->smpl = smpl;
    state->batch = batch;

    LOGI("Llama context successfully initialized");
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT jstring JNICALL
Java_io_github_thanhngochoang_voicetrans_feature_translate_llama_LlamaBridge_nativeGenerate(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jstring jPrompt,
    jint maxNewTokens
) {
    if (!handle || !jPrompt) return nullptr;
    auto* state = reinterpret_cast<LlamaState*>(handle);

    const char* prompt_chars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(jPrompt, prompt_chars);

    std::lock_guard<std::mutex> lock(state->mtx);

    llama_memory_t mem = llama_get_memory(state->ctx);
    llama_memory_clear(mem, true);
    llama_sampler_reset(state->smpl);

    int n_prompt = -llama_tokenize(state->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n_prompt <= 0) return nullptr;

    std::vector<llama_token> prompt_tokens(n_prompt);
    if (llama_tokenize(state->vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()), true, true) < 0) {
        return nullptr;
    }

    batch_set_tokens(state->batch, prompt_tokens.data(), n_prompt, 0);

    int n_tokens = n_prompt;
    int n_predict = maxNewTokens > 0 ? maxNewTokens : 128;
    std::string response;

    for (int n_pos = 0; n_pos + n_tokens < n_prompt + n_predict; ) {
        if (llama_process(state->ctx, LLAMA_PROCESS_TYPE_DECODE, state->batch)) {
            break;
        }

        n_pos += n_tokens;

        llama_token new_token_id = llama_sampler_sample(state->smpl, state->ctx, -1);
        if (llama_vocab_is_eog(state->vocab, new_token_id)) {
            break;
        }

        char piece_buf[128];
        int piece_len = llama_token_to_piece(state->vocab, new_token_id, piece_buf, sizeof(piece_buf), 0, true);
        if (piece_len > 0) {
            response.append(piece_buf, piece_len);
        }

        batch_set_tokens(state->batch, &new_token_id, 1, n_pos);
        n_tokens = 1;
    }

    return env->NewStringUTF(response.c_str());
}

JNIEXPORT void JNICALL
Java_io_github_thanhngochoang_voicetrans_feature_translate_llama_LlamaBridge_nativeDestroy(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle
) {
    if (!handle) return;
    auto* state = reinterpret_cast<LlamaState*>(handle);
    if (state->batch) llama_batch_ext_free(state->batch);
    if (state->smpl) llama_sampler_free(state->smpl);
    if (state->ctx) llama_free(state->ctx);
    if (state->model) llama_model_free(state->model);
    delete state;
    LOGI("Llama context destroyed");
}

} // extern "C"
