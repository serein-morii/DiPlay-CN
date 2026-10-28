package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Attaches to a hotspot that is already running on this device.
 *
 * The hotspot remains owned by the system. This manager only locates its interface and reads the
 * channel/security data that the public Android APIs expose. Some vendors hide the current SoftAP
 * configuration, in which case the caller-supplied credentials remain authoritative and the iAP2
 * channel is reported as zero ("auto").
 */
class ManualHotspotManager(
    context: Context,
    ssid: String,
    passphrase: String,
    band: ManualHotspotBand,
    channel: Int,
    security: ManualHotspotSecurity,
    private val onDiagnostic: (String) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
    private val preferredInterface: String? = null,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val interfaces = ManualHotspotInterfaces(appContext, onDiagnostic, preferredInterface)
    private val waitLock = Object()
    private var confirmed: HotspotSelection? = null
    private var lastSampleLog = emptyList<String>()
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val expectedSsid = ssid
    private val passphrase = passphrase
    private val expectedBand = band
    private val expectedChannel = channel
    private val expectedSecurity = security.toIap2Security()

    @Volatile
    private var closed = false

    init {
        require(expectedSsid.isNotBlank()) { "ssid must not be blank" }
        require('\u0000' !in expectedSsid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        require(passphrase.isEmpty() || passphrase.length in 8..63) {
            "passphrase must be empty or between 8 and 63 characters"
        }
        require(channel in 0..196) { "channel must be 0 or in 1..196" }
        require(expectedSecurity == Iap2WirelessSecurity.NONE || passphrase.isNotEmpty()) {
            "passphrase is required for secured manual hotspots"
        }
        require(expectedSecurity != Iap2WirelessSecurity.NONE || passphrase.isEmpty()) {
            "passphrase must be empty for open manual hotspots"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ManualHotspotManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val readConfiguration = readApConfiguration()
        val companion = readConfiguration != null &&
            isFiveGhzCompanion(apSsid = readConfiguration.ssid, expectedSsid = expectedSsid)
        if (readConfiguration != null && readConfiguration.ssid != expectedSsid && !companion) {
            throw detailsDiffer(
                "Manual hotspot SSID does not match the active local AP configuration: " +
                    "'${readConfiguration.ssid}'",
            )
        }
        // The Android configuration describes the 2.4 GHz network, so its channel, band and
        // frequency say nothing about the companion; only its security carries over.
        val apConfiguration = if (companion) null else readConfiguration
        if (companion) onDiagnostic("Manual hotspot uses the car's 5 GHz companion network of the configured hotspot")
        validateApConfiguration(apConfiguration)

        // 只在候选证据变化时记录，避免每 250ms 重复输出。
        val selected = ManualHotspotReadiness(
            sample = {
                interfaces.sample().also { snapshot ->
                    val messages = mutableListOf<String>()
                    selectHotspotInterface(snapshot, messages::add)
                    if (messages != lastSampleLog) {
                        messages.forEach(onDiagnostic)
                        lastSampleLog = messages
                    }
                }
            },
            cancelled = { closed || isCancelled() },
            pause = { millis -> synchronized(waitLock) { if (!closed && !isCancelled()) waitLock.wait(millis) } },
            log = {},
            configuredInterface = preferredInterface,
        ).await(timeoutMillis)
        confirmed = selected
        onDiagnostic("hotspot interface confirmed iface=${selected.name} index=${selected.index} atNs=${System.nanoTime()}")
        val network = NetworkInterface.getByName(selected.name)
        val localInterface = LocalHotspotInterface(selected.name, selected.address,
            runCatching { network?.hardwareAddress?.toMacAddressString() }.getOrNull()
                ?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
                ?: HotspotInterfaceBssid.read(selected.name))
        val connectionFrequency = if (companion) null else frequencyFromConnectionInfo()
        val scanFrequency = if (companion) null else frequencyFromScanResult(localInterface)
        val channel = observedManualHotspotChannel(
            apChannel = apConfiguration?.channel ?: 0,
            connectionFrequencyMHz = connectionFrequency,
            scanFrequencyMHz = scanFrequency,
            apFrequencyMHz = apConfiguration?.frequencyMHz,
        )
        val frequencyMHz = when {
            apConfiguration?.frequencyMHz != null -> apConfiguration.frequencyMHz
            connectionFrequency != null -> connectionFrequency
            scanFrequency != null -> scanFrequency
            else -> null
        }
        val security = readConfiguration?.security ?: expectedSecurity
        // Keep the existing IPv4-first endpoint selection and also publish the selected AP's
        // scoped IPv6 address, using the same dual-stack policy as existing Wi-Fi connections.
        val hostAddresses = network?.let {
            existingWifiHostAddresses(Collections.list(it.inetAddresses), selected.index)
        }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(localInterface.hostAddress)
        onDiagnostic("Manual hotspot configReadable=${readConfiguration != null} " +
            "security=$security channelKnown=${channel > 0} " +
            "hardwareAddressKnown=${localInterface.hardwareAddress != null} iface=${localInterface.name} " +
            "family=${if (localInterface.hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
            "mdnsFamilies=${hostAddresses.joinToString("+") { address -> if (address is Inet6Address) "IPv6" else "IPv4" }}")
        if (security != Iap2WirelessSecurity.NONE && passphrase.isEmpty()) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is secured but no passphrase was provided")
        }

        if (channel == 0) {
            Log.w(
                TAG,
                "Could not read the active hotspot channel from Android public APIs; " +
                    "reporting iAP2 channel 0 (auto) instead of configured channel " +
                    "$expectedChannel",
            )
        }
        val observedBandLabel = wifiBandLabel(apConfiguration?.band)
        return WirelessHotspotInfo(
            ssid = expectedSsid,
            passphrase = passphrase,
            security = security,
            channel = channel,
            frequencyMHz = frequencyMHz,
            bssid = localInterface.hardwareAddress,
            interfaceName = localInterface.name,
            hostAddress = localInterface.hostAddress,
            hostAddresses = hostAddresses,
            bandLabel = when (expectedBand) {
                ManualHotspotBand.GHZ_2_4 -> "2.4 GHz"
                ManualHotspotBand.GHZ_5 -> "5 GHz"
                ManualHotspotBand.AUTO -> if (companion) "5 GHz" else
                    frequencyMHz?.let(::bandLabel) ?: observedBandLabel ?: "Auto"
            },
            backend = WirelessHotspotBackend.MANUAL_HOTSPOT,
        )
    }

    override fun validateReady() {
        val expected = confirmed ?: throw WirelessStartupException(
            WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
        )
        val current = selectHotspotInterface(interfaces.sample(), onDiagnostic)
        if (closed || isCancelled() || current == null || !expected.sameAddress(current)) {
            throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY,
                "Hotspot interface or address changed before publication")
        }
    }

    override fun close() {
        closed = true
        synchronized(waitLock) { waitLock.notifyAll() }
        interfaces.close()
    }

    private fun validateApConfiguration(configuration: ManualApConfiguration?) {
        configuration ?: return
        if (expectedChannel > 0 && configuration.channel > 0 &&
            configuration.channel != expectedChannel
        ) {
            throw detailsDiffer(
                "Manual hotspot channel ${configuration.channel} does not match configured " +
                "channel $expectedChannel",
            )
        }
        val actualBand = when (configuration.band) {
            1 -> ManualHotspotBand.GHZ_2_4
            2 -> ManualHotspotBand.GHZ_5
            else -> null
        }
        if (actualBand != null && expectedBand != ManualHotspotBand.AUTO &&
            actualBand != expectedBand
        ) {
            throw detailsDiffer(
                "Manual hotspot band ${wifiBandLabel(configuration.band)} does not match " +
                    "configured band ${wifiBandLabel(if (expectedBand == ManualHotspotBand.GHZ_2_4) 1 else 2)}",
            )
        }
        // WPA2 vs WPA3 variants are fine: the live security is what the iPhone is told (see start()).
        // Only an open/secured mismatch means the saved password cannot be right.
        if ((configuration.security == Iap2WirelessSecurity.NONE) != (expectedSecurity == Iap2WirelessSecurity.NONE)) {
            throw detailsDiffer(
                "Manual hotspot security ${configuration.security} does not match configured " +
                    "security $expectedSecurity",
            )
        }
        val frequency = configuration.frequencyMHz ?: return
        when (expectedBand) {
            ManualHotspotBand.GHZ_2_4 -> if (frequency !in 2_400..2_500) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is not running on 2.4 GHz")
            }
            ManualHotspotBand.GHZ_5 -> if (frequency !in 5_150..5_895) {
                throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION, "Manual hotspot is not running on 5 GHz")
            }
            ManualHotspotBand.AUTO -> Unit
        }
    }

    private fun frequencyFromConnectionInfo(): Int? {
        val connectionInfo = try {
            wifiManager.connectionInfo
        } catch (_: SecurityException) {
            null
        } ?: return null
        if (unquote(connectionInfo.ssid) != expectedSsid) return null
        return connectionInfo.frequency.takeIf { it > 0 }
    }

    private fun frequencyFromScanResult(localInterface: LocalHotspotInterface): Int? {
        val localBssid = localInterface.hardwareAddress ?: return null
        val scanResults = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            return null
        }
        return scanResults.firstOrNull { result ->
            result.SSID == expectedSsid &&
                result.BSSID.equals(localBssid, ignoreCase = true) &&
                result.frequency > 0
        }?.frequency
    }

    @SuppressLint("PrivateApi")
    private fun readApConfiguration(): ManualApConfiguration? =
        readSoftApConfiguration() ?: readLegacyApConfiguration()

    @SuppressLint("PrivateApi")
    private fun readSoftApConfiguration(): ManualApConfiguration? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
            val configuration = method.invoke(wifiManager) as? SoftApConfiguration
                ?: return null
            val ssid = configuration.ssid ?: return null
            val bandAndChannel = when {
                Build.VERSION.SDK_INT >= 36 -> {
                    val channels = configuration.channels
                    if (channels.size() == 0) null else channels.keyAt(0) to channels.valueAt(0)
                }
                else -> {
                    val band = (
                        SoftApConfiguration::class.java
                            .getMethod("getBand")
                            .invoke(configuration) as? Number
                        )?.toInt()
                    val channel = SoftApConfiguration::class.java
                        .getMethod("getChannel")
                        .invoke(configuration) as? Number
                    if (band == null || channel == null) null else band to channel.toInt()
                }
            }
            val band = bandAndChannel?.first
            val channel = bandAndChannel?.second ?: 0
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapSoftApSecurity(configuration.securityType),
            )
        } catch (_: Throwable) {
            null
        }
    }

    @SuppressLint("PrivateApi")
    private fun readLegacyApConfiguration(): ManualApConfiguration? {
        return try {
            val method = WifiManager::class.java.getMethod("getWifiApConfiguration")
            val configuration = method.invoke(wifiManager) as? WifiConfiguration
                ?: return null
            val ssid = unquote(configuration.SSID) ?: return null
            val channel = try {
                WifiConfiguration::class.java.getField("apChannel").getInt(configuration)
            } catch (_: ReflectiveOperationException) {
                0
            }
            val band = try {
                legacyHotspotBandToSoftApBand(WifiConfiguration::class.java.getField("apBand").getInt(configuration))
            } catch (_: ReflectiveOperationException) {
                null
            }
            ManualApConfiguration(
                ssid = ssid,
                band = band,
                channel = channel,
                frequencyMHz = wifiChannelToFrequencyMhz(channel, band),
                security = mapWifiConfigurationSecurity(configuration),
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun mapSoftApSecurity(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION ->
            Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        else -> Iap2WirelessSecurity.WPA_WPA2
    }

    private fun mapWifiConfigurationSecurity(
        configuration: WifiConfiguration,
    ): Iap2WirelessSecurity {
        val keyManagement = configuration.allowedKeyManagement ?: return Iap2WirelessSecurity.NONE
        val open = keyManagement.get(WifiConfiguration.KeyMgmt.NONE)
        val wpa2 = keyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)
        val sae = keyManagement.get(WifiConfiguration.KeyMgmt.SAE)
        return when {
            open && !wpa2 && !sae -> Iap2WirelessSecurity.NONE
            wpa2 && sae -> Iap2WirelessSecurity.WPA3_TRANSITION
            wpa2 -> Iap2WirelessSecurity.WPA_WPA2
            sae -> Iap2WirelessSecurity.WPA3_ONLY
            else -> Iap2WirelessSecurity.WPA_WPA2
        }
    }

    private fun bandLabel(frequencyMHz: Int): String = when (frequencyMHz) {
        in 2400..2500 -> "2.4 GHz"
        in 5150..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        else -> "Unknown band"
    }

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }

    private fun ByteArray.toMacAddressString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class ManualApConfiguration(
        val ssid: String,
        val band: Int?,
        val channel: Int,
        val frequencyMHz: Int?,
        val security: Iap2WirelessSecurity,
    )

    private class LocalHotspotInterface(
        val name: String,
        val hostAddress: InetAddress,
        val hardwareAddress: String?,
    )

    internal companion object {
        private const val TAG = "xcertplay-usb"
        private val FIVE_GHZ_SUFFIXES = listOf("_5G", "-5G")

        /**
         * Some head units configure one hotspot name in Android and also broadcast a 5 GHz network
         * named after it with "_5G" and the same password (#617). Android only knows the first, so
         * choosing the second failed as a name mismatch.
         */
        fun isFiveGhzCompanion(apSsid: String, expectedSsid: String): Boolean =
            apSsid.isNotEmpty() && FIVE_GHZ_SUFFIXES.any { expectedSsid.equals(apSsid + it, ignoreCase = true) }
    }
}

private fun detailsDiffer(message: String) = WirelessStartupException(
    WirelessStartupFailure.HOTSPOT_CONFIGURATION, message, ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER,
)

private fun ManualHotspotSecurity.toIap2Security(): Iap2WirelessSecurity = when (this) {
    ManualHotspotSecurity.OPEN -> Iap2WirelessSecurity.NONE
    ManualHotspotSecurity.WPA2 -> Iap2WirelessSecurity.WPA_WPA2
    ManualHotspotSecurity.WPA3_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
    ManualHotspotSecurity.WPA3 -> Iap2WirelessSecurity.WPA3_ONLY
}
