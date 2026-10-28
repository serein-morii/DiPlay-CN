package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.network.WifiP2pChannels
import java.net.Inet6Address
import java.net.InetAddress

enum class CarPlayTransport {
    WIRED,
    WIRELESS,
}

enum class MfiTarget {
    LOCAL,
    USB_CH341,
    I2C,
    REMOTE,
}

enum class WirelessHotspotMode {
    WIFI_P2P,
    LOCAL_ONLY_HOTSPOT,
    MANUAL,
    EXISTING_WIFI,
}

enum class ManualHotspotBand {
    AUTO,
    GHZ_2_4,
    GHZ_5,
}

enum class ManualHotspotSecurity {
    OPEN,
    WPA2,
    WPA3_TRANSITION,
    WPA3,
}

/**
 * Deployment-owned constants for one head unit. There are deliberately no built-in Apple or
 * CH341 product IDs: the physical devices attached to the target must be identified first.
 */
class CarPlayRuntimeConfig(
    val iphoneDevices: List<UsbDeviceId> = emptyList(),
    val mfiTarget: MfiTarget = MfiTarget.USB_CH341,
    val ch341Devices: List<UsbDeviceId> = emptyList(),
    val ch341MfiResetGpio: Int? = null,
    val linuxI2cPath: String? = null,
    val remoteMfiServer: String? = null,
    val remoteMfiToken: String? = null,
    val hostMac: ByteArray = DEFAULT_HOST_MAC,
    val linkLocal: String = "fe80::2",
    val identification: Iap2IdentificationConfig,
    val availableCurrentMilliAmps: Int = DEFAULT_AVAILABLE_CURRENT_MILLI_AMPS,
    val label: String = "xcertplay",
    val hostName: String = "xcertplay",
    val transport: CarPlayTransport = CarPlayTransport.WIRED,
    val wirelessHotspotMode: WirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
    val manualHotspotSsid: String? = null,
    val manualHotspotPassphrase: String? = null,
    val manualHotspotBand: ManualHotspotBand = ManualHotspotBand.AUTO,
    val manualHotspotChannel: Int = 0,
    val manualHotspotSecurity: ManualHotspotSecurity = ManualHotspotSecurity.WPA2,
    /** Interface override for head units that keep their hotspot off the Wi-Fi tether list (#573). */
    val manualHotspotInterface: String? = null,
    val wirelessBluetoothDeviceAddress: String? = null,
    val locationReportingEnabled: Boolean = false,
    val wifiP2pPreferredChannel: Int = WifiP2pChannels.AUTO,
    val existingWifiSsid: String = "",
    val existingWifiPassphrase: String = "",
    /** Interface override for units whose station LAN runs on an interface Android does not report as Wi-Fi, e.g. a USB NIC on eth1. */
    val existingWifiInterface: String? = null,
) {
    init {
        require(iphoneDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
        require(hostMac.size == 6) { "hostMac must be 6 bytes" }
        require(isLinkLocalIpv6(linkLocal)) { "linkLocal must be a link-local IPv6 literal" }
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(label.isNotBlank()) { "label must not be blank" }
        require(hostName.isNotBlank()) { "hostName must not be blank" }
        require(mfiTarget != MfiTarget.USB_CH341 || ch341Devices.isNotEmpty()) {
            "CH341 devices must be configured for the USB/CH341 MFi target"
        }
        require(mfiTarget != MfiTarget.I2C || !linuxI2cPath.isNullOrBlank()) {
            "A Linux I2C path must be configured for the I2C MFi target"
        }
        require(mfiTarget != MfiTarget.REMOTE || !remoteMfiServer.isNullOrBlank()) {
            "A server address must be configured for the remote MFi target"
        }
        require(ch341MfiResetGpio == null || ch341MfiResetGpio in 0..5) {
            "CH341 MFi reset GPIO must be D0..D5"
        }
        require(remoteMfiServer?.contains('\u0000') != true) {
            "Remote MFi server must not contain U+0000"
        }
        require(remoteMfiToken?.contains('\u0000') != true) {
            "Remote MFi token must not contain U+0000"
        }
        // Only a wireless session starts the hotspot; a USB session must not fail on unused settings.
        if (transport == CarPlayTransport.WIRELESS && wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            require(ManualHotspotValidation.error(existingWifiSsid, existingWifiPassphrase) == null) {
                "Existing Wi-Fi requires an SSID of at most 32 UTF-8 bytes and an empty (open) or 8–63 character WPA2 password"
            }
        }
        if (transport == CarPlayTransport.WIRELESS && wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P) {
            require(WifiP2pChannels.isValid(wifiP2pPreferredChannel)) {
                "Unsupported Wi-Fi Direct channel: $wifiP2pPreferredChannel"
            }
        }
        if (transport == CarPlayTransport.WIRELESS && wirelessHotspotMode == WirelessHotspotMode.MANUAL) {
            val ssid = manualHotspotSsid
            require(!ssid.isNullOrBlank()) {
                "manualHotspotSsid is required in manual hotspot mode"
            }
            require('\u0000' !in ssid) {
                "manualHotspotSsid must not contain U+0000"
            }
            val passphrase = manualHotspotPassphrase.orEmpty()
            require('\u0000' !in passphrase) {
                "manualHotspotPassphrase must not contain U+0000"
            }
            require(passphrase.isEmpty() || passphrase.length in 8..63) {
                "manualHotspotPassphrase must be empty or between 8 and 63 characters"
            }
            require(manualHotspotChannel in 0..196) {
                "manualHotspotChannel must be 0 or in 1..196"
            }
            require(
                manualHotspotSecurity == ManualHotspotSecurity.OPEN ||
                    passphrase.length in 8..63,
            ) {
                "A passphrase between 8 and 63 characters is required for secured manual hotspots"
            }
            require(
                manualHotspotChannel == 0 ||
                    isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel),
            ) {
                "manualHotspotChannel is not valid for the selected manual hotspot band"
            }
            require(
                manualHotspotSecurity == ManualHotspotSecurity.OPEN || passphrase.isNotEmpty(),
            ) {
                "manualHotspotPassphrase is required for secured manual hotspots"
            }
        }
    }

    companion object {
        const val APPLE_VENDOR_ID = 0x05ac
        /** The wired iAP2 charging offer; the iPhone may draw up to this much from the car's USB port. */
        const val DEFAULT_AVAILABLE_CURRENT_MILLI_AMPS = 2400
        val DEFAULT_HOST_MAC = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)
        private fun isLinkLocalIpv6(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                val address = InetAddress.getByName(value)
                address is Inet6Address && address.isLinkLocalAddress
            } catch (_: Exception) {
                false
            }
        }
    }
}

fun isManualHotspotChannelCompatible(band: ManualHotspotBand, channel: Int): Boolean = when (band) {
    ManualHotspotBand.AUTO -> channel in 1..196
    ManualHotspotBand.GHZ_2_4 -> channel in 1..14
    ManualHotspotBand.GHZ_5 -> channel in 32..177
}
