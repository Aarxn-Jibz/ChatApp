#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Holds the native model + context pointers
struct LlamaState {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_chatapplication_LlmEngine_initNative(
        JNIEnv* env,
        jobject /* this */,
        jstring model_path) {

    llama_backend_init();

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model from: %s", path);

    // Model params — CPU-only on Android (n_gpu_layers=0)
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    llama_model* model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        LOGE("Failed to load model from file");
        llama_backend_free();
        return 0L;
    }

    // Context params
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx        = 2048;
    cparams.n_batch      = 512;
    cparams.n_threads    = 4; // conservative for mobile
    cparams.n_threads_batch = 4;
    cparams.no_perf      = true;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("Failed to create llama context");
        llama_model_free(model);
        llama_backend_free();
        return 0L;
    }

    LlamaState* state = new LlamaState();
    state->model = model;
    state->ctx   = ctx;

    LOGI("Model loaded successfully. Returning state ptr.");
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chatapplication_LlmEngine_generateNative(
        JNIEnv* env,
        jobject thiz,
        jlong   engine_ptr,
        jstring prompt) {

    if (engine_ptr == 0) {
        LOGE("generateNative called with null engine_ptr");
        return;
    }

    LlamaState*    state = reinterpret_cast<LlamaState*>(engine_ptr);
    llama_model*   model = state->model;
    llama_context* ctx   = state->ctx;

    const char* prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Starting generation (prompt len=%zu)", strlen(prompt_str));

    // ── Tokenize prompt ───────────────────────────────────────────────────
    const struct llama_vocab* vocab = llama_model_get_vocab(model);

    // First call with nullptr to get required size
    int n_tokens_needed = -llama_tokenize(
            vocab, prompt_str, (int32_t)strlen(prompt_str),
            nullptr, 0, /*add_special=*/true, /*parse_special=*/true);

    std::vector<llama_token> tokens(n_tokens_needed + 4);
    int n_tokens = llama_tokenize(
            vocab, prompt_str, (int32_t)strlen(prompt_str),
            tokens.data(), (int32_t)tokens.size(),
            /*add_special=*/true, /*parse_special=*/true);

    env->ReleaseStringUTFChars(prompt, prompt_str);

    if (n_tokens < 0) {
        LOGE("Tokenization failed (returned %d)", n_tokens);
        return;
    }
    tokens.resize(n_tokens);
    LOGI("Tokenized prompt into %d tokens", n_tokens);

    // ── Clear KV cache and process the prompt batch ───────────────────────
    llama_memory_clear(llama_get_memory(ctx), /*data=*/false);

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    batch.n_tokens = n_tokens;
    for (int i = 0; i < n_tokens; i++) {
        batch.token[i]      = tokens[i];
        batch.pos[i]        = i;
        batch.n_seq_id[i]   = 1;
        batch.seq_id[i][0]  = 0;
        // Only request logits for the last token (the one we'll sample from)
        batch.logits[i] = (i == n_tokens - 1) ? 1 : 0;
    }

    if (llama_decode(ctx, batch) != 0) {
        LOGE("llama_decode failed for prompt batch");
        llama_batch_free(batch);
        return;
    }
    llama_batch_free(batch);

    // ── Set up sampler chain ──────────────────────────────────────────────
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ── Get reference to Kotlin callback ─────────────────────────────────
    jclass    cls      = env->GetObjectClass(thiz);
    jmethodID callback = env->GetMethodID(cls, "onTokenGenerated", "([BZ)V");

    // ── Generation loop ───────────────────────────────────────────────────
    const int max_new_tokens = 512;
    int       n_pos          = n_tokens; // position counter continues from prompt end
    bool      eog_sent       = false;

    for (int i = 0; i < max_new_tokens; i++) {
        // Sample next token from the last logits
        llama_token token_id = llama_sampler_sample(smpl, ctx, -1);
        llama_sampler_accept(smpl, token_id);

        bool is_eog = llama_vocab_is_eog(vocab, token_id);

        // Convert token id → text piece
        char piece[256];
        int  piece_len = llama_token_to_piece(vocab, token_id, piece, sizeof(piece) - 1, 0, true);
        if (piece_len < 0) piece_len = 0;

        // Fire Kotlin callback: onTokenGenerated(tokenBytes, isComplete)
        jbyteArray j_bytes = env->NewByteArray(piece_len);
        env->SetByteArrayRegion(j_bytes, 0, piece_len, reinterpret_cast<const jbyte*>(piece));
        env->CallVoidMethod(thiz, callback, j_bytes, (jboolean)is_eog);
        env->DeleteLocalRef(j_bytes);

        if (is_eog) {
            eog_sent = true;
            break;
        }

        // Feed the sampled token back for the next step
        llama_batch next = llama_batch_init(1, 0, 1);
        next.n_tokens     = 1;
        next.token[0]     = token_id;
        next.pos[0]       = n_pos++;
        next.n_seq_id[0]  = 1;
        next.seq_id[0][0] = 0;
        next.logits[0]    = 1;

        if (llama_decode(ctx, next) != 0) {
            LOGE("llama_decode failed during generation step %d", i);
            llama_batch_free(next);
            break;
        }
        llama_batch_free(next);
    }

    // If we hit max_tokens without a natural EOS, send the final done signal
    if (!eog_sent) {
        jbyteArray empty = env->NewByteArray(0);
        env->CallVoidMethod(thiz, callback, empty, (jboolean) true);
        env->DeleteLocalRef(empty);
    }

    llama_sampler_free(smpl);
    LOGI("Generation complete");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_chatapplication_LlmEngine_releaseNative(
        JNIEnv* env,
        jobject /* this */,
        jlong   engine_ptr) {

    if (engine_ptr == 0) return;
    LlamaState* state = reinterpret_cast<LlamaState*>(engine_ptr);

    llama_free(state->ctx);
    llama_model_free(state->model);
    llama_backend_free();
    delete state;

    LOGI("Model and context released");
}
