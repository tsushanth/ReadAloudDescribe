package com.listenai.describe.access.focus

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Depth-first traversal over the currently active window's node tree,
 * filtered to "speakable" nodes via [NodeFilter]. M3: linear next/
 * previous only — no spatial (up/down/left/right) traversal, no
 * explore-by-touch (that's M4).
 *
 * Each call re-walks the tree from [root] fresh rather than caching
 * the node list: the tree can mutate between calls (content changes,
 * window changes) and cached AccessibilityNodeInfo instances go stale
 * the moment the underlying view changes.
 */
class FocusTraversalEngine {

    /**
     * Returns the next speakable node after [current] in DFS order, or
     * the first speakable node in the tree if [current] is null or not
     * found. Returns null if the tree has no speakable nodes at all.
     */
    fun next(root: AccessibilityNodeInfo, current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val ordered = collectSpeakable(root)
        if (ordered.isEmpty()) return null
        val idx = current?.let { c -> ordered.indexOfFirst { it == c } } ?: -1
        val nextIdx = if (idx == -1) 0 else (idx + 1).coerceAtMost(ordered.size - 1)
        return ordered[nextIdx]
    }

    fun previous(root: AccessibilityNodeInfo, current: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val ordered = collectSpeakable(root)
        if (ordered.isEmpty()) return null
        val idx = current?.let { c -> ordered.indexOfFirst { it == c } } ?: 0
        val prevIdx = (idx - 1).coerceAtLeast(0)
        return ordered[prevIdx]
    }

    private fun collectSpeakable(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            if (NodeFilter.isSpeakable(node)) out.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it) }
            }
        }
        visit(root)
        return out
    }
}
