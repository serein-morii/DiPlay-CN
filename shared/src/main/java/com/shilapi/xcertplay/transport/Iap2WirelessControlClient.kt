package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessMessages
import com.shilapi.xcertplay.iap2.message.Iap2WirelessSessionParameters
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import kotlin.math.min

/**
 * The wireless LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, the five update subscriptions, then Wi-Fi credentials and the
 * 0x4E0D/0x4E0E Wireless CarPlay transport notifications.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-control CSM frame to [onIncoming].
 */
class Iap2WirelessControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        vehicleStatusProvider: VehicleStatusProvider? = null,
        onReady: () -> Unit = {},
        beforeStartSession: () -> Unit = {},
        onStartSessionSent: (Iap2StartSessionSent) -> Unit = {},
        onIncoming: (Iap2Frame) -> Unit = {},
        // Evaluated only in the authenticated control loop when its original deadline expires.
        // The caller must prove that the current CarPlay session is usable before keeping it alive.
        keepAliveAfterDeadline: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): Iap2WirelessControlResult {
        require(identification.wireless != null) {
            "Wireless control requires an Iap2IdentificationConfig with wireless transport"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadline = WirelessControlDeadline(timeoutMillis)
        val identified = identification.withVehicleStatusFrom(vehicleStatusProvider)
        if (identified.vehicleStatusEnabled != identification.vehicleStatusEnabled) {
            onProgress("iap2 no battery reading: not declaring an electric vehicle")
        }
        Iap2IdentificationClient(session).identify(identified, deadline.requireRemaining(), onProgress)
        onProgress("iap2 identification accepted")
        var stage = Iap2WirelessControlStage.IDENTIFIED

        mfi.run(session, deadline.requireRemaining(), onProgress)
        stage = Iap2WirelessControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")

        for (subscription in Iap2WiredControlClient.subscriptions()) {
            session.send(subscription, deadline.requireRemaining())
        }
        stage = Iap2WirelessControlStage.SUBSCRIBED
        onProgress("iap2 subscriptions sent")
        onReady()

        fun sendControl(frame: Iap2Frame) {
            session.send(frame, deadline.requireRemaining(keepAliveAfterDeadline))
        }

        var forwardedFrames = 0
        val rxLog = Iap2RxLog()
        var wifiConfigurationsSent = 0
        var carPlayStartSessionsSent = 0
        var preTransportWiFiConfigurationsSent = 0
        var postTransportWiFiConfigurationsSent = 0
        var transportNotificationSeen = false
        var wirelessCarPlayAvailableSeen = false
        val location = Iap2LocationReporter(locationProvider, onProgress)
        val vehicleStatus = Iap2VehicleStatusReporter(vehicleStatusProvider, onProgress)
        try {
            while (true) {
                val remaining = deadline.remainingMillis(keepAliveAfterDeadline)
                if (remaining == 0L) {
                    return Iap2WirelessControlResult(
                        Iap2WirelessControlTerminal.TIMED_OUT,
                        stage,
                        forwardedFrames,
                        wifiConfigurationsSent,
                        carPlayStartSessionsSent,
                        transportNotificationSeen,
                        postTransportWiFiConfigurationsSent,
                        wirelessCarPlayAvailableSeen,
                    )
                }
                location.tick(::sendControl)
                vehicleStatus.tick(::sendControl)
                val pollTimeout = vehicleStatus.pollTimeout(location.pollTimeout(remaining))
                val incoming = session.recv(pollTimeout)
                if (incoming == null) {
                    if (session.isClosed) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                        )
                    }
                    if (deadline.remainingMillis(keepAliveAfterDeadline) == 0L) {
                        return Iap2WirelessControlResult(
                            Iap2WirelessControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            wifiConfigurationsSent,
                            carPlayStartSessionsSent,
                            transportNotificationSeen,
                            postTransportWiFiConfigurationsSent,
                            wirelessCarPlayAvailableSeen,
                        )
                    }
                    continue
                }

                when (incoming.messageId) {
                    REQUEST_ACCESSORY_WIFI_CONFIGURATION -> {
                        onProgress("iap2 rx=0x5702 request-wifi-configuration")
                        val postTransport = transportNotificationSeen
                        val sentCount = if (postTransport) {
                            postTransportWiFiConfigurationsSent
                        } else {
                            preTransportWiFiConfigurationsSent
                        }
                        val limit = if (postTransport) {
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        } else {
                            MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        }
                        if (sentCount >= limit) {
                            onProgress(
                                "iap2 0x5703 ignored: maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            sendControl(accessoryWiFiConfiguration(endpoint))
                            stage = later(
                                stage,
                                if (postTransport) {
                                    Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT
                                } else {
                                    Iap2WirelessControlStage.WIFI_CONFIG_SENT
                                },
                            )
                            wifiConfigurationsSent++
                            if (postTransport) {
                                postTransportWiFiConfigurationsSent++
                            } else {
                                preTransportWiFiConfigurationsSent++
                            }
                            onProgress("iap2 tx=0x5703 accessory-wifi-configuration")
                        }
                    }

                    CARPLAY_AVAILABILITY -> {
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        onProgress(carPlayAvailabilityDiagnostic(incoming))
                        beforeStartSession()
                        sendStartSession(endpoint, ::sendControl, onStartSessionSent)
                        stage = later(stage, Iap2WirelessControlStage.CARPLAY_START_SENT)
                        carPlayStartSessionsSent++
                        onProgress("iap2 tx=0x4301 carplay-start-session")
                    }

                    WIRELESS_CARPLAY_UPDATE -> {
                        val available = Iap2WirelessMessages.wirelessCarPlayAvailability(incoming)
                        if (available) wirelessCarPlayAvailableSeen = true
                        onProgress("iap2 rx=0x4e0d wireless-carplay-update available=$available")
                    }

                    DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION -> {
                        transportNotificationSeen = true
                        val identifiers = Iap2WirelessMessages.deviceTransportIdentifier(incoming)
                        stage = later(stage, Iap2WirelessControlStage.TRANSPORT_NOTIFIED)
                        onProgress(
                            "iap2 rx=0x4e0e device-transport-identifier " +
                                "bluetooth=${identifiers.bluetoothMac ?: "none"} " +
                                "usb=${identifiers.usbTransportIdentifier ?: "none"}; " +
                                "resending 0x5703",
                        )
                        if (
                            postTransportWiFiConfigurationsSent >=
                            MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS
                        ) {
                            onProgress(
                                "iap2 post-transport 0x5703 ignored: " +
                                    "maximum Wi-Fi configuration sends reached",
                            )
                        } else {
                            sendControl(accessoryWiFiConfiguration(endpoint))
                            stage = later(
                                stage,
                                Iap2WirelessControlStage.POST_TRANSPORT_WIFI_CONFIG_SENT,
                            )
                            wifiConfigurationsSent++
                            postTransportWiFiConfigurationsSent++
                            onProgress(
                                "iap2 tx=0x5703 post-transport accessory-wifi-configuration",
                            )
                        }
                    }

                    Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES, Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> {
                        vehicleStatus.handle(incoming, ::sendControl)
                    }

                    Iap2LocationMessages.START_LOCATION_INFORMATION,
                    Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
                        location.handle(incoming, ::sendControl)
                    }

                    else -> {
                        rxLog.line(incoming.messageId)?.let(onProgress)
                        onIncoming(incoming)
                        forwardedFrames++
                    }
                }
            }
        } finally {
            locationProvider?.stop()
        }
    }

    companion object {
        private const val REQUEST_ACCESSORY_WIFI_CONFIGURATION = 0x5702
        private const val ACCESSORY_WIFI_CONFIGURATION = 0x5703
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val WIRELESS_CARPLAY_UPDATE = 0x4e0d
        private const val DEVICE_TRANSPORT_IDENTIFIER_NOTIFICATION = 0x4e0e
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_PRE_TRANSPORT_WIFI_CONFIGURATION_SENDS = 5
        private const val MAX_POST_TRANSPORT_WIFI_CONFIGURATION_SENDS = 2

        /** Malformed optional availability metadata must not change existing control behavior. */
        internal fun carPlayAvailabilityDiagnostic(frame: Iap2Frame): String = try {
            val value = Iap2CarPlayMessages.availability(frame)
            "iap2 availability wired=${value.wired?.available ?: "unknown"} " +
                "wireless=${value.wireless?.available ?: "unknown"} " +
                "themeAssets=${value.themeAssets?.available ?: "unknown"}"
        } catch (error: Exception) {
            "iap2 availability decode=failed failureClass=${error.javaClass.simpleName}"
        }

        /** Optional AP hint is independent of the AirPlay receiver identity. */
        fun accessoryWiFiConfiguration(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2WirelessMessages.accessoryWiFiConfiguration(
                ssid = endpoint.ssid,
                passphrase = endpoint.passphrase,
                channel = endpoint.channel,
                securityType = endpoint.security.wireValue,
                bssid = endpoint.accessPointBssid,
            )

        /** Wireless 0x4301 reply carrying the receiver address, port and pairing identity. */
        fun carPlayStartSession(endpoint: Iap2WirelessCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wireless = Iap2WirelessSessionParameters(
                    ssid = endpoint.ssid,
                    passphrase = endpoint.passphrase,
                    channel = endpoint.channel,
                    ipAddresses = endpoint.ipAddresses,
                    securityType = endpoint.security.wireValue,
                ),
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        private fun later(
            current: Iap2WirelessControlStage,
            next: Iap2WirelessControlStage,
        ): Iap2WirelessControlStage = if (current.ordinal >= next.ordinal) current else next
    }
}

/** Wireless 0x5703 security values. */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}

/** Wireless hotspot and AirPlay endpoint sent in 0x5703 and 0x4301. */
class Iap2WirelessCarPlayEndpoint(
    val ssid: String,
    val passphrase: String,
    val channel: Int,
    val security: Iap2WirelessSecurity,
    ipAddresses: List<String>,
    val airPlayPort: Int,
    val deviceIdentifier: String,
    val publicKey: String,
    val sourceVersion: String,
    accessPointBssid: ByteArray? = null,
) {
    val ipAddresses: List<String> = ipAddresses.toList()
    val accessPointBssid: ByteArray? = accessPointBssid?.copyOf()

    init {
        require(accessPointBssid == null || accessPointBssid.size == 6) { "AP address must contain six bytes" }
        require(ssid.isNotBlank()) { "ssid is required and must not be blank" }
        require('\u0000' !in ssid) { "ssid must not contain U+0000" }
        require('\u0000' !in passphrase) { "passphrase must not contain U+0000" }
        if (security != Iap2WirelessSecurity.NONE) {
            require(passphrase.isNotEmpty()) { "passphrase is required for secured Wi-Fi" }
        }
        require(channel in 0..0xff) { "channel must be in 0..255" }
        require(ipAddresses.isNotEmpty()) { "At least one wireless IP address is required" }
        require(ipAddresses.all { it.isNotBlank() && '\u0000' !in it }) {
            "Every wireless IP address must be non-blank and must not contain U+0000"
        }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(deviceIdentifier.isNotBlank()) { "deviceIdentifier is required and must not be blank" }
        require('\u0000' !in deviceIdentifier) { "deviceIdentifier must not contain U+0000" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
    }
}

enum class Iap2WirelessControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    WIFI_CONFIG_SENT,
    CARPLAY_START_SENT,
    TRANSPORT_NOTIFIED,
    POST_TRANSPORT_WIFI_CONFIG_SENT,
}

enum class Iap2WirelessControlTerminal { CHANNEL_CLOSED, TIMED_OUT }

/** End state of the control loop only; it is not evidence of a live Wi-Fi or AirPlay session. */
data class Iap2WirelessControlResult(
    val terminal: Iap2WirelessControlTerminal,
    val stage: Iap2WirelessControlStage,
    val forwardedFrames: Int,
    val wifiConfigurationsSent: Int,
    val carPlayStartSessionsSent: Int,
    val transportNotificationSeen: Boolean,
    val postTransportWiFiConfigurationsSent: Int,
    val wirelessCarPlayAvailableSeen: Boolean,
)

/** 仅在 StartSession 发送成功后产生，不通过日志驱动超时。 */
data class Iap2StartSessionSent(val sentAtNanos: Long)

internal fun sendStartSession(
    endpoint: Iap2WirelessCarPlayEndpoint,
    send: (Iap2Frame) -> Unit,
    onSent: (Iap2StartSessionSent) -> Unit,
) {
    send(Iap2WirelessControlClient.carPlayStartSession(endpoint))
    onSent(Iap2StartSessionSent(System.nanoTime()))
}

/** A bounded bring-up deadline that a proven live session can extend for ongoing control updates. */
internal class WirelessControlDeadline(timeoutMillis: Long, private val nowNanos: () -> Long = System::nanoTime) {
    private var extendedForLiveSession = false
    private val deadlineNanos = if (timeoutMillis == Iap2WirelessControlClient.NO_TIMEOUT_MILLIS) {
        Long.MAX_VALUE
    } else {
        val now = nowNanos()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        if (now > Long.MAX_VALUE - delta) Long.MAX_VALUE else now + delta
    }

    fun remainingMillis(keepAlive: () -> Boolean = { false }): Long {
        if (extendedForLiveSession) return if (keepAlive()) MAX_POLL_MILLIS else 0L
        if (deadlineNanos == Long.MAX_VALUE) return MAX_POLL_MILLIS
        val remaining = deadlineNanos - nowNanos()
        if (remaining <= 0) {
            if (!keepAlive()) return 0L
            extendedForLiveSession = true
            return MAX_POLL_MILLIS
        }
        return min(MAX_POLL_MILLIS, (remaining + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
    }

    fun requireRemaining(keepAlive: () -> Boolean = { false }): Long = remainingMillis(keepAlive).also {
        if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wireless iAP2 control bring-up")
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_POLL_MILLIS = 5 * 60 * 1_000L
    }
}
