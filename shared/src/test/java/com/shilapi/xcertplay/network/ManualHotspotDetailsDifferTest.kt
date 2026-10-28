package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android 8.0 hid the saved hotspot configuration from ordinary apps, so DiPlay can compare it with its own only on 7.1 and earlier. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class ManualHotspotDetailsDifferTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun carHotspot(ssid: String, legacyBand: Int = 0) {
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"12345678\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        WifiConfiguration::class.java.getField("apBand").setInt(config, legacyBand)
        WifiManager::class.java.getMethod("setWifiApConfiguration", WifiConfiguration::class.java)
            .invoke(context.getSystemService(WifiManager::class.java), config)
    }

    private fun startFailure(band: ManualHotspotBand = ManualHotspotBand.AUTO): WirelessStartupException {
        val manager = ManualHotspotManager(context, "Car", "12345678", band, 0, ManualHotspotSecurity.WPA2)
        var failure: Throwable? = null
        try {
            thread { failure = runCatching { manager.start(1_000) }.exceptionOrNull() }.join()
        } finally { manager.close() }
        return failure as WirelessStartupException
    }

    @Test fun aCarHotspotWithAnotherNameIsNamed() {
        carHotspot("Other")
        val failure = startFailure()
        assertEquals(WirelessStartupFailure.HOTSPOT_CONFIGURATION, failure.reason)
        assertEquals("Manual hotspot SSID does not match the active local AP configuration: 'Other'", failure.message)
        assertEquals(ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER, failure.knownCause)
    }

    @Test fun aCarHotspotOnAnotherBandIsNamed() {
        carHotspot("Car", legacyBand = 1)
        val failure = startFailure(ManualHotspotBand.GHZ_2_4)
        assertEquals(WirelessStartupFailure.HOTSPOT_CONFIGURATION, failure.reason)
        assertEquals("Manual hotspot band 5 GHz does not match configured band 2.4 GHz", failure.message)
        assertEquals(ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER, failure.knownCause)
    }
}
