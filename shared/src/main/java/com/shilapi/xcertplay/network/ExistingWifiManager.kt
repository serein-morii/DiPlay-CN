package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Attaches to an existing station network. Never creates an AP, joins Wi-Fi or changes routing. */
class ExistingWifiManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
    private val preferredInterface: String? = null,
) : WirelessHotspotManager {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        ?: throw IllegalStateException("ConnectivityManager is unavailable")
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val lock = Any()
    private val invalidated = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var selected: Network? = null
    @Volatile private var host: InetAddress? = null
    @Volatile private var hosts: List<InetAddress> = emptyList()
    private var interfaceIndex = 0
    @Volatile private var interfaceName: String? = null
    private var callbackRegistered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == selected) invalidate("network lost")
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            // With an explicit interface the platform network may be discovered only after
            // attaching, so track the interface name until a network is selected.
            val tracked = network == selected ||
                (selected == null && properties.interfaceName == interfaceName)
            if (tracked && !sameLink(properties)) invalidate("interface or address changed")
        }
    }

    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null) {
            "Invalid existing Wi-Fi credentials"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ExistingWifiManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(selected == null) { "ExistingWifiManager has already started" }
        val started = System.nanoTime()
        // An explicit interface covers units whose station LAN runs on an interface Android never
        // reports as Wi-Fi, such as a USB NIC presenting itself as eth1 (#573 family). The SSID and
        // security checks below read WifiInfo, which such an interface cannot provide.
        val configured = preferredInterface?.trim()?.takeIf { it.isNotEmpty() }
        while (!closed) {
            // INTERNET/VALIDATED and the default route do not identify the CarPlay LAN.
            val network: Network? = if (configured != null) {
                connectivity.allNetworks.firstOrNull {
                    connectivity.getLinkProperties(it)?.interfaceName == configured
                }
            } else {
                val networks = connectivity.allNetworks.filter { candidate ->
                    connectivity.getNetworkCapabilities(candidate)?.let {
                        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true
                }
                if (networks.size > 1) {
                    throw IOException("Multiple Wi-Fi networks are active; keep one existing Wi-Fi connection")
                }
                networks.singleOrNull()
            }
            val properties = network?.let(connectivity::getLinkProperties)
            val name = properties?.interfaceName ?: configured
            val iface = name?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
            val linkAddresses: List<InetAddress> = when {
                properties != null -> properties.linkAddresses.map { it.address }
                iface != null -> Collections.list(iface.inetAddresses)
                else -> emptyList()
            }
            val address = if (iface == null) null else wirelessHostAddress(linkAddresses, iface.index)
            if (iface != null && name != null && address != null) {
                val addresses = existingWifiHostAddresses(linkAddresses, iface.index)
                var frequency: Int? = null
                var channel = 0
                var apBssid: ByteArray? = null
                var networkNameReadable = false
                if (configured == null) {
                    val capabilities = connectivity.getNetworkCapabilities(network)
                    val networkInfo = if (Build.VERSION.SDK_INT >= 31) capabilities?.transportInfo as? WifiInfo else null
                    // On-demand capabilities can redact SSID/BSSID even when the legacy station
                    // snapshot can supply them. A non-null redacted object must not hide that input.
                    @Suppress("DEPRECATION")
                    val stationInfo = try { wifi.connectionInfo } catch (_: SecurityException) { null }
                    val info = listOfNotNull(networkInfo, stationInfo).firstOrNull { readableSsid(it) != null }
                        ?: networkInfo ?: stationInfo
                    val liveSsid = readableSsid(info)
                    networkNameReadable = liveSsid != null
                    if (liveSsid != null && liveSsid != ssid) {
                        throw IOException("Configured Wi-Fi does not match the connected network; check Wi-Fi settings and saved details")
                    }
                    frequency = info?.frequency?.takeIf { it > 0 }
                    channel = frequency?.let(::wifiFrequencyMhzToChannel) ?: 0
                    apBssid = accessPointAddress(info?.bssid)
                    val observedSecurity = if (Build.VERSION.SDK_INT >= 31 && info != null) info.currentSecurityType else -1
                    if (Build.VERSION.SDK_INT >= 31) {
                        when (observedSecurity) {
                            WifiInfo.SECURITY_TYPE_OPEN -> if (passphrase.isNotEmpty()) {
                                throw IOException("Connected Wi-Fi is open; clear the saved Wi-Fi password")
                            }
                            WifiInfo.SECURITY_TYPE_PSK, WifiInfo.SECURITY_TYPE_SAE -> if (passphrase.isEmpty()) {
                                throw IOException("Connected Wi-Fi is secured; enter its WPA2 password")
                            }
                            WifiInfo.SECURITY_TYPE_UNKNOWN -> Unit
                            else -> throw IOException("Existing Wi-Fi requires an open or WPA2-Personal network")
                        }
                    }
                }
                synchronized(lock) {
                    check(!closed) { "ExistingWifiManager is closed" }
                    selected = network
                    host = address
                    hosts = addresses
                    interfaceIndex = iface.index
                    interfaceName = name
                    // With an explicit interface the network may lack the Wi-Fi transport (or not be
                    // visible at all), so the request drops the transport and matches by name.
                    val request = requestWithoutDefaultCapabilities()
                    if (configured == null) request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    connectivity.registerNetworkCallback(
                        request.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), callback)
                    callbackRegistered = true
                }
                // Close the gap between reading the link and registering the callback. With an
                // explicit interface that the platform does not expose as a Network there is no
                // LinkProperties to compare, so only the invalidation flag is checked.
                val current = if (network == null) null else connectivity.getLinkProperties(network)
                if ((current != null && !sameLink(current)) || invalidated.get()) {
                    throw IOException("Existing Wi-Fi changed during startup; connect again")
                }
                onDiagnostic("Existing Wi-Fi attached iface=$name host=${address.hostAddress} " +
                    "networkNameReadable=$networkNameReadable channel=$channel security=${security()} " +
                    "receiverIdentity=saved" + if (configured != null) " configuredInterface=$configured" else "")
                return WirelessHotspotInfo(ssid, passphrase, security(), channel, frequency,
                    // The router's BSSID is not this receiver's AirPlay device identity.
                    null, name, address, when {
                        frequency == null -> "Auto"
                        frequency < 2500 -> "2.4 GHz"
                        frequency < 5955 -> "5 GHz"
                        else -> "6 GHz"
                    }, WirelessHotspotBackend.EXISTING_WIFI, hostAddresses = addresses, accessPointBssid = apBssid)
            }
            if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                val interfaceMissing = configured != null &&
                    runCatching { NetworkInterface.getByName(configured) }.getOrNull() == null
                throw IOException(
                    if (interfaceMissing) {
                        "Existing Wi-Fi interface $configured was not found; check the interface setting"
                    } else {
                        "Existing Wi-Fi is not connected or has no usable address. Connect both devices to the same Wi-Fi in system settings"
                    },
                )
            }
            Thread.sleep(200)
        }
        throw IOException("Existing Wi-Fi attachment was cancelled")
    }

    private fun sameLink(properties: LinkProperties): Boolean =
        properties.interfaceName == interfaceName && properties.linkAddresses.any { it.address == host } &&
            existingWifiHostAddresses(properties.linkAddresses.map { it.address }, interfaceIndex).toSet() == hosts.toSet()

    private fun security(): Iap2WirelessSecurity =
        if (passphrase.isEmpty()) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2

    private fun readableSsid(info: WifiInfo?): String? = info?.ssid?.removeSurrounding("\"")
        ?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }

    private fun accessPointAddress(text: String?): ByteArray? {
        if (text == null || !text.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
        val bytes = text.split(':').map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.all { it == 0.toByte() } || bytes[0].toInt() and 1 != 0 ||
            text.equals("02:00:00:00:00:00", ignoreCase = true)) return null
        return bytes
    }

    private fun invalidate(reason: String) {
        if (!closed && invalidated.compareAndSet(false, true)) {
            onDiagnostic("Existing Wi-Fi $reason; restarting wireless session")
            onNetworkChanged()
        }
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed || invalidated.get()) "lost" else "attached"}"

    override fun close() {
        synchronized(lock) {
            closed = true
            if (callbackRegistered) {
                connectivity.unregisterNetworkCallback(callback)
                callbackRegistered = false
            }
        }
    }

    // NetworkRequest.Builder.clearCapabilities needs API 30; before that, drop the defaults one by one.
    private fun requestWithoutDefaultCapabilities(): NetworkRequest.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NetworkRequest.Builder().clearCapabilities()
        } else {
            NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
        }
}
