package com.listenai.describe.access.gesture

/**
 * What a gesture does once resolved by [GestureMappingStore]. Kept as
 * a small closed set rather than a generic command bus — M7 only
 * needs to route into the three things the service can already do
 * (next/previous from M3, describe-screen from M5); extend this enum
 * when a new capability needs its own gesture slot.
 */
enum class GestureAction(val displayName: String) {
    NEXT("Next item"),
    PREVIOUS("Previous item"),
    DESCRIBE_SCREEN("Describe screen"),
    NONE("Do nothing"),
}
