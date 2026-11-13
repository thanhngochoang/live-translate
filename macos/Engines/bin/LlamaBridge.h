#ifndef LLAMA_BRIDGE_H
#define LLAMA_BRIDGE_H

#ifdef __cplusplus
extern "C" {
#endif

typedef void* llama_bridge_t;

llama_bridge_t llama_bridge_create(const char* model_path, int n_ctx, int n_gpu_layers);
char* llama_bridge_generate(llama_bridge_t bridge, const char* prompt, int max_new_tokens);
void llama_bridge_free_string(char* str);
void llama_bridge_destroy(llama_bridge_t bridge);

#ifdef __cplusplus
}
#endif

#endif // LLAMA_BRIDGE_H
