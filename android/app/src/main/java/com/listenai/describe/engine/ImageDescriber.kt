package com.listenai.describe.engine

/**
 * Seam for swapping the on-device [LlamaEngineController] for a future
 * cloud implementation (e.g. Gemini) behind a settings toggle, without
 * touching call sites in DescribeActivity or the accessibility
 * service. Only LlamaEngineController implements this today — no
 * cloud implementation exists yet.
 */
interface ImageDescriber {
    suspend fun describe(
        imageBytes: ByteArray,
        prompt: String,
        maxTokens: Int,
        chatTemplate: String,
    ): String
}
