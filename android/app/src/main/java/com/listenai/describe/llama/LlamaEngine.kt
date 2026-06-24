package com.listenai.describe.llama

/**
 * Thin Kotlin wrapper around the libdescribe_jni.so native bridge.
 *
 * Day-5 surface: a single nativeSystemInfo() call that returns the
 * llama.cpp version + ggml CPU feature string, proving the JNI
 * channel works end-to-end.
 *
 * Day-6 will add nativeLoadModel(modelPath, mmprojPath),
 * nativeDescribeImage(jpegBytes, prompt, maxTokens), nativeFree(), etc.
 */
object LlamaEngine {

    init {
        // libdescribe_jni.so is built by CMake (see app/src/main/cpp/
        // CMakeLists.txt) and packaged into the APK under
        // lib/arm64-v8a/. This call loads it via System.loadLibrary
        // which transitively brings in our llama.cpp + ggml symbols
        // because they're statically linked into libdescribe_jni.so.
        System.loadLibrary("describe_jni")
    }

    /**
     * Returns a multi-line string identifying the linked llama.cpp
     * build + the CPU features ggml detected. Use this as a "is the
     * engine alive" probe before invoking real inference paths.
     *
     * Sample output (rough shape):
     *   llama.cpp linked OK
     *   system_info: AVX = 0 | AVX2 = 0 | NEON = 1 | DOTPROD = 1 | ...
     */
    external fun nativeSystemInfo(): String
}
