package com.listenai.describe.access.focus

import android.view.accessibility.AccessibilityNodeInfo

/**
 * "Is this node worth stopping on and speaking?" predicate, applied
 * during traversal. Mirrors the well-known screen-reader heuristic
 * set (TalkBack, VoiceOver) rather than reinventing it: skip nodes
 * that are invisible, marked not-important-for-accessibility, or
 * carry no usable label/action.
 */
object NodeFilter {
    fun isSpeakable(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        if (!node.isImportantForAccessibility) return false
        val hasLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val isInteractive = node.isClickable || node.isCheckable || node.isEditable || node.isFocusable
        return hasLabel || isInteractive
    }
}
