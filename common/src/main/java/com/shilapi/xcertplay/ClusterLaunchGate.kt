package com.shilapi.xcertplay

/**
 * Orders the DiLink 4 ADB cluster launch after DiPlay's own window (#536).
 *
 * Starting an activity on the cluster display moves input focus there. On Android 10 a window hides its
 * system bars only once it has focus, so a launch before DiPlay's first focus left the bars on screen:
 * CarPlay started at the smaller size, and the next tap on the head unit returned focus, grew the window
 * and restarted CarPlay. The launch now waits for that focus, or for the host to leave the screen, and
 * CarPlay waits for the launch so that its first session already carries the cluster stream.
 */
internal class ClusterLaunchGate(
    private val focusWaitMillis: Long = FOCUS_WAIT_MILLIS,
    private val routeWaitMillis: Long = ROUTE_WAIT_MILLIS,
) {
    private var awaitingFocusSince = -1L
    private var launchedAt = -1L

    /** True when the launch may run now; otherwise it waits for [due]. */
    fun request(now: Long, hostFocused: Boolean): Boolean {
        if (hostFocused) return launch(now)
        if (awaitingFocusSince < 0) awaitingFocusSince = now
        return false
    }

    /** True once a waiting launch may run: the host has focus, is off screen, or waited long enough. */
    fun due(now: Long, hostFocused: Boolean, hostVisible: Boolean): Boolean {
        if (awaitingFocusSince < 0) return false
        if (!hostFocused && hostVisible && now - awaitingFocusSince < focusWaitMillis) return false
        return launch(now)
    }

    /** CarPlay waits while the launch waits for focus, and briefly while the cluster window confirms. */
    fun holdsCarPlayStart(now: Long, routeSettling: Boolean): Boolean =
        awaitingFocusSince >= 0 || (routeSettling && launchedAt >= 0 && now - launchedAt < routeWaitMillis)

    fun reset() {
        awaitingFocusSince = -1L
        launchedAt = -1L
    }

    private fun launch(now: Long): Boolean {
        awaitingFocusSince = -1L
        launchedAt = now
        return true
    }

    companion object {
        const val FOCUS_WAIT_MILLIS = 1_500L
        // The 0.2.17 report confirmed the cluster window within 0.6 s of the launch.
        const val ROUTE_WAIT_MILLIS = 3_000L
        const val TICK_MILLIS = 100L
    }
}
