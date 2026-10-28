package com.shilapi.xcertplay

import com.shilapi.xcertplay.DiLink51ClusterLayout.Theme
import com.shilapi.xcertplay.DiLink51ClusterLayout.Plan
import org.junit.Assert.*
import org.junit.Test

class DiLink51ClusterLayoutTest {
    private val names = listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.SIDE)
    private val firmware = DiLink51ClusterLayout.FINGERPRINT

    @Test fun mapUsesFullLayerWhileBothOtherThemesUseSideLayer() {
        assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names, firmware, Theme.MAP))
        for (theme in listOf(Theme.SCENARIO, Theme.SIMPLE)) {
            assertEquals(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.displayName(names, firmware, theme))
            assertEquals(Plan(1320, 0, 600, 720, false), DiLink51ClusterLayout.plan(1920, 720, theme))
        }
    }

    @Test fun missingSideLayerCannotCoverScenarioWithAFullMap() {
        assertNull(DiLink51ClusterLayout.displayName(listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL), firmware, Theme.SCENARIO))
    }

    @Test fun fullMapKeepsSpeedAndGearBandsOutsideTheVideoViewport() {
        val plan = DiLink51ClusterLayout.plan(1920, 720, Theme.MAP)!!
        assertTrue(plan.top >= 144)
        assertTrue(plan.top + plan.height <= 624)
        assertEquals(0, plan.left)
        assertEquals(1920, plan.width)
        assertEquals(0, plan.sourceLeft)
        assertEquals(240, plan.sourceTop)
        assertNull(DiLink51ClusterLayout.streamConfig().safeArea)
    }

    @Test fun oneContinuousStreamSupportsBothViewportsWithoutStretching() {
        val config = DiLink51ClusterLayout.streamConfig()
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(292, config.widthPhysicalMm)
        assertEquals(110, config.heightPhysicalMm)
        assertEquals(0, config.features)
        assertEquals(config, DiLink51ClusterLayout.streamConfig(1920, 720))
        for (theme in Theme.entries) {
            val plan = DiLink51ClusterLayout.plan(1920, 720, theme)!!
            assertEquals(config.widthPixels, plan.streamWidth)
            assertEquals(config.heightPixels, plan.streamHeight)
            assertTrue(plan.sourceLeft >= 0)
            assertTrue(plan.sourceTop >= 0)
            assertTrue(plan.sourceLeft + plan.width <= config.widthPixels)
            assertEquals(config.heightPixels, plan.sourceTop + plan.height)
            // Preserve the centered, bottom-anchored car marker in every crop.
            assertTrue(960 in plan.sourceLeft until plan.sourceLeft + plan.width)
            assertTrue(640 in plan.sourceTop until plan.sourceTop + plan.height)
        }
        val side = DiLink51ClusterLayout.plan(1920, 720, Theme.SIMPLE)!!
        assertEquals(660, side.sourceLeft)
        assertEquals(0, side.sourceTop)
    }

    @Test fun diLink5AndUnknownFirmwareRetainThePrDisplaySelection() {
        for (fingerprint in listOf("BYD-AUTO/DiLink5.0/DiLink5.0:12/test", "unknown")) {
            assertFalse(DiLink51ClusterLayout.supported(fingerprint))
            assertEquals(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.displayName(names, fingerprint, Theme.SIMPLE))
            assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names.drop(1), fingerprint, Theme.MAP))
        }
    }

    @Test fun theLeopard5FirmwareUsesTheSharedLayers() {
        // #301: the plain layer that unverified firmware gets is one this cluster never shows.
        val leopard = DiLink51ClusterLayout.LEOPARD5_FINGERPRINT
        assertTrue(DiLink51ClusterLayout.supported(leopard))
        assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names, leopard, Theme.MAP))
        assertEquals(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.displayName(names, leopard, Theme.SCENARIO))
        assertFalse(DiLink51ClusterLayout.supported(leopard.replace("20260705", "20260706")))
    }

    @Test fun theDenzaN9FirmwareUsesTheSharedLayers() {
        // #301: like the Leopard 5, the N9 cluster never shows the plain layer.
        val n9 = DiLink51ClusterLayout.DENZA_N9_FINGERPRINT
        assertTrue(DiLink51ClusterLayout.supported(n9))
        assertEquals(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.displayName(names, n9, Theme.MAP))
        assertEquals(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.displayName(names, n9, Theme.SCENARIO))
        assertFalse(DiLink51ClusterLayout.supported(n9.replace("011226", "011227")))
    }

    @Test fun theWideN9ClusterGetsTheMeasuredPlansScaledAcross() {
        // Inferred from owner photos (#301): the stock side map sits in the right quarter, x=1760..2560.
        for (theme in listOf(Theme.SCENARIO, Theme.SIMPLE)) {
            assertEquals(Plan(1760, 0, 800, 720, false, 2560, 720), DiLink51ClusterLayout.plan(2560, 720, theme))
        }
        assertEquals(Plan(0, 144, 2560, 480, true, 2560, 720), DiLink51ClusterLayout.plan(2560, 720, Theme.MAP))
        for (theme in Theme.entries) {
            val measured = DiLink51ClusterLayout.plan(1920, 720, theme)!!
            val wide = DiLink51ClusterLayout.plan(2560, 720, theme)!!
            assertEquals(measured.left * 4, wide.left * 3)
            assertEquals(measured.width * 4, wide.width * 3)
            assertEquals(measured.top, wide.top)
            assertEquals(measured.height, wide.height)
        }
    }

    @Test fun theN9StreamSizeIsKnownBeforeItsClusterDisplayAppears() {
        // The cluster stream is negotiated before the display exists (#981), so the size comes from the firmware.
        assertEquals(2560 to 720, DiLink51ClusterLayout.streamSize(DiLink51ClusterLayout.DENZA_N9_FINGERPRINT))
        assertEquals(1920 to 720, DiLink51ClusterLayout.streamSize(firmware))
        assertEquals(1920 to 720, DiLink51ClusterLayout.streamSize(DiLink51ClusterLayout.LEOPARD5_FINGERPRINT))
        val (width, height) = DiLink51ClusterLayout.streamSize(DiLink51ClusterLayout.DENZA_N9_FINGERPRINT)
        for (theme in Theme.entries) {
            val plan = DiLink51ClusterLayout.plan(2560, 720, theme)!!
            assertEquals(width, plan.streamWidth)
            assertEquals(height, plan.streamHeight)
        }
    }

    @Test fun theWideN9ClusterGetsAStreamOfItsOwnSizeCroppedAtNativeScale() {
        val config = DiLink51ClusterLayout.streamConfig(2560, 720)
        assertEquals(2560, config.widthPixels)
        assertEquals(720, config.heightPixels)
        assertEquals(0, config.features)
        assertNull(config.safeArea)
        for (theme in Theme.entries) {
            val plan = DiLink51ClusterLayout.plan(2560, 720, theme)!!
            assertEquals(config.widthPixels, plan.streamWidth)
            assertEquals(config.heightPixels, plan.streamHeight)
            assertTrue(plan.sourceLeft >= 0)
            assertTrue(plan.sourceLeft + plan.width <= config.widthPixels)
            assertEquals(config.heightPixels, plan.sourceTop + plan.height)
            assertTrue(1280 in plan.sourceLeft until plan.sourceLeft + plan.width)
            assertTrue(640 in plan.sourceTop until plan.sourceTop + plan.height)
        }
        assertEquals(880, DiLink51ClusterLayout.plan(2560, 720, Theme.SIMPLE)!!.sourceLeft)
        assertEquals(240, DiLink51ClusterLayout.plan(2560, 720, Theme.MAP)!!.sourceTop)
    }

    @Test fun unmeasuredPanelSizesAreNotGivenTheWrongReadoutMask() {
        assertNull(DiLink51ClusterLayout.plan(1280, 480, Theme.MAP))
        assertNull(DiLink51ClusterLayout.plan(2560, 1080, Theme.MAP))
        assertNull(DiLink51ClusterLayout.plan(2400, 720, Theme.SCENARIO))
    }
}
