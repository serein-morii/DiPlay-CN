package com.shilapi.xcertplay.network

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotOffReportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before @After fun clear() {
        context.getSharedPreferences("diplay_car_hotspot", 0).edit().clear().commit()
    }

    @Test fun anOffReportCountsUntilTheDriverConnectsAnyway() {
        assertTrue(CarHotspotSettings.reportedOff(context, false))
        CarHotspotSettings.setOffReportIgnored(context, true)
        assertFalse(CarHotspotSettings.reportedOff(context, false))
    }

    @Test fun anOnReportClearsTheDriversChoice() {
        CarHotspotSettings.setOffReportIgnored(context, true)
        assertFalse(CarHotspotSettings.reportedOff(context, true))
        assertFalse(CarHotspotSettings.offReportIgnored(context))
        assertTrue(CarHotspotSettings.reportedOff(context, false))
    }

    // #809: DiLink 2.0 keeps its hotspot switch on but starts the hotspot only from its own hotspot screen.
    @Test fun aHotspotThatNeverCameUpWhileReportedOffBringsTheWarningBack() {
        CarHotspotSettings.setOffReportIgnored(context, true)
        assertTrue(CarHotspotSettings.hotspotNeverCameUp(context, false))
        assertFalse(CarHotspotSettings.offReportIgnored(context))
        assertTrue(CarHotspotSettings.reportedOff(context, false))
    }

    // Geely Galaxy E8 (#604) reports off while its hotspot is on; only a report of off with no hotspot counts.
    @Test fun aFailureWithoutAnOffReportKeepsTheChoice() {
        CarHotspotSettings.setOffReportIgnored(context, true)
        assertFalse(CarHotspotSettings.hotspotNeverCameUp(context, null))
        assertFalse(CarHotspotSettings.hotspotNeverCameUp(context, true))
        assertTrue(CarHotspotSettings.offReportIgnored(context))
        CarHotspotSettings.setOffReportIgnored(context, false)
        assertFalse(CarHotspotSettings.hotspotNeverCameUp(context, false))
    }

    @Test fun anUnknownStateNeverBlocksAndKeepsTheChoice() {
        assertFalse(CarHotspotSettings.reportedOff(context, null))
        CarHotspotSettings.setOffReportIgnored(context, true)
        assertFalse(CarHotspotSettings.reportedOff(context, null))
        assertTrue(CarHotspotSettings.offReportIgnored(context))
    }
}
