package com.listenai.describe.model

/**
 * Catalog of vision-language models the user can pick between in the
 * Settings → Model toggle. One entry per (model architecture, size,
 * quant) tuple. Adding a new model means: add an entry here, add the
 * matching chat-template branch in describe_jni.cpp, ensure the
 * GGUFs are reachable at the URLs below.
 *
 * Per-model fields:
 *   - displayName: shown in the toggle ("Detailed" / "Fast")
 *   - sizeLabel: shown next to displayName ("~1.9 GB" / "~640 MB")
 *   - mmprojUrl/textUrl: GGUFs to download on first use of this model
 *   - mmprojFileName/textFileName: local filenames under context.filesDir
 *   - expectedMin/MaxBytes: sanity-check thresholds so a partial download
 *     doesn't pass the "files are present" check
 *   - chatTemplate: hands off to describe_jni's prompt-format branch
 *
 * Each model owns its own GgufModelDownloader instance (keyed by kind),
 * so the user can have Moondream2 cached while downloading SmolVLM2 in
 * the background without one overwriting the other.
 */
enum class ModelKind(
    val displayName: String,
    val sizeLabel: String,
    val mmprojUrl: String,
    val mmprojFileName: String,
    val expectedMmprojMinBytes: Long,
    val expectedMmprojMaxBytes: Long,
    val textUrl: String,
    val textFileName: String,
    val expectedTextMinBytes: Long,
    val expectedTextMaxBytes: Long,
    val estimatedTotalMb: Int,
    val chatTemplate: String,   // passed to native; describe_jni branches on it
    /**
     * llama context size. Moondream2 naturally produces ~729 vision
     * tokens + a few hundred decode tokens, so 2048 is plenty —
     * bumping to 4096 doubles the KV cache and caused a ~75 % prefill
     * regression on Samsung S22 (RAM-constrained 8 GB). SmolVLM2's
     * image-splitting can multiply vision tokens; it needs 4096.
     * Per-kind so each model gets its right-sized cache.
     */
    val nCtx: Int,
) {
    MOONDREAM2(
        displayName = "Detailed",
        sizeLabel = "~1.9 GB",
        mmprojUrl =
            "https://github.com/tsushanth/ReadAloudDescribe/releases/download/v0.1.0-models/moondream2-mmproj-f16.gguf",
        mmprojFileName = "moondream2-mmproj-f16.gguf",
        expectedMmprojMinBytes = 800L * 1024 * 1024,
        expectedMmprojMaxBytes = 1200L * 1024 * 1024,
        textUrl =
            "https://github.com/tsushanth/ReadAloudDescribe/releases/download/v0.1.0-models/moondream2-text-model-Q5_K_M.gguf",
        textFileName = "moondream2-text-model-Q5_K_M.gguf",
        expectedTextMinBytes = 900L * 1024 * 1024,
        expectedTextMaxBytes = 1200L * 1024 * 1024,
        estimatedTotalMb = 1860,
        chatTemplate = "vicuna",
        nCtx = 2048,
    ),

    SMOLVLM2_500M(
        displayName = "Fast",
        sizeLabel = "~640 MB",
        mmprojUrl =
            "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/mmproj-SmolVLM2-500M-Video-Instruct-f16.gguf?download=true",
        mmprojFileName = "smolvlm2-500m-mmproj-f16.gguf",
        expectedMmprojMinBytes = 150L * 1024 * 1024,
        expectedMmprojMaxBytes = 300L * 1024 * 1024,
        textUrl =
            "https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF/resolve/main/SmolVLM2-500M-Video-Instruct-Q8_0.gguf?download=true",
        textFileName = "smolvlm2-500m-text-Q8_0.gguf",
        expectedTextMinBytes = 350L * 1024 * 1024,
        expectedTextMaxBytes = 600L * 1024 * 1024,
        estimatedTotalMb = 640,
        chatTemplate = "smolvlm",
        nCtx = 4096,
    );

    companion object {
        val DEFAULT = MOONDREAM2

        fun fromName(name: String?): ModelKind =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
