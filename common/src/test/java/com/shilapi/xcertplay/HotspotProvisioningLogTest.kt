package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.CarHotspotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "en", manifest = Config.NONE)
class HotspotProvisioningLogTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ShadowLog.clear()
        CarHotspotSettings.setConfigWrite(app, true)
    }

    @Test fun provisioningAHotspotDoesNotLogItsName() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        DiPlayActivity::class.java.getDeclaredMethod("launchHotspotCredentials")
            .apply { isAccessible = true }.invoke(activity)
        val ssid = AirPlayPersistence.loadManualHotspotSsid(app)
        assertTrue(ssid.isNotBlank())
        val lines = ShadowLog.getLogsForTag("DiPlay-Hotspot")
        assertTrue("provisioning must still leave a log line", lines.any { it.msg.startsWith("provisioned") })
        lines.forEach { assertFalse("hotspot name reached logcat: ${it.msg}", it.msg.contains(ssid)) }
    }

    @Test fun anExistingHotspotIsNotProvisionedOrLogged() {
        AirPlayPersistence.saveManualHotspotSsid(app, "Car hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(app, "12345678")
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        DiPlayActivity::class.java.getDeclaredMethod("launchHotspotCredentials")
            .apply { isAccessible = true }.invoke(activity)
        assertEquals("Car hotspot", AirPlayPersistence.loadManualHotspotSsid(app))
        assertTrue(ShadowLog.getLogsForTag("DiPlay-Hotspot").none { it.msg.startsWith("provisioned") })
    }
}
