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
    jstring mmprojPath, jstring textModelPath
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
    cparams.n_ctx      = 2048;  // Phi-2 max; mtmd will tell us if image needs more
    cparams.n_batch    = 512;
    cparams.n_ubatch   = 512;
    cparams.n_threads  = 4;     // tune in Day 7b after first run
    cparams.n_threads_batch = 4;

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
    vparams.n_threads      = 4;
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
