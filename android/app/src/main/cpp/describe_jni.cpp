// Day-5 minimum-viable JNI bridge.
//
// Single function: nativeSystemInfo() returns a string describing the
// linked llama.cpp + ggml runtime. Proves end-to-end that:
//   1. CMake built llama.cpp + our wrapper successfully
//   2. The .so packaged into the APK
//   3. System.loadLibrary("describe_jni") works at runtime
//   4. JNI calls cross the boundary cleanly + return strings
//
// Day-6 will layer on the actual mtmd load + inference functions.

#include <jni.h>
#include <string>
#include <android/log.h>

#include "llama.h"
#include "ggml.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "describe_jni", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "describe_jni", __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_listenai_describe_llama_LlamaEngine_nativeSystemInfo(
    JNIEnv* env, jobject /* this */
) {
    LOGI("nativeSystemInfo called");

    // llama_print_system_info() returns a const char* describing CPU
    // features detected at runtime (NEON / dotprod / matmul-int8 etc).
    // Available since llama.cpp's initial public API.
    const char* sys = llama_print_system_info();

    std::string out;
    out += "llama.cpp linked OK\n";
    out += "system_info: ";
    out += (sys ? sys : "<null>");

    LOGI("nativeSystemInfo done, length=%zu", out.size());
    return env->NewStringUTF(out.c_str());
}
