package com.shilapi.xcertplay.orchestration

/**
 * A time limit for the iPhone's AirPlay session after a wired `carplay-start-session`.
 *
 * The wired control loop has no limit of its own, so an iPhone that accepts the start request but never opens the
 * session kept DiPlay on "connecting" until the cable was replugged (#889, #890). On a working head unit the session
 * is active 2 to 13 s after the request. When the limit passes, [onTimeout] ends the attempt so the usual retry starts
 * a new one, which asks the iPhone to switch modes again, as a replug does.
 *
 * After a Save-and-reconnect the iPhone opened the USB network 15.8 to 30.2 s after the request, four of eight times
 * at 28.9 to 30.2 s (#335), too late for DiPlay's second request ([WiredStartAgain]) to go out within the limit, so
 * the limit is held once while that request still can.
 */
internal class WiredStartWatchdog(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onTimeout: () -> Unit,
    private val secondRequestCanStillGoOut: () -> Boolean = { false },
    private val onHold: () -> Unit = {},
    private val timeoutMillis: Long = TIMEOUT_MILLIS,
) {
    private var armed = false
    private var finished = false
    private var rearmed = false
    private var held = false
    // The limit in force; a replaced timer that runs anyway finds a newer one and does nothing.
    private var generation = 0
    private var cancelTimer: (() -> Unit)? = null

    /** The first `carplay-start-session` starts the limit; a repeated one keeps it. */
    @Synchronized fun startSessionSent() {
        if (armed || finished) return
        armed = true
        arm(timeoutMillis)
    }

    /** DiPlay's own second request after a late USB network ([WiredStartAgain]) gets a full limit, once. */
    @Synchronized fun startSessionSentAgain() {
        if (!armed || finished || rearmed) return
        rearmed = true
        cancel()
        arm(timeoutMillis)
    }

    @Synchronized fun sessionActive() {
        finished = true
        cancel()
    }

    @Synchronized fun terminate() {
        finished = true
        cancel()
    }

    private fun arm(delayMillis: Long) {
        val armedGeneration = ++generation
        cancelTimer = schedule(delayMillis) { expire(armedGeneration) }
    }

    private fun expire(armedGeneration: Int) {
        val holding = synchronized(this) {
            if (finished || armedGeneration != generation) return
            if (!held && !rearmed && secondRequestCanStillGoOut()) {
                held = true
                arm(HELD_TIMEOUT_MILLIS - timeoutMillis)
                true
            } else {
                finished = true
                cancelTimer = null
                false
            }
        }
        if (holding) onHold() else onTimeout()
    }

    private fun cancel() {
        cancelTimer?.invoke()
        cancelTimer = null
    }

    companion object {
        /** The same limit as the wireless first connection (WirelessStartupPolicy.FIRST_TCP_MILLIS). */
        const val TIMEOUT_MILLIS = 30_000L

        /** Well past the latest USB network seen (30.2 s, #335) plus [WiredStartAgain.QUIET_MILLIS]. */
        const val HELD_TIMEOUT_MILLIS = 45_000L
    }
}
