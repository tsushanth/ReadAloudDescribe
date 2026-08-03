package com.listenai.describe.access

import android.util.Log

/**
 * Tracks consecutive failures in event/key handling and trips
 * [onTripped] once [maxConsecutiveFailures] is hit in a row — a broken
 * screen reader stuck in a crash loop is worse for a blind user than
 * no screen reader at all (see project plan risk register: "No system
 * TalkBack running in parallel"). Resets to zero after any successful
 * call, so a rare transient blip during a long healthy session never
 * creeps toward the threshold.
 *
 * Deliberately per-process, not persisted — a fresh service
 * connection (user re-enabling after a fix, or after Android restarts
 * the process) starts with a clean slate rather than staying
 * permanently tripped from a previous run.
 */
class CrashWatchdog(
    private val maxConsecutiveFailures: Int = 3,
    private val onTripped: () -> Unit,
) {
    private var consecutiveFailures = 0
    private var tripped = false

    fun runGuarded(tag: String, block: () -> Unit) {
        if (tripped) return
        try {
            block()
            consecutiveFailures = 0
        } catch (t: Throwable) {
            consecutiveFailures++
            Log.e(TAG, "guarded call ($tag) threw (failure $consecutiveFailures/$maxConsecutiveFailures)", t)
            if (consecutiveFailures >= maxConsecutiveFailures) {
                tripped = true
                Log.e(TAG, "crash threshold exceeded — disabling service")
                onTripped()
            }
        }
    }

    companion object {
        private const val TAG = "CrashWatchdog"
    }
}
