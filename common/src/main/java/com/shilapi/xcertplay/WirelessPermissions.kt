package com.shilapi.xcertplay

import android.Manifest
import android.os.Build
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Runtime permissions a wireless CarPlay start needs per Android release. Android 17 requires
 * [Manifest.permission.ACCESS_LOCAL_NETWORK] for local traffic, including the inbound AirPlay
 * listener. A ROM where link-local IPv6 happened to work without it does not establish a general
 * permission exemption for IPv6.
 */
internal object WirelessPermissions {
    fun required(hotspotMode: WirelessHotspotMode, sdkInt: Int): List<String> = when {
        hotspotMode == WirelessHotspotMode.EXISTING_WIFI ->
            if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        sdkInt >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }.let { permissions ->
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
            permissions + Manifest.permission.ACCESS_LOCAL_NETWORK
        } else {
            permissions
        }
    }

    /**
     * What the CarPlay screen asks for once the wireless start permissions were refused (#290): Location,
     * or Nearby devices for Bluetooth and Nearby Wi-Fi devices. Anything else, such as Android 17's local
     * network access, is not under Nearby devices, so the screen only says to check the app permissions.
     */
    @StringRes
    fun refusalMessage(missing: Collection<String>): Int = when {
        Manifest.permission.ACCESS_FINE_LOCATION in missing || Manifest.permission.ACCESS_COARSE_LOCATION in missing ->
            R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p
        Manifest.permission.BLUETOOTH_CONNECT in missing || Manifest.permission.NEARBY_WIFI_DEVICES in missing ->
            R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per
        else -> R.string.could_not_start_carplay_return_to_diplay_and_check_app_per
    }
}
