package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class IphoneCornerMaskProtocolTest {
    private val example = byteArrayOf(1, 2, 3, 4)
    private val main = AirPlayDisplayConfig(1280, 720, mainDisplayCornerMasks = true,
        mainDisplayDisableSafeArea = true, useIphoneCornerMask = true)
    private fun config(display: AirPlayDisplayConfig = main) = AirPlayConfig(
        deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0", main = display,
    )

    @Test fun featureIsGatedByExplicitUserOptInAndPhoneOffer() {
        val proposed = listOf("viewAreas", "iAPChannel", "cornerMasks")
        assertTrue(setupEnabledFeatures(config(), proposed).contains("cornerMasks"))
        assertFalse(setupEnabledFeatures(config(), listOf("viewAreas")).contains("cornerMasks"))
        // Legacy square-corner mode uses /info only and must NOT advertise the new protocol.
        assertFalse(setupEnabledFeatures(config(main.copy(useIphoneCornerMask = false)), proposed)
            .contains("cornerMasks"))
        assertFalse(setupEnabledFeatures(config(main.copy(mainDisplayCornerMasks = null,
            useIphoneCornerMask = false)), proposed).contains("cornerMasks"))
    }

    @Test fun optInDeclaresCornerMasksOnlyOnMainDisplayAndNoSafeArea() {
        val info = AirPlayInfoPlist.build(config())
        val displays = info["displays"] as List<*>
        val mainDisplay = displays.first() as Map<*, *>
        assertEquals(true, mainDisplay["cornerMasks"])
        val area = (mainDisplay["viewAreas"] as List<*>).first() as Map<*, *>
        assertFalse(area.containsKey("safeArea"))
    }

    @Test fun extractsOpaquePngDataFromScreenSetupOrNestedRuntimeUpdate() {
        val setup = mapOf("streams" to listOf(mapOf("type" to 110, "topLeftCornerMask" to example)))
        assertArrayEquals(example, findIphoneCornerMask(setup))
        val runtime = mapOf("params" to mapOf("display" to mapOf("topLeftCornerMask" to example)))
        assertArrayEquals(example, findIphoneCornerMask(runtime))
        assertNull(findIphoneCornerMask(mapOf("type" to 110, "topLeftCornerMask" to null)))
        assertNull(findIphoneCornerMask(mapOf("topLeftCornerMask" to "not a byte array")))
        assertNull(findIphoneCornerMask(mapOf("unrelated" to example)))
    }
}
