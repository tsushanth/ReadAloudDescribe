// JNI bridge between Kotlin (LlamaEngine) and llama.cpp + mtmd.
//
// Surface evolves over Day 5-7:
//   Day 5 (done): nativeSystemInfo() — just proves the lib loaded.
//   Day 7a (this commit): nativeLoadModels(mmprojPath, textModelPath)
//                          returns a long handle + nativeFreeModels(h).
//   Day 7b (next commit): nativeDescribeImage(handle, jpegBytes, prompt,
//                          maxTokens) — full mtmd inference.

#include <jni.h>
#include <string>
#include <android/log.h>

#include "llama.h"
#include "ggml.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <vector>
#include <chrono>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "describe_jni", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "describe_jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "describe_jni", __VA_ARGS__)

namespace {

struct DescribeContext {
    llama_model        * model       = nullptr;
    llama_context      * lctx        = nullptr;
    mtmd_context       * mtmd        = nullptr;

    ~DescribeContext() {
        if (mtmd)  { mtmd_free(mtmd); }
        if (lctx)  { llama_free(lctx); }
        if (model) { llama_model_free(model); }
    }
};

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_listenai_describe_llama_LlamaEngine_nativeSystemInfo(
    JNIEnv* env, jobject /* this */
) {
    LOGI("nativeSystemInfo called");
    const char* sys = llama_print_system_info();

    std::string out;
    out += "llama.cpp linked OK\n";
    out += "system_info: ";
    out += (sys ? sys : "<null>");

    LOGI("nativeSystemInfo done, length=%zu", out.size());
    return env->NewStringUTF(out.c_str());
}

// ----------------------------------------------------------------
// Day-7a: load both models (mmproj for vision, text-model for the
// Phi decoder). Returns a long-cast pointer the caller stores as
// an opaque handle. Returns 0 on any failure.
// ----------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_listenai_describe_llama_LlamaEngine_nativeLoadModels(
    JNIEnv* env, jobject /* this */,
    jstring mmprojPath, jstring textModelPath, jint nCtx
) {
    const char* mmproj_c = env->GetStringUTFChars(mmprojPath, nullptr);
    const char* text_c   = env->GetStringUTFChars(textModelPath, nullptr);
    LOGI("nativeLoadModels mmproj=%s text=%s", mmproj_c, text_c);

    // Initialize llama backend once. Idempotent — calling twice is safe.
    llama_backend_init();

    auto* ctx = new DescribeContext();

    // ---- text model ----
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;  // CPU only on Android arm64

    ctx->model = llama_model_load_from_file(text_c, mparams);
    if (!ctx->model) {
        LOGE("llama_model_load_from_file FAILED for %s", text_c);
        delete ctx;
        env->ReleaseStringUTFChars(mmprojPath, mmproj_c);
        env->ReleaseStringUTFChars(textModelPath, text_c);
        return 0;
    }
    LOGI("text model loaded: %s", text_c);

    // ---- context for inference ----
    llama_context_params cparams = llama_context_default_params();
    // Per-ModelKind n_ctx (Moondream2 → 2048, SmolVLM2 → 4096). Bumping
    // 2048 → 4096 unconditionally regressed Moondream prefill ~75 % on
    // Samsung S22 due to doubled KV-cache pressure on its 8 GB RAM,
    // even though Moondream doesn't need the headroom. Fall back to
    // 2048 if Kotlin passes 0 (defensive).
    cparams.n_ctx      = (nCtx > 0) ? (uint32_t)nCtx : 2048u;
    cparams.n_batch    = 512;
    cparams.n_ubatch   = 512;
    // Galaxy S22 has 1 Cortex-X2 + 3 A710 + 4 A510 (8 cores). 4 threads
    // (the v0.1.1 setting) leaves the 3 A710 big cores under-subscribed;
    // 8 over-subscribes because the A510 efficiency cores fight for
    // memory bandwidth. 6 lines up with X2 + 3×A710 + 2×A510, which is
    // the topology llama.cpp's CPU sched prefers for memory-bound decode.
    // Empirically validate by reading the "tokens in Xms" log after first
    // describe; revert if no improvement.
    cparams.n_threads  = 6;
    cparams.n_threads_batch = 6;

    ctx->lctx = llama_init_from_model(ctx->model, cparams);
    if (!ctx->lctx) {
        LOGE("llama_init_from_model FAILED");
        delete ctx;
        env->ReleaseStringUTFChars(mmprojPath, mmproj_c);
        env->ReleaseStringUTFChars(textModelPath, text_c);
        return 0;
    }
    LOGI("llama context created (n_ctx=%d)", (int)cparams.n_ctx);

    // ---- mtmd vision context ----
    mtmd_context_params vparams = mtmd_context_params_default();
    vparams.use_gpu        = false;
    vparams.print_timings  = false;
    vparams.n_threads      = 6;  // match the text-decode setting; vision prefill is bandwidth-bound too
    vparams.warmup         = false;

    ctx->mtmd = mtmd_init_from_file(mmproj_c, ctx->model, vparams);
    if (!ctx->mtmd) {
        LOGE("mtmd_init_from_file FAILED for %s", mmproj_c);
        delete ctx;
        env->ReleaseStringUTFChars(mmprojPath, mmproj_c);
        env->ReleaseStringUTFChars(textModelPath, text_c);
        return 0;
    }
    LOGI("mtmd context created from %s", mmproj_c);

    env->ReleaseStringUTFChars(mmprojPath, mmproj_c);
    env->ReleaseStringUTFChars(textModelPath, text_c);

    LOGI("nativeLoadModels success, handle=%p", (void*)ctx);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_listenai_describe_llama_LlamaEngine_nativeFreeModels(
    JNIEnv* /* env */, jobject /* this */, jlong handle
) {
    if (handle == 0) return;
    auto* ctx = reinterpret_cast<DescribeContext*>(handle);
    LOGI("nativeFreeModels handle=%p", (void*)ctx);
    delete ctx;
}

// ----------------------------------------------------------------
// Day-7b: describe an image. Takes a jbyteArray containing JPEG/PNG
// bytes (whatever PIL / stb_image can parse), runs full mtmd
// tokenize -> eval -> greedy decode, returns the generated string.
//
// Blocking. May take 15-60s on Pixel 9 for F16 Moondream2 weights.
// Caller MUST invoke off the main thread.
// ----------------------------------------------------------------
// Compose the wrapping chat template per model. mtmd_default_marker()
// returns "<__media__>" regardless of loaded model; mtmd substitutes
// the right embeddings during tokenize. Only the wrapping text differs.
//   "vicuna" (Moondream2):   "USER: <__media__>\n{prompt}\nASSISTANT:"
//   "smolvlm" (SmolVLM2):    "<|im_start|>User:<__media__>\n{prompt}<end_of_utterance>\nAssistant:"
// Default to "smolvlm" — it's the safer fallback for an unknown value
// since the SmolVLM2 build is the one this code path was designed
// around going forward.
static std::string compose_chat_prompt(const char* template_c, const char* prompt_c) {
    const char* marker = mtmd_default_marker();
    std::string out;
    if (template_c && std::string(template_c) == "vicuna") {
        out += "USER: ";
        out += marker;
        out += "\n";
        out += prompt_c;
        out += "\nASSISTANT:";
    } else {
        // smolvlm (default)
        out += "<|im_start|>User:";
        out += marker;
        out += "\n";
        out += prompt_c;
        out += "<end_of_utterance>\nAssistant:";
    }
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_listenai_describe_llama_LlamaEngine_nativeDescribeImage(
    JNIEnv* env, jobject /* this */,
    jlong handle,
    jbyteArray imageBytes,
    jstring promptStr,
    jint maxTokens,
    jstring chatTemplateStr
) {
    if (handle == 0) {
        return env->NewStringUTF("(error: engine not loaded)");
    }
    auto* ctx = reinterpret_cast<DescribeContext*>(handle);

    // Pull the inputs out of Java
    jsize image_len = env->GetArrayLength(imageBytes);
    jbyte* image_buf = env->GetByteArrayElements(imageBytes, nullptr);
    const char* prompt_c = env->GetStringUTFChars(promptStr, nullptr);
    LOGI("nativeDescribeImage image_bytes=%d prompt=\"%.60s%s\" maxTokens=%d",
         (int)image_len, prompt_c, strlen(prompt_c) > 60 ? "..." : "", (int)maxTokens);

    auto t_start = std::chrono::steady_clock::now();

    // ---- 1. Build mtmd_bitmap from the JPEG bytes ----
    auto bitmap_wrap = mtmd_helper_bitmap_init_from_buf(
        ctx->mtmd,
        reinterpret_cast<const unsigned char*>(image_buf),
        image_len,
        /* placeholder= */ false
    );
    if (!bitmap_wrap.bitmap) {
        env->ReleaseByteArrayElements(imageBytes, image_buf, JNI_ABORT);
        env->ReleaseStringUTFChars(promptStr, prompt_c);
        LOGE("mtmd_helper_bitmap_init_from_buf FAILED");
        return env->NewStringUTF("(error: bad image format)");
    }

    // ---- 2. Compose prompt with the multimodal marker ----
    const char* tmpl_c = env->GetStringUTFChars(chatTemplateStr, nullptr);
    std::string full_prompt = compose_chat_prompt(tmpl_c, prompt_c);
    env->ReleaseStringUTFChars(chatTemplateStr, tmpl_c);

    mtmd_input_text input_text;
    input_text.text = full_prompt.c_str();
    input_text.add_special  = true;
    input_text.parse_special = true;

    // ---- 3. Tokenize prompt+image into chunks ----
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[1] = { bitmap_wrap.bitmap };

    int32_t tok_rc = mtmd_tokenize(ctx->mtmd, chunks, &input_text, bitmaps, 1);
    if (tok_rc != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap_wrap.bitmap);
        env->ReleaseByteArrayElements(imageBytes, image_buf, JNI_ABORT);
        env->ReleaseStringUTFChars(promptStr, prompt_c);
        LOGE("mtmd_tokenize failed rc=%d", tok_rc);
        char buf[64];
        snprintf(buf, sizeof(buf), "(error: tokenize rc=%d)", tok_rc);
        return env->NewStringUTF(buf);
    }
    auto t_after_tokenize = std::chrono::steady_clock::now();
    LOGI("mtmd_tokenize done in %lldms, n_chunks=%zu",
         (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t_after_tokenize - t_start).count(),
         mtmd_input_chunks_size(chunks));

    // ---- 4. Reset KV cache + run prefill via mtmd_helper_eval_chunks ----
    // Memory clear is a fresh prompt per call. For multi-turn we'd track
    // n_past; not needed for single-shot describe.
    llama_memory_clear(llama_get_memory(ctx->lctx), true);

    llama_pos n_past_out = 0;
    int32_t eval_rc = mtmd_helper_eval_chunks(
        ctx->mtmd, ctx->lctx, chunks,
        /* n_past= */ 0,
        /* seq_id= */ 0,
        /* n_batch= */ 512,
        /* logits_last= */ true,
        &n_past_out
    );
    if (eval_rc != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap_wrap.bitmap);
        env->ReleaseByteArrayElements(imageBytes, image_buf, JNI_ABORT);
        env->ReleaseStringUTFChars(promptStr, prompt_c);
        LOGE("mtmd_helper_eval_chunks failed rc=%d", eval_rc);
        char buf[64];
        snprintf(buf, sizeof(buf), "(error: eval rc=%d)", eval_rc);
        return env->NewStringUTF(buf);
    }
    auto t_after_eval = std::chrono::steady_clock::now();
    LOGI("mtmd_helper_eval_chunks done in %lldms, n_past=%d",
         (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t_after_eval - t_after_tokenize).count(),
         (int)n_past_out);

    // ---- 5. Greedy decode loop ----
    const llama_model * model = llama_get_model(ctx->lctx);
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const llama_token eos     = llama_vocab_eos(vocab);

    llama_batch batch = llama_batch_init(/* n_tokens= */ 1, /* embd= */ 0, /* n_seq_max= */ 1);

    std::string result;
    result.reserve(2048);

    int generated = 0;
    for (int step = 0; step < (int)maxTokens; ++step) {
        // Argmax over the last logits row
        const float * logits = llama_get_logits_ith(ctx->lctx, -1);
        if (!logits) {
            LOGE("llama_get_logits_ith returned null at step %d", step);
            break;
        }
        const int32_t n_vocab = llama_vocab_n_tokens(vocab);
        int best = 0;
        float best_l = logits[0];
        for (int i = 1; i < n_vocab; ++i) {
            if (logits[i] > best_l) { best_l = logits[i]; best = i; }
        }
        const llama_token next = (llama_token)best;
        if (next == eos) {
            LOGI("EOS at step %d", step);
            break;
        }
        generated++;

        // Append token's piece to the result string
        char piece[64];
        int n_chars = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (n_chars > 0) {
            result.append(piece, n_chars);
        }

        // Feed the new token back for the next step
        batch.n_tokens   = 1;
        batch.token[0]   = next;
        batch.pos[0]     = n_past_out;
        batch.n_seq_id[0]= 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]  = 1;
        if (llama_decode(ctx->lctx, batch) != 0) {
            LOGE("llama_decode failed at step %d", step);
            break;
        }
        n_past_out += 1;
    }

    llama_batch_free(batch);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap_wrap.bitmap);
    env->ReleaseByteArrayElements(imageBytes, image_buf, JNI_ABORT);
    env->ReleaseStringUTFChars(promptStr, prompt_c);

    auto t_end = std::chrono::steady_clock::now();
    long long total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start).count();
    long long decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_after_eval).count();
    LOGI("nativeDescribeImage done: %d tokens in %lldms total (%lldms decode), result_chars=%zu",
         generated, total_ms, decode_ms, result.size());

    return env->NewStringUTF(result.c_str());
}

// ----------------------------------------------------------------
// Day-8d: streaming variant. Same pipeline as nativeDescribeImage,
// but calls callback.onToken(piece) for every emitted token so the
// UI / TTS can start consuming output ~15-20s in instead of waiting
// for the full 70-80s decode. onComplete(generatedTokens) fires once.
//
// Callback object MUST implement (matched by JNI signature):
//   public interface DescribeCallback {
//       void onToken(String piece);
//       void onComplete(int generated);
//       void onError(String message);
//   }
//
// Returns void. Errors surface via onError() (no exceptions thrown).
// ----------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_listenai_describe_llama_LlamaEngine_nativeDescribeImageStream(
    JNIEnv* env, jobject /* this */,
    jlong handle,
    jbyteArray imageBytes,
    jstring promptStr,
    jint maxTokens,
    jstring chatTemplateStr,
    jobject callback
) {
    // Resolve callback methods once up front.
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID midOnToken    = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID midOnComplete = env->GetMethodID(cbClass, "onComplete", "(I)V");
    jmethodID midOnError    = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
    if (!midOnToken || !midOnComplete || !midOnError) {
        LOGE("nativeDescribeImageStream: callback missing required methods");
        return;
    }

    auto emitError = [&](const char* msg) {
        jstring jmsg = env->NewStringUTF(msg);
        env->CallVoidMethod(callback, midOnError, jmsg);
        env->DeleteLocalRef(jmsg);
    };

    if (handle == 0) {
        emitError("engine not loaded");
        return;
    }
    auto* ctx = reinterpret_cast<DescribeContext*>(handle);

    jsize image_len  = env->GetArrayLength(imageBytes);
    jbyte* image_buf = env->GetByteArrayElements(imageBytes, nullptr);
    const char* prompt_c = env->GetStringUTFChars(promptStr, nullptr);
    LOGI("nativeDescribeImageStream image_bytes=%d maxTokens=%d", (int)image_len, (int)maxTokens);

    auto t_start = std::chrono::steady_clock::now();

    auto bitmap_wrap = mtmd_helper_bitmap_init_from_buf(
        ctx->mtmd,
        reinterpret_cast<const unsigned char*>(image_buf),
        image_len, false
    );
    if (!bitmap_wrap.bitmap) {
        env->ReleaseByteArrayElements(imageBytes, image_buf, JNI_ABORT);
        env->ReleaseStringUTFChars(promptStr, prompt_c);
        emitError("bad image format");
        return;
    }

    const char* tmpl_c = env->GetStringUTFChars(chatTemplateStr, nullptr);
    std::string full_prompt = compose_chat_prompt(tmpl_c, prompt_c);
    env->ReleaseStringUTFChars(chatTemplateStr, tmpl_c);

    mtmd_input_text input_text;
    input_text.text          = full_prompt.c_str();
    input_text.add_special   = true;
    input_text.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[1] = { bitmap_wrap.bitmap };
    int32_t tok_rc = mtmd_tokenize(ctx->mtmd, chunks, &input_text, bitmaps, 1);
    if (tok_rc != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap_wrap.bitmap);
        env->ReleaseByteArrayElements(imageBytes, image_buf, JNI_ABORT);
        env->ReleaseStringUTFChars(promptStr, prompt_c);
        char buf[64]; snprintf(buf, sizeof(buf), "tokenize rc=%d", tok_rc);
        emitError(buf);
        return;
    }

    llama_memory_clear(llama_get_memory(ctx->lctx), true);

    llama_pos n_past_out = 0;
    int32_t eval_rc = mtmd_helper_eval_chunks(
        ctx->mtmd, ctx->lctx, chunks, 0, 0, 512, true, &n_past_out
    );
    if (eval_rc != 0) {
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap_wrap.bitmap);
        env->ReleaseByteArrayElements(imageBytes, image_buf, JNI_ABORT);
        env->ReleaseStringUTFChars(promptStr, prompt_c);
        char buf[64]; snprintf(buf, sizeof(buf), "eval rc=%d", eval_rc);
        emitError(buf);
        return;
    }
    auto t_after_eval = std::chrono::steady_clock::now();
    LOGI("stream: prefill done in %lldms",
         (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t_after_eval - t_start).count());

    const llama_model * model = llama_get_model(ctx->lctx);
    const llama_vocab * vocab = llama_model_get_vocab(model);
    const llama_token eos     = llama_vocab_eos(vocab);

    llama_batch batch = llama_batch_init(1, 0, 1);

    int generated = 0;
    for (int step = 0; step < (int)maxTokens; ++step) {
        const float * logits = llama_get_logits_ith(ctx->lctx, -1);
        if (!logits) break;
        const int32_t n_vocab = llama_vocab_n_tokens(vocab);
        int best = 0; float best_l = logits[0];
        for (int i = 1; i < n_vocab; ++i) {
            if (logits[i] > best_l) { best_l = logits[i]; best = i; }
        }
        const llama_token next = (llama_token)best;
        if (next == eos) break;
        generated++;

        char piece[64];
        int n_chars = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (n_chars > 0) {
            jstring jpiece = env->NewStringUTF(std::string(piece, n_chars).c_str());
            env->CallVoidMethod(callback, midOnToken, jpiece);
            env->DeleteLocalRef(jpiece);
        }

        batch.n_tokens     = 1;
        batch.token[0]     = next;
        batch.pos[0]       = n_past_out;
        batch.n_seq_id[0]  = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0]    = 1;
        if (llama_decode(ctx->lctx, batch) != 0) break;
        n_past_out += 1;
    }

    llama_batch_free(batch);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap_wrap.bitmap);
    env->ReleaseByteArrayElements(imageBytes, image_buf, JNI_ABORT);
    env->ReleaseStringUTFChars(promptStr, prompt_c);

    auto t_end = std::chrono::steady_clock::now();
    LOGI("nativeDescribeImageStream done: %d tokens in %lldms",
         generated,
         (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t_end - t_start).count());

    env->CallVoidMethod(callback, midOnComplete, (jint)generated);
}
