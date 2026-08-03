package com.listenai.describe.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide observable for whether [DescribeAccessibilityService] is
 * currently bound/running. Lets the Activity/settings UI show accurate
 * status without binding to the service directly. The service itself
 * is the only writer.
 */
object ServiceStateBus {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }
}
