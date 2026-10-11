package com.shilapi.xcertplay.hud

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A generic Android tablet must never retry a BYD-only service that is absent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BydHudBridgeTest {
    @Test fun noBydGatewayDoesNotStartPeriodicRetry() {
        val bridge = BydHudBridge
        bridge.initialize(RuntimeEnvironment.getApplication())

        val retryStarted = bridge.javaClass.getDeclaredField("senderStarted")
            .apply { isAccessible = true }
            .getBoolean(bridge)
        assertFalse("No BYD gateway: retry loop must not start", retryStarted)
    }
}
