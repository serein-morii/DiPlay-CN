package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// #889, #890: the iPhone accepted the wired start request and never opened the AirPlay session.
class WiredStartWatchdogTest {
    private var delay: Long? = null
    private var timer: (() -> Unit)? = null
    private var cancelled = 0
    private var timeouts = 0

    private fun watchdog() = WiredStartWatchdog(
        schedule = { delayMillis, action ->
            delay = delayMillis
            timer = action
            val cancel: () -> Unit = { cancelled++ }
            cancel
        },
        onTimeout = { timeouts++ },
    )

    @Test fun nothingIsTimedBeforeTheStartRequest() {
        watchdog()
        assertNull(timer)
        assertEquals(0, timeouts)
    }

    @Test fun anIphoneThatNeverOpensTheSessionEndsTheAttemptOnce() {
        val watch = watchdog()
        watch.startSessionSent()
        assertEquals(WiredStartWatchdog.TIMEOUT_MILLIS, delay)
        timer!!()
        timer!!()
        assertEquals(1, timeouts)
    }

    @Test fun aRepeatedStartRequestKeepsTheFirstDeadline() {
        val watch = watchdog()
        watch.startSessionSent()
        val first = timer
        watch.startSessionSent()
        assertEquals(first, timer)
        assertEquals(0, cancelled)
    }

    @Test fun anActiveSessionCancelsTheLimit() {
        val watch = watchdog()
        watch.startSessionSent()
        watch.sessionActive()
        assertEquals(1, cancelled)
        timer!!()
        assertEquals(0, timeouts)
        // A later start request in the same attempt does not start a new limit.
        watch.startSessionSent()
        assertEquals(1, cancelled)
    }

    @Test fun teardownCancelsTheLimit() {
        val watch = watchdog()
        watch.startSessionSent()
        watch.terminate()
        timer!!()
        assertEquals(0, timeouts)
    }
}
