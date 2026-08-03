package com.listenai.describe.access.speech

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Builds a spoken utterance from a node's role + label + state, e.g.
 * "Submit, button" or "Remember me, checkbox, checked". This is the
 * ground-truth path and stays entirely independent of the AI describe
 * engine — never delegates to the VLM for on-screen text, per the
 * documented hallucination risk (see engine/DescribePrompts.kt).
 */
object NodeSpeechFormatter {
    fun format(node: AccessibilityNodeInfo): String {
        val label = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: "unlabeled"

        return listOfNotNull(label, roleFor(node), stateFor(node)).joinToString(", ")
    }

    private fun roleFor(node: AccessibilityNodeInfo): String? {
        val className = node.className?.toString() ?: return null
        return when {
            className.contains("Button") -> "button"
            className.contains("CheckBox") -> "checkbox"
            className.contains("Switch") -> "switch"
            className.contains("EditText") -> "text field"
            className.contains("RadioButton") -> "radio button"
            className.contains("ImageView") -> "image"
            else -> null
        }
    }

    private fun stateFor(node: AccessibilityNodeInfo): String? = when {
        node.isCheckable && node.isChecked -> "checked"
        node.isCheckable && !node.isChecked -> "not checked"
        !node.isEnabled -> "disabled"
        node.isSelected -> "selected"
        else -> null
    }
}
