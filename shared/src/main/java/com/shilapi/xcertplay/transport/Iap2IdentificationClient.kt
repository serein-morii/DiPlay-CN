package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.transport.Iap2VehicleStatus.electricVehicleComponents
import java.io.IOException
import kotlin.math.min

/** The wireless transport identity advertised on the Bluetooth identification session. */
class Iap2WirelessIdentification(
    val bluetoothMac: String,
    val ssid: String,
) {
    private val macBytes: ByteArray

    init {
        require(MAC_ADDRESS.matches(bluetoothMac)) {
            "bluetoothMac must be six colon-separated hexadecimal bytes"
        }
        require(ssid.isNotBlank()) { "Wireless SSID must not be blank" }
        require('\u0000' !in ssid) { "Wireless SSID must not contain U+0000" }
        macBytes = ByteArray(6) { index ->
            bluetoothMac.substring(index * 3, index * 3 + 2).toInt(16).toByte()
        }
    }

    internal fun bluetoothMacBytes(): ByteArray = macBytes.copyOf()

    private companion object {
        private val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    }
}

/** Required identity for the minimal wired or wireless iAP2 identification exchange. */
data class Iap2IdentificationConfig(
    val name: String,
    val modelIdentifier: String,
    val manufacturer: String,
    val serialNumber: String,
    val firmwareVersion: String,
    val hardwareVersion: String,
    /** The iPhone USB interface used by CarPlay, supplied explicitly by the deployment. */
    val carPlayUsbInterfaceNumber: Int,
    val language: String = "en",
    /** This app's own EA protocol identifier; it deliberately does not claim a CarPlay EA flag. */
    val externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    /** Non-null selects the wireless Bluetooth and WirelessCarPlay transport components. */
    val wireless: Iap2WirelessIdentification? = null,
    /** Advertises and enables iAP2 LocationInformation from the accessory to the phone. */
    val locationInformationEnabled: Boolean = false,
    /** Declares an electric vehicle and answers the phone's vehicle-status subscription. */
    val vehicleStatusEnabled: Boolean = false,
    /** The charging inlets declared with [vehicleStatusEnabled]. */
    val chargingConnectors: EvChargingConnectors = EvChargingConnectors.CCS2_TYPE2,
    /** Also offer wheel speed ($PASCD) in the location component; needs [locationInformationEnabled]. */
    val vehicleSpeedEnabled: Boolean = false,
) {
    constructor(
        name: String,
        modelIdentifier: String,
        manufacturer: String,
        serialNumber: String,
        firmwareVersion: String,
        hardwareVersion: String,
        wireless: Iap2WirelessIdentification,
        language: String = "en",
        externalAccessoryProtocol: String = "com.shilapi.xcertplay",
    ) : this(
        name = name,
        modelIdentifier = modelIdentifier,
        manufacturer = manufacturer,
        serialNumber = serialNumber,
        firmwareVersion = firmwareVersion,
        hardwareVersion = hardwareVersion,
        carPlayUsbInterfaceNumber = 0,
        language = language,
        externalAccessoryProtocol = externalAccessoryProtocol,
        wireless = wireless,
    )

    init {
        listOf(name, modelIdentifier, manufacturer, serialNumber, firmwareVersion, hardwareVersion).forEach {
            require(it.isNotBlank()) { "Identification identity strings must not be blank" }
        }
        require(language.isNotBlank()) { "Identification language must not be blank" }
        require(externalAccessoryProtocol.isNotBlank()) { "External accessory protocol must not be blank" }
        listOf(
            name,
            modelIdentifier,
            manufacturer,
            serialNumber,
            firmwareVersion,
            hardwareVersion,
            language,
            externalAccessoryProtocol,
        ).forEach {
            require('\u0000' !in it) { "NUL-terminated identification strings must not contain U+0000" }
        }
        require(carPlayUsbInterfaceNumber in 0..0xff) {
            "carPlayUsbInterfaceNumber must be in 0..255"
        }
    }
}

/** The two iAP2 links used during one wireless CarPlay connection have different responsibilities. */
internal enum class Iap2WirelessLinkRole {
    /** Short-lived RFCOMM link used only to authenticate and hand the iPhone the Wi-Fi endpoint. */
    BLUETOOTH_BOOTSTRAP,

    /** Long-lived iAP2 DataStream inside the active Wi-Fi AirPlay session. */
    RUNTIME_TUNNEL,
}

/**
 * Builds identification for one wireless link. Long-lived accessory data must not be advertised on
 * Bluetooth: iOS can bind Location or Vehicle to that endpoint and reject the same data on Wi-Fi
 * after RFCOMM closes.
 */
internal fun Iap2IdentificationConfig.forWirelessLink(
    role: Iap2WirelessLinkRole,
    wirelessIdentification: Iap2WirelessIdentification,
): Iap2IdentificationConfig {
    val wirelessConfig = copy(wireless = wirelessIdentification)
    return when (role) {
        Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP -> wirelessConfig.copy(
            locationInformationEnabled = false,
            vehicleStatusEnabled = false,
            vehicleSpeedEnabled = false,
        )
        Iap2WirelessLinkRole.RUNTIME_TUNNEL -> wirelessConfig
    }
}

/** Identification failures distinguished from the underlying iAP2 transport failure. */
sealed class Iap2IdentificationException(message: String) : IOException(message) {
    class Rejected(
        parameterIds: Set<Int>,
        val unsupportedMessagesSent: Iap2RejectedMessageList? = null,
        val unsupportedMessagesReceived: Iap2RejectedMessageList? = null,
    ) : Iap2IdentificationException(
        "iAP2 identification rejected before MFi authentication; parameters ${parameterIds.sorted().hexadecimalIds()}; " +
            "unsupported messages sent=${unsupportedMessagesSent?.diagnostic() ?: "absent"} " +
            "received=${unsupportedMessagesReceived?.diagnostic() ?: "absent"}",
    ) {
        val parameterIds: Set<Int> = parameterIds.toSet()
    }

    class UnexpectedMessage(messageId: Int) : Iap2IdentificationException(
        "Unexpected iAP2 identification message 0x${messageId.toString(16).padStart(4, '0')}",
    )
}

/** Bounded diagnostic values from IdentificationRejected parameter 6 or 7, not negotiated features. */
data class Iap2RejectedMessageList internal constructor(
    val messageIds: List<Int>,
    val payloadCount: Int,
    val emptyPayloadCount: Int,
    val malformedPayloadCount: Int,
    val omittedMessageCount: Int,
) {
    internal fun diagnostic(): String =
        "${messageIds.hexadecimalIds()} " +
            "payloads=$payloadCount empty=$emptyPayloadCount malformed=$malformedPayloadCount " +
            "duplicateParameters=${payloadCount > 1} " +
            "duplicateRetainedIds=${messageIds.size != messageIds.toSet().size} " +
            "omitted=$omittedMessageCount"
}

private fun Iterable<Int>.hexadecimalIds(): String =
    joinToString(prefix = "[", postfix = "]") { "0x${it.toString(16).padStart(4, '0')}" }

/**
 * Synchronous accessory-side wired or wireless identification over an already owned CSM channel.
 *
 * This is intentionally only identification: it neither invokes MFi nor itself starts any
 * CarPlay, subscription, power, media, or UI service.
 */
class Iap2IdentificationClient(private val session: Iap2Session) {
    /** Waits for link negotiation, then completes the 1D00/1D01/1D02 exchange. */
    @Throws(IphoneUsbException::class, Iap2IdentificationException::class)
    fun identify(config: Iap2IdentificationConfig, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS) {
        require(timeoutMillis in 1..MAXIMUM_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAXIMUM_TIMEOUT_MILLIS"
        }
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        if (!session.awaitReady(remainingMillis(deadlineNanos))) {
            throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 control session readiness")
        }

        while (true) {
            val frame = session.recv(remainingMillis(deadlineNanos))
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for iAP2 identification")
            when (frame.messageId) {
                START_IDENTIFICATION -> session.send(identificationInformation(config), remainingMillis(deadlineNanos))
                IDENTIFICATION_ACCEPTED -> return
                IDENTIFICATION_REJECTED -> {
                    throw identificationRejection(frame)
                }
                else -> throw Iap2IdentificationException.UnexpectedMessage(frame.messageId)
            }
        }
    }

    companion object {
        const val START_IDENTIFICATION = 0x1d00
        const val IDENTIFICATION_INFORMATION = 0x1d01
        const val IDENTIFICATION_ACCEPTED = 0x1d02
        const val IDENTIFICATION_REJECTED = 0x1d03

        private const val DEFAULT_TIMEOUT_MILLIS = 10_000L
        private const val MAXIMUM_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MAX_REJECTED_MESSAGE_IDS = 32

        internal fun identificationRejection(frame: Iap2Frame): Iap2IdentificationException.Rejected {
            val parameters = Iap2BodyReader.of(frame).list()
            return Iap2IdentificationException.Rejected(
                parameterIds = parameters.mapTo(LinkedHashSet()) { it.id },
                unsupportedMessagesSent = rejectedMessageList(parameters, 6),
                unsupportedMessagesReceived = rejectedMessageList(parameters, 7),
            )
        }

        private fun rejectedMessageList(parameters: List<Iap2Parameter>, id: Int): Iap2RejectedMessageList? {
            val payloads = parameters.filter { it.id == id }
            if (payloads.isEmpty()) return null
            val messageIds = ArrayList<Int>(MAX_REJECTED_MESSAGE_IDS)
            var emptyPayloadCount = 0
            var malformedPayloadCount = 0
            var omittedMessageCount = 0
            for (parameter in payloads) {
                val payload = parameter.payload
                when {
                    payload.isEmpty() -> emptyPayloadCount++
                    payload.size % 2 != 0 -> malformedPayloadCount++
                    else -> {
                        // These two rejection parameters contain BE16 message IDs, unlike the
                        // empty presence markers used by other rejected identification fields.
                        // Keep repetitions visible and do not guess IDs from an odd-length payload.
                        for (offset in payload.indices step 2) {
                            if (messageIds.size < MAX_REJECTED_MESSAGE_IDS) {
                                messageIds += ((payload[offset].toInt() and 0xff) shl 8) or
                                    (payload[offset + 1].toInt() and 0xff)
                            } else {
                                omittedMessageCount++
                            }
                        }
                    }
                }
            }
            return Iap2RejectedMessageList(
                messageIds = messageIds.toList(),
                payloadCount = payloads.size,
                emptyPayloadCount = emptyPayloadCount,
                malformedPayloadCount = malformedPayloadCount,
                omittedMessageCount = omittedMessageCount,
            )
        }

        /** Builds the smallest honest LIVI-compatible wired or wireless IdentificationInformation. */
        fun identificationInformation(config: Iap2IdentificationConfig): Iap2Frame {
            val wireless = config.wireless
            var sentMessages = if (config.locationInformationEnabled) {
                MESSAGES_SENT_BY_ACCESSORY + LOCATION_INFORMATION
            } else {
                MESSAGES_SENT_BY_ACCESSORY
            }
            var receivedMessages = if (config.locationInformationEnabled) {
                MESSAGES_RECEIVED_FROM_PHONE + START_LOCATION_INFORMATION + STOP_LOCATION_INFORMATION
            } else {
                MESSAGES_RECEIVED_FROM_PHONE
            }
            if (config.vehicleStatusEnabled) {
                sentMessages += Iap2VehicleStatus.VEHICLE_STATUS_UPDATE
                receivedMessages += intArrayOf(
                    Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES,
                    Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES,
                )
            }
            return Iap2Messages.build(Iap2Endpoints.IDENTIFICATION_INFORMATION) {
                string(0, config.name)
                string(1, config.modelIdentifier)
                string(2, config.manufacturer)
                string(3, config.serialNumber)
                string(4, config.firmwareVersion)
                string(5, config.hardwareVersion)
                u16List(
                    6,
                    if (wireless == null) {
                        sentMessages.asIterable()
                    } else {
                        sentMessages.filterNot { it == POWER_SOURCE_UPDATE }.toIntArray().asIterable() +
                            ACCESSORY_WIFI_CONFIGURATION_INFORMATION
                    },
                )
                u16List(
                    7,
                    if (wireless == null) {
                        receivedMessages.asIterable()
                    } else {
                        receivedMessages.asIterable() + WIRELESS_PHONE_MESSAGES.asIterable()
                    },
                )
                u8(8, if (wireless == null) 2 else 0)
                u16(9, 20)
                group(10) {
                    u8(0, 1)
                    string(1, config.externalAccessoryProtocol)
                    u8(2, 0)
                }
                string(12, config.language)
                strings(13, listOf(config.language))
                if (wireless == null) {
                    group(16) {
                        u16(0, 0)
                        string(1, "USBHostTransport")
                        void(2)
                        u8(3, config.carPlayUsbInterfaceNumber)
                        void(4)
                    }
                } else {
                    group(17) {
                        u16(0, 0)
                        string(1, "blue")
                        void(2)
                        bytes(3, wireless.bluetoothMacBytes())
                        string(4, "blue")
                        void(5)
                    }
                    group(24) {
                        u16(0, 1)
                        string(1, wireless.ssid)
                        void(2)
                        u16(3, 1)
                        void(4)
                        void(5)
                    }
                }
                if (config.vehicleStatusEnabled) electricVehicleComponents(config.manufacturer, config.chargingConnectors)
                if (config.locationInformationEnabled) {
                    group(22) {
                        u16(0, 0)
                        string(1, config.name)
                        void(17)
                        void(18)
                        if (config.vehicleSpeedEnabled) void(20)
                    }
                }
                group(30) {
                    u16(0, 42)
                    string(1, "RouteGuidance")
                    // Without these limits the iPhone may omit road names from 0x5201/0x5202.
                    u16(2, 64) // MaxCurrentRoadNameLength
                    u16(4, 64) // MaxAfterManeuverRoadNameLength
                    u16(6, 8)
                }
            }
        }

        private fun remainingMillis(deadlineNanos: Long): Long {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0) throw IphoneUsbException.TimedOut("iAP2 identification timed out")
            return min(MAXIMUM_TIMEOUT_MILLIS, (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
        }

        /* Include the authentication messages implemented by Iap2MfiAuthenticationClient. */
        private val MESSAGES_SENT_BY_ACCESSORY = intArrayOf(
            0xaa01, // AuthenticationCertificate
            0xaa03, // AuthenticationResponse
            0x5000, // StartNowPlayingUpdates
            0x5002, // StopNowPlayingUpdates
            0x5200, // StartRouteGuidanceUpdates
            0x5203, // StopRouteGuidanceUpdates
            0xae00, // StartPowerUpdates
            0xae02, // StopPowerUpdates
            0x4157, // StartCommunicationsUpdates
            0x4159, // StopCommunicationsUpdates
            0x4154, // StartCallStateUpdates
            0x4156, // StopCallStateUpdates
            0xae03, // PowerSourceUpdate
            0x4301, // CarPlayStartSession
        )
        private val MESSAGES_RECEIVED_FROM_PHONE = intArrayOf(
            0xaa00, // RequestAuthenticationCertificate
            0xaa02, // RequestAuthenticationChallengeResponse
            0xaa04, // AuthenticationFailed
            0xaa05, // AuthenticationSucceeded
            0xea00, // StartExternalAccessoryProtocolSession
            0xea01, // StopExternalAccessoryProtocolSession
            0x5001, // NowPlayingUpdate
            0x5201, // RouteGuidanceUpdate
            0x5202, // RouteGuidanceManeuverUpdate
            0x5204, // LaneGuidanceInfoUpdate
            0xae01, // PowerUpdate
            0x4158, // CommunicationsUpdate
            0x4155, // CallStateUpdate
            0x4300, // CarPlayAvailability
        )
        private const val POWER_SOURCE_UPDATE = 0xae03
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val ACCESSORY_WIFI_CONFIGURATION_INFORMATION = 0x5703
        private const val LOCATION_INFORMATION = 0xfffb
        private const val START_LOCATION_INFORMATION = 0xfffa
        private const val STOP_LOCATION_INFORMATION = 0xfffc
        private val WIRELESS_PHONE_MESSAGES = intArrayOf(0x4e0d, 0x4e0e, 0x5702)
    }
}
