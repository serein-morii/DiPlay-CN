package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterProjectionWatchdogTest {
    private val watchdog = ClusterProjectionWatchdog()

    @Test fun aProjectionThatStaysOnIsNeverTouched() {
        // The stock map owns the switch while it shows on the cluster; DiPlay must not fight it.
        repeat(10) { assertFalse(watchdog.onStatus(true)) }
    }

    @Test fun oneOffReadingIsNotEnoughBecauseTheStockMapSwitchesThroughIt() {
        assertFalse(watchdog.onStatus(false))
        assertFalse(watchdog.onStatus(true))
        assertFalse(watchdog.onStatus(false))
    }

    @Test fun twoOffReadingsInARowAskForTheProjectionAgain() {
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
        // The count restarts, so a re-assert that did not take is not retried on every reading.
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
    }

    @Test fun anUnreadableStatusIsNotEvidenceAndNeverActs() {
        repeat(5) { assertFalse(watchdog.onStatus(null)) }
        assertFalse(watchdog.onStatus(false))
        assertFalse(watchdog.onStatus(null))
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
    }

    @Test fun anOnReadingClearsPartialEvidence() {
        assertFalse(watchdog.onStatus(false))
        assertFalse(watchdog.onStatus(true))
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
    }

    @Test fun resetDropsPartialEvidence() {
        assertFalse(watchdog.onStatus(false))
        watchdog.reset()
        assertFalse(watchdog.onStatus(false))
        assertTrue(watchdog.onStatus(false))
    }

    @Test fun anAssertionIsHeldUntilAReadingShowsTheSwitchClosed() {
        assertFalse("nothing is asserted before the presentation starts", watchdog.holdsProjection)
        watchdog.assertedProjection()
        assertTrue(watchdog.holdsProjection)
        assertFalse(watchdog.onStatus(true))
        assertTrue("the stock map owning the cluster is indistinguishable from ours", watchdog.holdsProjection)

        assertFalse(watchdog.onStatus(false))
        assertFalse("the switch left DiPlay's hands", watchdog.holdsProjection)
    }

    @Test fun aReAssertClaimsTheProjectionAgain() {
        watchdog.assertedProjection()
        assertFalse(watchdog.onStatus(false))
        assertFalse(watchdog.holdsProjection)
        assertTrue("the guard asks for the projection again", watchdog.onStatus(false))
        watchdog.assertedProjection()
        assertTrue(watchdog.holdsProjection)
    }

    @Test fun anUnreadableReadingDoesNotRetireTheAssertion() {
        watchdog.assertedProjection()
        repeat(3) { assertFalse(watchdog.onStatus(null)) }
        assertTrue(watchdog.holdsProjection)
    }

    @Test fun theMeasuredMapTakeoverLeavesTheProjectionToTheMap() {
        // Measured on the car: the wheel key closes the switch, DiPlay re-asserts, the head unit
        // closes it again while it assembles the map and the map opens its own projection last.
        watchdog.assertedProjection()
        listOf(false, false, false, true).forEach { watchdog.onStatus(it) }
        assertFalse("the map owns the cluster now", watchdog.holdsProjection)
    }

    @Test fun theSwitchIsHandedBackOnlyWhileDiplaysOwnProjectionIsUp() {
        watchdog.assertedProjection()
        assertTrue("DiPlay's own projection goes back to the head unit", watchdog.shouldHandBack(true))

        // The closed switch was the last evidence, so the on reading now is the stock map's
        // projection, not DiPlay's.
        assertFalse(watchdog.onStatus(false))
        assertFalse("a projection the stock map took over stays untouched", watchdog.shouldHandBack(true))

        watchdog.reset()
        watchdog.assertedProjection()
        assertFalse("nothing to hand back when the switch is already off", watchdog.shouldHandBack(false))
        assertFalse("an unreadable switch is not touched", watchdog.shouldHandBack(null))
    }
}
