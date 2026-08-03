package com.listenai.describe.access.gesture

/**
 * Resolves a framework gesture ID (from AccessibilityService.onGesture)
 * to a [GestureAction] via [GestureMappingStore], and invokes the
 * matching callback. Kept separate from DescribeAccessibilityService
 * so the routing logic — and the dispatchGesture/GestureDescription
 * work multi-finger custom gestures will need later — has a home that
 * isn't tangled into the service's event-handling code.
 */
class GestureRouter(
    private val store: GestureMappingStore,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onDescribeScreen: () -> Unit,
) {
    /** @return true if the gesture was handled (i.e. wasn't NONE). */
    fun handle(gestureId: Int): Boolean {
        when (store.actionFor(gestureId)) {
            GestureAction.NEXT -> onNext()
            GestureAction.PREVIOUS -> onPrevious()
            GestureAction.DESCRIBE_SCREEN -> onDescribeScreen()
            GestureAction.NONE -> return false
        }
        return true
    }
}
