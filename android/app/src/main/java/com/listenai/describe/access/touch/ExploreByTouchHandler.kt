package com.listenai.describe.access.touch

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.listenai.describe.access.focus.NodeFilter

/**
 * Handles TYPE_VIEW_HOVER_ENTER events (fired by the framework once
 * touch-exploration mode is enabled — it converts raw touch/drag into
 * hover events for us; double-tap-to-activate is likewise handled by
 * the framework against whichever node holds accessibility focus, no
 * extra code needed here for that part).
 *
 * A hovered node isn't always itself "speakable" (e.g. a Text node
 * nested inside a clickable Row) — [handleHoverEnter] walks up to the
 * nearest ancestor that passes [NodeFilter] so touching anywhere
 * inside a control focuses/speaks the whole control, not a sub-part.
 */
class ExploreByTouchHandler(
    private val onFocusChanged: (AccessibilityNodeInfo) -> Unit,
) {
    /**
     * @return the newly-focused node, or null if this event yielded no
     *         change (no source, no speakable ancestor, or same node
     *         re-entered).
     */
    fun handleHoverEnter(event: AccessibilityEvent, current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val source = event.source ?: return null
        val speakable = nearestSpeakable(source) ?: return null
        if (speakable == current) return null

        current?.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        speakable.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        onFocusChanged(speakable)
        return speakable
    }

    private fun nearestSpeakable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (NodeFilter.isSpeakable(n)) return n
            n = n.parent
        }
        return null
    }
}
