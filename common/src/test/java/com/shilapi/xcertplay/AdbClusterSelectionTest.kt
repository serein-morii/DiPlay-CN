package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class AdbClusterSelectionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun turningOffAdbKeepsTheExistingClusterMapPreference() {
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        AirPlayPersistence.saveAdbClusterEnabled(app, false)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(app))
    }

    @Test fun savedAdbSwitchCannotReplaceAnExistingDilink5Display() {
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        val manager = app.getSystemService(DisplayManager::class.java)
        shadowOf(manager.getDisplay(id)).apply {
            setName(DiLink51ClusterLayout.BASE)
            setFlags(Display.FLAG_PRESENTATION)
        }
        try {
            assertFalse(AdbClusterRouter.enabled(app))
            assertEquals(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP,
                AirPlayPersistence.loadClusterContent(app))
        }
        finally { ShadowDisplayManager.removeDisplay(id) }
    }

    @Test fun verifiedDilink51NeverFallsBackToAdbForAMissingSideLayer() {
        ShadowBuild.setFingerprint(DiLink51ClusterLayout.FINGERPRINT)
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        assertFalse(AdbClusterRouter.enabled(app))
    }

    @Test fun enabledAdbRoutePreparesItsNativeStreamBeforeTheWindowAppears() {
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val config = CarPlayHostActivity::class.java.getDeclaredMethod("clusterDisplayConfig")
            .apply { isAccessible = true }.invoke(host) as AirPlayDisplayConfig
        assertFalse(ClusterActivityOutput.hasConfirmedRoute())
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
    }
    @Test fun legacyRouteIsOptInAndBothDirectRoutesAreMutuallyExclusive() {
        assertFalse(AirPlayPersistence.loadLegacyClusterEnabled(app))
        AirPlayPersistence.saveLegacyClusterEnabled(app, true)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(app))
        assertTrue(AirPlayPersistence.loadLegacyClusterEnabled(app))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(app))
        assertEquals(com.shilapi.xcertplay.airplay.CarPlayClusterDisplay.Content.MAP,
            AirPlayPersistence.loadClusterContent(app))
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        assertFalse(AirPlayPersistence.loadLegacyClusterEnabled(app))
        AirPlayPersistence.saveLegacyClusterEnabled(app, true)
        AirPlayPersistence.saveClusterMapEnabled(app, false)
        assertFalse(AdbClusterRouter.enabled(app))
    }

    @Test fun legacyRoutePreservesKnownDilink5And51Priority() {
        AirPlayPersistence.saveLegacyClusterEnabled(app, true)
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        shadowOf(app.getSystemService(DisplayManager::class.java).getDisplay(id)).apply {
            setName(DiLink51ClusterLayout.BASE)
            setFlags(Display.FLAG_PRESENTATION)
        }
        try { assertFalse(AdbClusterRouter.enabled(app)) }
        finally { ShadowDisplayManager.removeDisplay(id) }
        ShadowBuild.setFingerprint(DiLink51ClusterLayout.FINGERPRINT)
        assertFalse(AdbClusterRouter.enabled(app))
    }

    @Test fun legacyDirectTaskCanUseTheMeasuredPublicProjectionOnlyAfterOptIn() {
        val id = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        shadowOf(app.getSystemService(DisplayManager::class.java).getDisplay(id)).apply {
            setName(DiLink4ClusterDisplay.NAME)
            setFlags(Display.FLAG_PRESENTATION)
        }
        try {
            AirPlayPersistence.saveAdbClusterEnabled(app, true)
            assertFalse(AdbClusterRouter.enabled(app))
            AirPlayPersistence.saveLegacyClusterEnabled(app, true)
            assertTrue(AdbClusterRouter.enabled(app))
        } finally { ShadowDisplayManager.removeDisplay(id) }
    }

}
