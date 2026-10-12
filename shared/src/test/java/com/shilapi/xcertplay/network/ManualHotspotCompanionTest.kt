package com.shilapi.xcertplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualHotspotCompanionTest {
    @Test fun theCarsFiveGhzNetworkIsTheConfiguredNameWithA5gSuffix() {
        // #617: Android knows "HOTSPOT"; the car also broadcasts "HOTSPOT_5G".
        assertTrue(ManualHotspotManager.isFiveGhzCompanion(apSsid = "HOTSPOT", expectedSsid = "HOTSPOT_5G"))
        assertTrue(ManualHotspotManager.isFiveGhzCompanion(apSsid = "Car", expectedSsid = "Car-5G"))
        assertTrue(ManualHotspotManager.isFiveGhzCompanion(apSsid = "Car", expectedSsid = "Car_5g"))
    }

    @Test fun otherNamesStillDoNotMatch() {
        assertFalse(ManualHotspotManager.isFiveGhzCompanion(apSsid = "HOTSPOT", expectedSsid = "HOTSPOT"))
        assertFalse(ManualHotspotManager.isFiveGhzCompanion(apSsid = "HOTSPOT", expectedSsid = "OTHER_5G"))
        assertFalse(ManualHotspotManager.isFiveGhzCompanion(apSsid = "HOTSPOT_5G", expectedSsid = "HOTSPOT"))
        assertFalse(ManualHotspotManager.isFiveGhzCompanion(apSsid = "", expectedSsid = "_5G"))
    }
}
