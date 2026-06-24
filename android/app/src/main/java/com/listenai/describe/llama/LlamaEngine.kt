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

    /**
     * Loads both the multimodal projector (mmproj) and the text-model
     * GGUF files. Returns an opaque handle (a C++ pointer cast to long)
     * the caller must hand back to nativeDescribeImage and
     * nativeFreeModels. Returns 0L on failure — check logcat for the
     * specific failure (file missing, GGUF corrupt, OOM, etc).
     *
     * This is a heavy call: it mmaps both files (~3.5 GB combined for
     * F16, ~1.86 GB for Q5_K_M), allocates the KV cache (~tens of MB
     * for n_ctx=2048), and creates the mtmd vision context.
     * Allow ~3-10 s on Pixel 9.
     *
     * MUST be called off the main thread.
     */
    external fun nativeLoadModels(mmprojPath: String, textModelPath: String): Long

    /**
     * Releases all native resources held by the handle returned from
     * nativeLoadModels. Safe to call with 0L (no-op). After this call,
     * the handle is invalid — using it again is a use-after-free.
     */
    external fun nativeFreeModels(handle: Long)

    /**
     * Describe the image bytes (JPEG/PNG/etc — anything stb_image can
     * parse) using the loaded mtmd + decoder. Blocking; takes 15-60 s
     * on Pixel 9 for F16 Moondream2. MUST be called off the main
     * thread.
     *
     * @param handle the value returned by nativeLoadModels
     * @param imageBytes raw image bytes (read from the URI in Kotlin)
     * @param prompt the user-facing question (the mtmd prompt template
     *               wraps this with USER:/<image>/ASSISTANT: in C++)
     * @param maxTokens hard cap on generated tokens (decoder also stops
     *                  on EOS)
     * @return the generated description text, or an error string
     *         starting with "(error:" for any failure
     */
    external fun nativeDescribeImage(
        handle: Long,
        imageBytes: ByteArray,
        prompt: String,
        maxTokens: Int,
    ): String
}
