package com.listenai.describe.engine

/**
 * Prompts fed to the VLM, centralized rather than left as inline
 * literals so different call sites (share-target photo description
 * vs. the accessibility service's screen-description gesture) can use
 * framing appropriate to their hallucination-risk profile.
 */
object DescribePrompts {
    /**
     * Share-target flow: describing a photo shared by the user.
     * Positive-only phrasing per earlier tuning — negative "do not X"
     * clauses caused smaller VLMs to over-latch onto the exclusion
     * instead of describing what's there.
     */
    const val PHOTO_SCENE_PROMPT =
        "In 1 or 2 sentences, describe what is visible: the people and their body positions, and the main objects in the scene."

    /**
     * Screen-reader "describe this screen" gesture: describing a
     * screenshot of app UI. Explicitly avoids asking the model to
     * transcribe specific text/numbers — the VLM has a documented
     * hallucination risk on text-dense/document content (it invented
     * a passport DOB and hallucinated UI-text names in earlier
     * testing). Ground-truth AccessibilityNodeInfo text remains the
     * source of truth for actual on-screen text; this prompt only
     * covers layout and control types the node tree can't easily
     * summarize.
     */
    const val SCREEN_UI_PROMPT =
        "In 1 or 2 sentences, describe the general layout and type of controls visible (buttons, menus, images, navigation bars). Do not attempt to read or guess any specific text, names, or numbers."
}
