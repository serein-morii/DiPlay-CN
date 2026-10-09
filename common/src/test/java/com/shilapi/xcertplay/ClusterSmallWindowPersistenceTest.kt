package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ClusterSmallWindowPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
    }

    @Test fun defaultsAreOffAndRightOfCentre() {
        assertFalse(AirPlayPersistence.loadClusterSmallWindowMarker(context))
        assertEquals(0, AirPlayPersistence.loadClusterSmallWindowMode(context))
        assertEquals(80, AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(context))
        assertEquals(45, AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(context))
    }

    @Test fun booleanSettingMigratesOntoTheThreeWayMode() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().putBoolean("cluster_small_window_marker", true).apply()
        assertEquals(1, AirPlayPersistence.loadClusterSmallWindowMode(context))
        prefs.edit().putBoolean("cluster_small_window_marker", false).apply()
        assertEquals(0, AirPlayPersistence.loadClusterSmallWindowMode(context))
        AirPlayPersistence.saveClusterSmallWindowMode(context, 2)
        assertEquals(2, AirPlayPersistence.loadClusterSmallWindowMode(context))
        assertTrue(AirPlayPersistence.loadClusterSmallWindowMarker(context))
    }

    @Test fun turnCardThemeDefaultsToAutoAndRoundTrips() {
        assertEquals(0, AirPlayPersistence.loadClusterTurnCardTheme(context))
        AirPlayPersistence.saveClusterTurnCardTheme(context, 2)
        assertEquals(2, AirPlayPersistence.loadClusterTurnCardTheme(context))
        AirPlayPersistence.saveClusterTurnCardTheme(context, 9)
        assertEquals(2, AirPlayPersistence.loadClusterTurnCardTheme(context))
    }

    @Test fun markerPercentsRoundTripIndependentlyOfTheFullScreenMarker() {
        AirPlayPersistence.saveClusterMarkerHorizontalStep(context, -2)
        AirPlayPersistence.saveClusterSmallWindowMode(context, 1)
        AirPlayPersistence.saveClusterSmallWindowMarkerXPercent(context, 95)
        AirPlayPersistence.saveClusterSmallWindowMarkerYPercent(context, 20)

        assertTrue(AirPlayPersistence.loadClusterSmallWindowMarker(context))
        assertEquals(95, AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(context))
        assertEquals(20, AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(context))
        assertEquals(-2, AirPlayPersistence.loadClusterMarkerHorizontalStep(context))
    }

    @Test fun markerSnapsOntoTheOnePercentGrid() {
        AirPlayPersistence.saveClusterSmallWindowMarkerXPercent(context, 87)
        AirPlayPersistence.saveClusterSmallWindowMarkerYPercent(context, 3)
        assertEquals(87, AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(context))
        assertEquals(5, AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(context))
        AirPlayPersistence.saveClusterSmallWindowMarkerXPercent(context, 99)
        assertEquals(95, AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(context))
    }

    @Test fun fullScreenMarkerStepsMigrateToThePercentGrid() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().putInt("cluster_marker_horizontal_step", 3).apply()
        prefs.edit().putInt("cluster_marker_vertical_step", -3).apply()
        assertEquals(80, AirPlayPersistence.loadClusterMarkerXPercent(context))
        assertEquals(16, AirPlayPersistence.loadClusterMarkerYPercent(context))

        prefs.edit().putInt("cluster_marker_horizontal_step", 0).apply()
        prefs.edit().putInt("cluster_marker_vertical_step", 0).apply()
        assertEquals(50, AirPlayPersistence.loadClusterMarkerXPercent(context))
        assertEquals(46, AirPlayPersistence.loadClusterMarkerYPercent(context))
    }

    @Test fun smallWindowCardThemeDefaultsToFollowAndRoundTrips() {
        assertEquals(0, AirPlayPersistence.loadClusterSmallWindowCardTheme(context))
        AirPlayPersistence.saveClusterSmallWindowCardTheme(context, 2)
        assertEquals(2, AirPlayPersistence.loadClusterSmallWindowCardTheme(context))
    }

    @Test fun legacyStepSettingsMigrateToPercents() {
        val prefs = context.getSharedPreferences("xcertplay_airplay", 0)
        // cn.3/cn.4 saved 10%-steps around the panel centre (x 49.5, y 45.5).
        prefs.edit().putInt("cluster_small_window_marker_x", 3).apply()
        prefs.edit().putInt("cluster_small_window_marker_y", -3).apply()
        assertEquals(80, AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(context))
        assertEquals(15, AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(context))

        prefs.edit().putInt("cluster_small_window_marker_x", 0).apply()
        prefs.edit().putInt("cluster_small_window_marker_y", 0).apply()
        assertEquals(50, AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(context))
        assertEquals(45, AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(context))
    }

    @Test fun turnCardKeepsASecondRectForTheSmallWindow() {
        assertEquals(80, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(25, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(40, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))

        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 20)
        AirPlayPersistence.saveClusterSmallWindowCardXPercent(context, 88)
        AirPlayPersistence.saveClusterSmallWindowCardYPercent(context, 14)
        AirPlayPersistence.saveClusterSmallWindowCardSizePercent(context, 35)

        assertEquals(88, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(15, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(35, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))
        assertEquals(20, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
    }

    @Test fun smallWindowCardSavesNotifyTheHostWithoutReconnecting() {
        var noticed = 0
        AirPlayPersistence.overlaySettingsListener = { noticed++ }
        try {
            AirPlayPersistence.saveClusterSmallWindowCardXPercent(context, 60)
            AirPlayPersistence.saveClusterSmallWindowCardYPercent(context, 40)
            AirPlayPersistence.saveClusterSmallWindowCardSizePercent(context, 50)
        } finally {
            AirPlayPersistence.overlaySettingsListener = null
        }
        assertEquals(3, noticed)
    }
}
