package com.listenai.describe.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.listenai.describe.BuildConfig
import com.listenai.describe.DescribeApplication
import com.listenai.describe.access.focus.FocusTraversalEngine
import com.listenai.describe.access.gesture.GestureMappingStore
import com.listenai.describe.access.gesture.GestureRouter
import com.listenai.describe.access.screen.ScreenDescriber
import com.listenai.describe.access.speech.NodeSpeechFormatter
import com.listenai.describe.access.speech.SpeechQueue
import com.listenai.describe.access.touch.ExploreByTouchHandler
import com.listenai.describe.dictation.DictationEngine
import com.listenai.describe.dictation.DictationResult
import com.listenai.describe.translate.TranslateEngine
import com.listenai.describe.translate.TranslateResult
import com.listenai.describe.translate.TranslateSettings
import com.listenai.describe.tts.DescribeTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * M3 (linear next/prev traversal) + M4 (explore-by-touch) + M5
 * (AI "describe this screen" gesture) + M7 (real customizable
 * gestures) all live here. Debug-only trigger paths remain alongside
 * the real ones, since they're still the only reliable way to test
 * headlessly over adb:
 *   - volume keys via onKeyEvent (works on real hardware; adb's
 *     `input keyevent` does NOT reliably route volume keys through an
 *     accessibility service's key-event filter — it's an OS/adb
 *     injection quirk, not something this code can fix)
 *   - a debug broadcast receiver (`adb shell am broadcast`)
 * Both share [moveFocus]/[describeScreen] with the real gesture path.
 * Neither ships in release builds — see [BuildConfig.DEBUG] guards
 * below.
 *
 * Explore-by-touch (M4) is real end-user input: once touch exploration
 * is enabled (declared in accessibility_service_config.xml), the
 * framework converts raw touch/drag into TYPE_VIEW_HOVER_ENTER/EXIT
 * events for us — [ExploreByTouchHandler] turns those into focus +
 * speech. Double-tap-to-activate is handled by the framework itself
 * against whichever node holds accessibility focus; no extra code
 * needed here for that part.
 *
 * Real gestures (M7): a quick single-finger swipe (distinct from the
 * slow drag/hover explore-by-touch uses) arrives via onGesture() as a
 * GESTURE_SWIPE_* constant — [GestureRouter] + [GestureMappingStore]
 * turn that into next/previous/describe-screen, user-remappable via
 * the settings UI in DescribeActivity.
 *
 * [ScreenDescriber] (M5) is the ONLY place this service touches the AI
 * describe engine, and only on an explicit trigger — never
 * automatically on focus/hover, per the documented hallucination risk.
 * Standard navigation above always uses ground-truth
 * AccessibilityNodeInfo text.
 */
class DescribeAccessibilityService : AccessibilityService() {

    private val traversal = FocusTraversalEngine()
    private lateinit var speechQueue: SpeechQueue
    private lateinit var touchHandler: ExploreByTouchHandler
    private lateinit var screenDescriber: ScreenDescriber
    private lateinit var gestureRouter: GestureRouter
    private lateinit var dictationEngine: DictationEngine
    private val translateEngine = TranslateEngine()
    private var currentNode: AccessibilityNodeInfo? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var describingScreen = false
    private var dictating = false
    private var translating = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * M6 safety hardening: onAccessibilityEvent/onKeyEvent run through
     * this so a bug in traversal/touch/describe logic can't crash the
     * whole service repeatedly and strand the user mid-navigation —
     * after 3 consecutive failures it self-disables rather than
     * keep firing broken handlers.
     */
    private val watchdog = CrashWatchdog(maxConsecutiveFailures = 3, onTripped = {
        Log.e(TAG, "watchdog tripped — calling disableSelf()")
        ServiceStateBus.setRunning(false)
        disableSelf()
    })

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected")
        speechQueue = SpeechQueue(DescribeTts(applicationContext))
        touchHandler = ExploreByTouchHandler(onFocusChanged = { node ->
            currentNode = node
            speechQueue.speak(NodeSpeechFormatter.format(node))
        })
        screenDescriber = ScreenDescriber(this)
        dictationEngine = DictationEngine(this)
        gestureRouter = GestureRouter(
            store = GestureMappingStore.getInstance(this),
            onNext = { moveFocus(next = true) },
            onPrevious = { moveFocus(next = false) },
            onDescribeScreen = { describeScreen() },
        )
        warnIfOtherTouchExplorationServiceActive()
        ServiceStateBus.setRunning(true)

        if (BuildConfig.DEBUG) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_DEBUG_NEXT -> moveFocus(next = true)
                        ACTION_DEBUG_PREV -> moveFocus(next = false)
                        ACTION_DEBUG_DESCRIBE_SCREEN -> describeScreen()
                        ACTION_DEBUG_DICTATE -> dictate()
                        ACTION_DEBUG_TRANSLATE -> translateFocused()
                        // M6 test hook only: deliberately throws inside
                        // the watchdog so its trip-after-3 behavior can
                        // be verified without waiting for a real bug.
                        ACTION_DEBUG_FORCE_CRASH -> watchdog.runGuarded("debugForceCrash") {
                            throw RuntimeException("forced crash for M6 watchdog test")
                        }
                    }
                }
            }
            debugReceiver = receiver
            // Exported so `adb shell am broadcast` (sent as the `shell`
            // UID, distinct from our app) can reach it — this whole
            // receiver only exists in debug builds (BuildConfig.DEBUG
            // guard above), so the exposure is limited to dev/test
            // devices, never a shipped release.
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter().apply {
                    addAction(ACTION_DEBUG_NEXT)
                    addAction(ACTION_DEBUG_PREV)
                    addAction(ACTION_DEBUG_DESCRIBE_SCREEN)
                    addAction(ACTION_DEBUG_DICTATE)
                    addAction(ACTION_DEBUG_TRANSLATE)
                    addAction(ACTION_DEBUG_FORCE_CRASH)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    /**
     * Single-owner model (see project plan, §"Coexistence with system
     * TalkBack"): running two touch-exploration services at once
     * causes double-speaking and gesture conflicts, which is worse
     * than either service alone. We don't auto-disable anything here
     * (that's the user's call), just log loudly so it shows up during
     * dev/test — a real in-app prompt to disable the other service is
     * still TODO before this ships to non-technical users.
     */
    private fun warnIfOtherTouchExplorationServiceActive() {
        val others = accessibilityServiceInfoListSafe()
            .filter { info -> info.resolveInfo?.serviceInfo?.packageName != packageName }
            .filter { info -> info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0 }
        if (others.isNotEmpty()) {
            Log.w(
                TAG,
                "Another touch-exploration accessibility service is active " +
                    "(${others.joinToString { it.resolveInfo?.serviceInfo?.packageName ?: "?" }}); " +
                    "expect double-speaking / gesture conflicts until only one is enabled.",
            )
        }
    }

    private fun accessibilityServiceInfoListSafe(): List<AccessibilityServiceInfo> =
        try {
            val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) ?: emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't query enabled accessibility services", t)
            emptyList()
        }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        watchdog.runGuarded("onAccessibilityEvent") {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // New screen — stop tracking the old focus node, it's
                    // stale, and let the user re-navigate from the top.
                    currentNode = null
                    speechQueue.reset()
                }
                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                    touchHandler.handleHoverEnter(event, currentNode)
                }
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val moveNext = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return false
        }
        watchdog.runGuarded("onKeyEvent") { moveFocus(moveNext) }
        return true
    }

    /**
     * M7: real end-user gesture input. While touch exploration is on
     * (M4), a quick single-finger swipe (as opposed to a slow drag,
     * which stays reserved for explore-by-touch/hover) is delivered
     * here by the framework as one of the GESTURE_SWIPE_* constants.
     * [GestureRouter] maps it through [GestureMappingStore] to next/
     * previous/describe-screen — the same actions the M3/M5 debug
     * broadcasts trigger, now reachable from a real touchscreen
     * gesture instead of adb.
     */
    @Suppress("OverridingDeprecatedMember", "DEPRECATION")
    @Deprecated("Overrides AccessibilityService's deprecated single-arg onGesture; kept because it works uniformly across minSdk 26+ without version-gating the newer AccessibilityGestureEvent overload.")
    override fun onGesture(gestureId: Int): Boolean {
        var handled = false
        watchdog.runGuarded("onGesture") { handled = gestureRouter.handle(gestureId) }
        return handled
    }

    private fun moveFocus(next: Boolean) {
        val root = rootInActiveWindow ?: return
        val target = if (next) traversal.next(root, currentNode) else traversal.previous(root, currentNode)
        if (target != null) {
            currentNode = target
            val utterance = NodeSpeechFormatter.format(target)
            Log.i(TAG, "moveFocus(next=$next) -> \"$utterance\"")
            speechQueue.speak(utterance)
        }
    }

    /**
     * "Describe this screen" — explicit-trigger only (see class doc).
     * Guards against overlapping runs: a second trigger while one is
     * already in flight is ignored rather than queued, since
     * LlamaEngineController's mutex would otherwise just make the user
     * wait through two back-to-back descriptions of a screen that's
     * probably already changed.
     */
    private fun describeScreen() {
        if (describingScreen) {
            Log.i(TAG, "describeScreen: already in progress, ignoring")
            return
        }
        describingScreen = true
        speechQueue.speak("Describing screen…")
        serviceScope.launch {
            val utterance = try {
                screenDescriber.describeCurrentScreen()
            } catch (t: Throwable) {
                Log.e(TAG, "describeCurrentScreen threw", t)
                "Something went wrong describing the screen."
            } finally {
                describingScreen = false
            }
            speechQueue.reset() // allow re-speaking "Describing screen…" style repeats later
            speechQueue.speak(utterance)
        }
    }

    /**
     * M9: capture speech, clean it up via the on-device LLM
     * (LlamaEngineController.completeText — text-only, no image), and
     * route it into whatever field currently holds input focus via
     * ACTION_SET_TEXT. Falls back to just speaking the result if
     * nothing editable is focused, so dictation is still useful as a
     * quick "compose a note" flow even without a target field.
     */
    private fun dictate() {
        if (dictating) {
            Log.i(TAG, "dictate: already in progress, ignoring")
            return
        }
        dictating = true
        speechQueue.speak("Listening…")
        serviceScope.launch {
            val result = try {
                dictationEngine.captureAndClean(DescribeApplication.engineController(this@DescribeAccessibilityService))
            } catch (t: Throwable) {
                Log.e(TAG, "captureAndClean threw", t)
                DictationResult.Error(t.message ?: "unknown error")
            } finally {
                dictating = false
            }
            speechQueue.reset()
            when (result) {
                is DictationResult.Success -> {
                    val target = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    val setOk = target?.let { setNodeText(it, result.cleanedText) } ?: false
                    speechQueue.speak(if (setOk) result.cleanedText else "No text field focused. You said: ${result.cleanedText}")
                }
                DictationResult.PermissionMissing ->
                    speechQueue.speak("Microphone permission needed. Open ReadAloud Describe to grant it.")
                DictationResult.NoSpeechRecognized ->
                    speechQueue.speak("Didn't catch that.")
                is DictationResult.Error ->
                    speechQueue.speak("Dictation failed: ${result.message}")
            }
        }
    }

    /**
     * M10: translate the currently focused node's text (ground-truth
     * AccessibilityNodeInfo text/contentDescription — same source of
     * truth as [NodeSpeechFormatter], never the AI describe engine) via
     * ML Kit's on-device translator. Speaks the result; doesn't modify
     * the screen.
     */
    private fun translateFocused() {
        if (translating) {
            Log.i(TAG, "translateFocused: already in progress, ignoring")
            return
        }
        val node = currentNode ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val text = node?.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (text == null) {
            speechQueue.speak("Nothing focused to translate.")
            return
        }
        translating = true
        val target = TranslateSettings.getInstance(this).targetLanguage.value
        speechQueue.speak("Translating to ${target.displayName}…")
        serviceScope.launch {
            val result = try {
                translateEngine.translate(text, target.code)
            } catch (t: Throwable) {
                Log.e(TAG, "translate threw", t)
                TranslateResult.Error(t.message ?: "unknown error")
            } finally {
                translating = false
            }
            speechQueue.reset()
            when (result) {
                is TranslateResult.Success -> speechQueue.speak(result.translatedText)
                is TranslateResult.Error -> speechQueue.speak("Translation failed: ${result.message}")
            }
        }
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (!node.isEditable) return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override fun onInterrupt() {
        Log.i(TAG, "onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        debugReceiver?.let { unregisterReceiver(it) }
        debugReceiver = null
        serviceScope.cancel()
        ServiceStateBus.setRunning(false)
        return super.onUnbind(intent)
    }

    companion object {
        private const val TAG = "DescribeAccessService"
        private const val ACTION_DEBUG_NEXT = "com.listenai.describe.debug.NEXT"
        private const val ACTION_DEBUG_PREV = "com.listenai.describe.debug.PREV"
        private const val ACTION_DEBUG_DESCRIBE_SCREEN = "com.listenai.describe.debug.DESCRIBE_SCREEN"
        private const val ACTION_DEBUG_DICTATE = "com.listenai.describe.debug.DICTATE"
        private const val ACTION_DEBUG_TRANSLATE = "com.listenai.describe.debug.TRANSLATE"
        private const val ACTION_DEBUG_FORCE_CRASH = "com.listenai.describe.debug.FORCE_CRASH"
    }
}
