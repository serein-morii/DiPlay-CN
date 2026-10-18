package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.Locale

/** Suggested user action, never a diagnosis inferred from an arbitrary exception message. */
internal object ConnectionFailureGuidance {
    @StringRes
    fun message(
        failure: CarPlayStatus.Failed,
        wireless: Boolean,
        mode: WirelessHotspotMode = WirelessHotspotMode.MANUAL,
    ): Int {
        if (failure.wifiResetRequired) return R.string.connection_help_reset_wifi
        when (failure.startupFailure) {
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> return R.string.connection_help_bluetooth_unusable
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT -> return R.string.connection_help_wifi_direct_beside_hotspot
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> return network(mode)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> return iphoneWifi(mode)
            null -> Unit
        }

        val error = failure.message.lowercase(Locale.ROOT)
        // A permission Android itself refuses (as on MIUI 13, #671) is not one the driver can grant.
        if ("securityexception" in error || "lacks permission" in error) return R.string.connection_help_general
        if ("permission" in error || "denied" in error) return R.string.connection_help_permissions
        if (!wireless) {
            return if (listOf("usb", "ncm", "configuration", "iphone", "cable").any { it in error }) {
                R.string.connection_help_usb
            } else {
                R.string.connection_help_general
            }
        }
        if ("bluetooth" in error || "rfcomm" in error || "paired" in error) {
            return R.string.connection_help_bluetooth
        }
        if ("hotspot" in error || "access point" in error || "creategroup" in error) {
            return network(mode)
        }
        if ("wifi" in error || "wi-fi" in error || "tcp" in error || "airplay" in error) {
            return iphoneWifi(mode)
        }
        return R.string.connection_help_general
    }

    /** The network the iPhone joins: the car hotspot, a Wi-Fi Direct group, or a shared Wi-Fi. */
    @StringRes
    private fun network(mode: WirelessHotspotMode): Int = when (mode) {
        WirelessHotspotMode.MANUAL -> R.string.connection_help_hotspot
        WirelessHotspotMode.EXISTING_WIFI -> R.string.connection_help_existing_wifi
        WirelessHotspotMode.WIFI_P2P, WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> R.string.connection_help_wifi_direct
    }

    /** Only the car hotspot is a network the driver joins on the iPhone. */
    @StringRes
    private fun iphoneWifi(mode: WirelessHotspotMode): Int =
        if (mode == WirelessHotspotMode.MANUAL) R.string.connection_help_iphone_wifi else network(mode)
}
