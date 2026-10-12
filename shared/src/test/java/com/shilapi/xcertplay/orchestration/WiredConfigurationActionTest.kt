package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiredConfigurationActionTest {
    private fun choose(found: Boolean, carPlayMode: Boolean, tried: Int) =
        WiredConfigurationAction.choose(found, carPlayMode, tried, maximumTransitions = 2)

    @Test fun carPlayModeIsUsedAsItIs() {
        assertEquals(WiredConfigurationAction.USE, choose(found = true, carPlayMode = true, tried = 0))
        assertEquals(WiredConfigurationAction.USE, choose(found = true, carPlayMode = true, tried = 1))
    }

    @Test fun usbmuxAndNcmWithoutAppleEthernetAskForCarPlayModeFirst() {
        // #634: iOS 26 offered configuration 5 with USBMUX and NCM; AirPlay never opened on it.
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(found = true, carPlayMode = false, tried = 0))
        assertTrue(WiredConfigurationAction.fallbackAfterRejection(found = true, carPlayMode = false))
    }

    @Test fun usbmuxAndNcmWithoutAppleEthernetAreUsedAfterTheModeRequest() {
        assertEquals(
            WiredConfigurationAction.USE_WITHOUT_APPLE_ETHERNET,
            choose(found = true, carPlayMode = false, tried = 1),
        )
    }

    @Test fun noUsableConfigurationRequestsTheTransitionUntilTheLimit() {
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(found = false, carPlayMode = false, tried = 0))
        assertEquals(WiredConfigurationAction.REQUEST_TRANSITION, choose(found = false, carPlayMode = false, tried = 1))
        assertEquals(WiredConfigurationAction.REJECT, choose(found = false, carPlayMode = false, tried = 2))
        assertFalse(WiredConfigurationAction.fallbackAfterRejection(found = false, carPlayMode = false))
    }

    @Test fun diagnosticNamesKeepTheExistingReportWording() {
        assertEquals("reuse-descriptors", WiredConfigurationAction.USE.diagnostic)
        assertEquals("request-transition", WiredConfigurationAction.REQUEST_TRANSITION.diagnostic)
        assertEquals("reject-missing-configuration", WiredConfigurationAction.REJECT.diagnostic)
    }
}
