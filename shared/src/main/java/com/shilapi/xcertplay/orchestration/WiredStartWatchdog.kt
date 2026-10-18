package com.shilapi.xcertplay.orchestration

/**
 * A time limit for the iPhone's AirPlay session after a wired `carplay-start-session`.
 *
 * The wired control loop has no limit of its own, so an iPhone that accepts the start request but never opens the
 * session kept DiPlay on "connecting" until the cable was replugged (#889, #890). On a working head unit the session
 * is active 2 to 13 s after the request. When the limit passes, [onTimeout] ends the attempt so the usual retry starts
 * a new one, which asks the iPhone to switch modes again, as a replug does.
 */
internal class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private var armed = false
    private var finished = false
    private var cancelTimer: (() -> Unit)? = null

    /** The first `carplay-start-session` starts the limit; a repeated one keeps it. */
    @Synchronized fun startSessionSent() {
        if (armed || finished) return
        armed = true
        cancelTimer = schedule(timeoutMillis, ::expire)
    }

    @Synchronized fun sessionActive() {
        finished = true
        cancel()
    }

    @Synchronized fun terminate() {
        finished = true
        cancel()
    }

    private fun expire() {
        synchronized(this) {
            if (finished) return
            finished = true
            cancelTimer = null
        }
        onTimeout()
    }

    private fun cancel() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    companion object {
        /** The same limit as the wireless first connection (WirelessStartupPolicy.FIRST_TCP_MILLIS). */
        const val TIMEOUT_MILLIS = 30_000L
    }
}
