package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterLaunchGateTest {
    private val gate = ClusterLaunchGate(focusWaitMillis = 1_500)

    @Test fun aFocusedWindowLaunchesAtOnce() {
        assertTrue(gate.request(now = 0, hostFocused = true))
        assertFalse(gate.awaitingFocus)
    }

    @Test fun theLaunchWaitsForTheMainWindowsFirstFocus() {
        // #536: launched from onResume, the cluster window took focus before DiPlay hid the system bars.
        assertFalse(gate.request(now = 0, hostFocused = false))
        assertFalse(gate.due(now = 100, hostFocused = false, hostVisible = true))
        assertTrue(gate.awaitingFocus)

        assertTrue(gate.due(now = 200, hostFocused = true, hostVisible = true))
        assertFalse(gate.due(now = 300, hostFocused = true, hostVisible = true))
    }

    @Test fun aWindowThatNeverGetsFocusStillLaunchesTheCluster() {
        assertFalse(gate.request(now = 0, hostFocused = false))
        assertFalse(gate.due(now = 1_400, hostFocused = false, hostVisible = true))
        assertTrue(gate.due(now = 1_500, hostFocused = false, hostVisible = true))
    }

    @Test fun aHostThatLeavesTheScreenLaunchesWithoutWaiting() {
        assertFalse(gate.request(now = 0, hostFocused = false))
        assertTrue(gate.due(now = 50, hostFocused = false, hostVisible = false))
    }

    @Test fun resetCancelsAPendingLaunch() {
        assertFalse(gate.awaitingFocus)
        assertFalse(gate.due(now = 0, hostFocused = true, hostVisible = true))
        assertFalse(gate.request(now = 0, hostFocused = false))
        gate.reset()
        assertFalse(gate.awaitingFocus)
        assertFalse(gate.due(now = 2_000, hostFocused = true, hostVisible = true))
    }
}
