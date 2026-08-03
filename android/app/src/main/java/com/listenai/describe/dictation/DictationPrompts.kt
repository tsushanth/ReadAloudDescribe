package com.listenai.describe.dictation

/**
 * Prompt fed to [com.listenai.describe.engine.LlamaEngineController.completeText]
 * to clean up a raw ASR transcript. Kept in its own object for the
 * same reason as engine/DescribePrompts.kt — a single named place to
 * tune wording, separate from the call site.
 */
object DictationPrompts {
    fun cleanupPrompt(rawTranscript: String): String =
        "Clean up this dictated text: fix punctuation and capitalization, " +
            "and remove filler words like \"um\" and \"uh\". Keep the meaning " +
            "exactly the same and do not add anything new. Output only the " +
            "corrected text, nothing else.\n\nText: $rawTranscript"
}
