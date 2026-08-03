package com.listenai.describe.access.gesture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SharedPreferences-backed gesture → action mapping, same pattern as
 * [com.listenai.describe.settings.DescribeSettings]. Only the four
 * single-finger swipe directions are remappable in M7 — these are the
 * gestures the framework delivers via onGesture() while touch
 * exploration is active (M4); single-finger drag/hover is reserved by
 * the system for explore-by-touch and never reaches onGesture().
 *
 * Defaults mirror a screen reader's usual "swipe right = next, swipe
 * left = previous" convention, with swipe up wired to the M5 AI
 * describe-screen gesture and swipe down left unassigned.
 */
class GestureMappingStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mapping = MutableStateFlow(loadMapping())
    val mapping: StateFlow<Map<Int, GestureAction>> = _mapping.asStateFlow()

    fun actionFor(gestureId: Int): GestureAction = _mapping.value[gestureId] ?: GestureAction.NONE

    fun setAction(gestureId: Int, action: GestureAction) {
        prefs.edit().putString(keyFor(gestureId), action.name).apply()
        _mapping.value = _mapping.value.toMutableMap().apply { put(gestureId, action) }
    }

    private fun loadMapping(): Map<Int, GestureAction> =
        SLOTS.associate { slot ->
            val stored = prefs.getString(keyFor(slot.gestureId), null)
            val action = stored?.let { name -> GestureAction.entries.firstOrNull { it.name == name } }
                ?: slot.default
            slot.gestureId to action
        }

    private fun keyFor(gestureId: Int) = "gesture_$gestureId"

    /** One remappable slot: a framework gesture ID + its default action + a UI label. */
    data class Slot(val gestureId: Int, val label: String, val default: GestureAction)

    companion object {
        private const val PREFS_NAME = "gesture_settings"

        val SLOTS: List<Slot> = listOf(
            Slot(AccessibilityService.GESTURE_SWIPE_RIGHT, "Swipe right", GestureAction.NEXT),
            Slot(AccessibilityService.GESTURE_SWIPE_LEFT, "Swipe left", GestureAction.PREVIOUS),
            Slot(AccessibilityService.GESTURE_SWIPE_UP, "Swipe up", GestureAction.DESCRIBE_SCREEN),
            Slot(AccessibilityService.GESTURE_SWIPE_DOWN, "Swipe down", GestureAction.NONE),
        )

        @Volatile
        private var instance: GestureMappingStore? = null

        fun getInstance(context: Context): GestureMappingStore =
            instance ?: synchronized(this) {
                instance ?: GestureMappingStore(context.applicationContext).also { instance = it }
            }
    }
}
