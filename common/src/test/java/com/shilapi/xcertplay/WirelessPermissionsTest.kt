package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessPermissionsTest {
    @Test fun hotspotModesRequestLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 37),
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 29),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 31),
        )
    }

    @Test fun existingWifiRequestsLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 37),
        )
        assertEquals(emptyList<String>(), WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 29))
    }

    @Test fun aRefusalNamesOnlyTheAccessThatIsMissing() {
        val location = R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p
        val nearby = R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per
        val checkPermissions = R.string.could_not_start_carplay_return_to_diplay_and_check_app_per
        assertEquals(location, WirelessPermissions.refusalMessage(listOf(Manifest.permission.ACCESS_FINE_LOCATION)))
        // Android 12 asks for both; Location is named first, Nearby devices once Location is allowed.
        assertEquals(location, WirelessPermissions.refusalMessage(
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION)))
        assertEquals(nearby, WirelessPermissions.refusalMessage(listOf(Manifest.permission.BLUETOOTH_CONNECT)))
        assertEquals(nearby, WirelessPermissions.refusalMessage(
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_LOCAL_NETWORK)))
        // Android 17 with Nearby devices allowed: local network access alone is not under Nearby devices.
        assertEquals(checkPermissions, WirelessPermissions.refusalMessage(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK)))
    }
}
