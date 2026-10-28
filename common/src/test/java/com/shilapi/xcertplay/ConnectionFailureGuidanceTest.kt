package com.shilapi.xcertplay

import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ConnectionFailureGuidanceTest {
    @Test fun typedFailuresHavePriorityOverMessages() {
        assertEquals(R.string.connection_help_reset_wifi,
            guide("unknown", reset = true, startup = WirelessStartupFailure.FIRST_TCP_TIMEOUT))
        assertEquals(R.string.connection_help_bluetooth_unusable,
            guide("usb permission denied", startup = WirelessStartupFailure.BLUETOOTH_UNUSABLE))
        assertEquals(R.string.connection_help_wifi_direct_beside_hotspot, guide("Wi-Fi P2P createGroup failed",
            startup = WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT, mode = WirelessHotspotMode.WIFI_P2P))
        assertEquals(R.string.connection_help_hotspot,
            guide("unknown", startup = WirelessStartupFailure.HOTSPOT_NOT_READY))
        assertEquals(R.string.connection_help_hotspot,
            guide("unknown", startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(R.string.connection_help_iphone_wifi,
            guide("unknown", startup = WirelessStartupFailure.FIRST_TCP_TIMEOUT))
    }

    @Test fun aKnownCauseNamesItsOwnHelp() {
        assertEquals(R.string.connection_help_chosen_iphone_not_paired, guide(
            "The selected iPhone is no longer paired. Choose it again in DiPlay.", known = ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED))
        assertEquals(R.string.on_your_iphone_open_settings_bluetooth_and_pair_with_the_c, guide(
            "No unambiguous bonded iPhone found; pair one iPhone and retry", known = ConnectionFailureCause.NO_PAIRED_IPHONE))
        assertEquals(R.string.connection_help_choose_iphone, guide(
            "No unambiguous bonded iPhone found; pair one iPhone and retry", known = ConnectionFailureCause.IPHONE_NOT_CHOSEN))
        assertEquals(R.string.connection_help_wifi_direct_channel, guide(channelRejected,
            known = ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE, mode = WirelessHotspotMode.WIFI_P2P))
    }

    @Test fun aHotspotCauseNamesItsOwnHelp() {
        assertEquals(R.string.connection_help_car_hotspot_off, guide(carHotspotOff,
            startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION, known = ConnectionFailureCause.CAR_HOTSPOT_OFF))
        assertEquals(R.string.connection_help_car_hotspot_details_differ,
            guide("Manual hotspot SSID does not match the active local AP configuration: 'Other'",
                startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION, known = ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER))
        assertEquals(R.string.connection_help_hotspot_interface_not_found, guide(hotspotNotReady,
            startup = WirelessStartupFailure.HOTSPOT_NOT_READY, known = ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND))
        assertEquals(R.string.connection_help_hotspot, guide("Timed out waiting for the car hotspot",
            startup = WirelessStartupFailure.HOTSPOT_NOT_READY, known = ConnectionFailureCause.CAR_HOTSPOT_START_TIMED_OUT))
        assertEquals(R.string.connection_help_hotspot, guide("The car could not start its hotspot",
            startup = WirelessStartupFailure.HOTSPOT_NOT_READY, known = ConnectionFailureCause.CAR_HOTSPOT_START_FAILED))
    }

    @Test fun aResetComesFirstAndAKnownCauseBeforeAStartupFailure() {
        assertEquals(R.string.connection_help_reset_wifi,
            guide(channelRejected, reset = true, known = ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE))
        assertEquals(R.string.connection_help_hotspot,
            guide(carHotspotOff, startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION))
        assertEquals(R.string.connection_help_car_hotspot_off, guide(carHotspotOff,
            startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION, known = ConnectionFailureCause.CAR_HOTSPOT_OFF))
    }

    @Test fun messageHintsAreTransportAwareAndSafeForUnknownErrors() {
        assertEquals(R.string.connection_help_permissions, guide("USB permission was denied", wireless = false))
        assertEquals(R.string.connection_help_usb, guide("NCM data path failed", wireless = false))
        assertEquals(R.string.connection_help_bluetooth, guide("RFCOMM socket closed"))
        assertEquals(R.string.connection_help_hotspot, guide("createGroup failed"))
        assertEquals(R.string.connection_help_iphone_wifi, guide("AirPlay TCP timeout"))
        assertEquals(R.string.connection_help_general, guide("opaque error"))
        assertEquals(R.string.connection_help_general, guide("opaque error", wireless = false))
        assertEquals(R.string.connection_help_general, guide("Bluetooth error", wireless = false))
    }

    @Test fun onlyTheCarHotspotAsksTheDriverToJoinANetworkOnTheIphone() {
        for (mode in listOf(WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT)) {
            assertEquals(R.string.connection_help_wifi_direct,
                guide("unknown", startup = WirelessStartupFailure.FIRST_TCP_TIMEOUT, mode = mode))
            assertEquals(R.string.connection_help_wifi_direct, guide("createGroup failed", mode = mode))
            assertEquals(R.string.connection_help_wifi_direct, guide("AirPlay TCP timeout", mode = mode))
        }
        assertEquals(R.string.connection_help_existing_wifi,
            guide("unknown", startup = WirelessStartupFailure.FIRST_TCP_TIMEOUT, mode = WirelessHotspotMode.EXISTING_WIFI))
        assertEquals(R.string.connection_help_existing_wifi,
            guide("unknown", startup = WirelessStartupFailure.HOTSPOT_CONFIGURATION, mode = WirelessHotspotMode.EXISTING_WIFI))
    }

    @Test fun aPermissionAndroidRefusesItselfIsNotOneTheDriverCanGrant() {
        // #671: MIUI 13 refused RFCOMM with "lacks permission android.permission.BLUETOOTH".
        assertEquals(R.string.connection_help_general, guide(
            "Could not connect RFCOMM to [address] <- SecurityException: UID 10211 / PID 12347 lacks permission android.permission.BLUETOOTH"))
        assertEquals(R.string.connection_help_permissions, guide("Hotspot control permission is missing"))
    }

    @Test fun thePermissionTipDoesNotAskForUsbTrustPrompts() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        assertFalse(app.getString(R.string.connection_help_permissions).contains("Trust"))
    }

    @Test fun recoveryMessageReplacesSetupTipAndClearsOnNextStage() {
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val normal = TextView(activity).apply { visibility = View.VISIBLE }
            val help = TextView(activity).apply { visibility = View.GONE }
            for ((field, value) in listOf("connectionInstructionsView" to normal, "connectionHelpView" to help)) {
                activity.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(activity, value)
            }
            val method = activity.javaClass.getDeclaredMethod("showConnectionHelp", CarPlayStatus.Failed::class.java)
                .apply { isAccessible = true }
            method.invoke(activity, CarPlayStatus.Failed("USB permission was denied"))
            assertEquals(View.GONE, normal.visibility)
            assertEquals(View.VISIBLE, help.visibility)
            assertEquals(activity.getString(R.string.connection_help_permissions), help.text.toString())
            assertTrue(help.text.isNotEmpty())

            method.invoke(activity, null)
            assertEquals(View.VISIBLE, normal.visibility)
            assertEquals(View.GONE, help.visibility)
        } finally {
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                (activity.javaClass.getDeclaredField(field).apply { isAccessible = true }
                    .get(activity) as java.util.concurrent.ExecutorService).shutdownNow()
            }
        }
    }

    private val channelRejected = "Could not establish WIFI_P2P hotspot: Wi-Fi Direct could not use channel 149. " +
        "Choose Auto or another channel. Wi-Fi P2P createGroup failed: ERROR (code=0)"
    private val carHotspotOff = "The car hotspot is off. Turn it on in the car settings and connect again."
    private val hotspotNotReady = "Could not establish MANUAL hotspot: Hotspot network is not ready"

    private fun guide(
        message: String,
        wireless: Boolean = true,
        reset: Boolean = false,
        startup: WirelessStartupFailure? = null,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
        known: ConnectionFailureCause? = null,
    ): Int = ConnectionFailureGuidance.message(CarPlayStatus.Failed(message, reset, startup, known), wireless, mode)
}
