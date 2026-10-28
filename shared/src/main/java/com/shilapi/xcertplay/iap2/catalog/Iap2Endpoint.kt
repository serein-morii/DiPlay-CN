package com.shilapi.xcertplay.iap2.catalog

enum class Iap2Direction {
    IPHONE_TO_ACCESSORY,
    ACCESSORY_TO_IPHONE,
    BIDIRECTIONAL,
}

enum class Iap2Confidence {
    HIGH,
    MEDIUM,
}

/** A wire-level body value kind from the endpoint reference. */
enum class Iap2WireType {
    VOID,
    U8,
    I8,
    U16,
    I16,
    U32,
    I32,
    U64,
    U16_LIST,
    BYTES,
    STRING,
    STRING_LIST,
    GROUP,
    OPAQUE,
}

/**
 * One endpoint field description.
 *
 * A group's [children] use the same parameter namespace as its parent. [repeatable] describes
 * repeated parameter IDs or repeated group records; the body builder and reader preserve both.
 */
data class Iap2FieldSpec(
    val id: Int,
    val name: String,
    val type: Iap2WireType,
    val required: Boolean = false,
    val repeatable: Boolean = false,
    val children: List<Iap2FieldSpec> = emptyList(),
)

/**
 * Metadata for one CSM endpoint.
 *
 * [fields] may be empty for endpoints whose reference body is intentionally opaque or has not yet
 * received a typed schema. The endpoint itself is still registered, named, and usable through the
 * generic body builder.
 */
data class Iap2Endpoint(
    val id: Int,
    val name: String,
    val feature: String,
    val direction: Iap2Direction,
    val confidence: Iap2Confidence = Iap2Confidence.HIGH,
    val fields: List<Iap2FieldSpec> = emptyList(),
)

/**
 * The endpoint registry from IAP2_ENDPOINT_BODY_REFERENCE.zh-CN.md.
 *
 * The registry is intentionally source-level rather than loaded from markdown at runtime. This
 * keeps Android builds deterministic and lets typed message code refer to named endpoints.
 */
object Iap2Endpoints {
    val START_IDENTIFICATION = endpoint(
        0x1d00,
        "StartIdentification",
        "Identification",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val IDENTIFICATION_INFORMATION = endpoint(
        0x1d01,
        "IdentificationInformation",
        "Identification",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(
            field(0, "name", Iap2WireType.STRING, required = true),
            field(1, "modelIdentifier", Iap2WireType.STRING, required = true),
            field(2, "manufacturer", Iap2WireType.STRING, required = true),
            field(3, "serialNumber", Iap2WireType.STRING, required = true),
            field(4, "firmwareVersion", Iap2WireType.STRING, required = true),
            field(5, "hardwareVersion", Iap2WireType.STRING, required = true),
            field(6, "MessagesSentByAccessory", Iap2WireType.U16_LIST, required = true),
            field(7, "MessagesReceivedFromDevice", Iap2WireType.U16_LIST, required = true),
            field(8, "powerProvidingCapability", Iap2WireType.U8),
            field(9, "maximumCurrentDrawnFromDevice", Iap2WireType.U16),
            field(10, "externalAccessoryProtocols", Iap2WireType.GROUP),
            field(12, "currentLanguage", Iap2WireType.STRING, required = true),
            field(13, "supportedLanguages", Iap2WireType.STRING_LIST, required = true),
            field(
                16,
                "USBHostTransport",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "componentID", Iap2WireType.U16),
                    field(1, "name", Iap2WireType.STRING),
                    field(2, "isSupported", Iap2WireType.VOID),
                    field(3, "interfaceNumber", Iap2WireType.U8),
                    field(4, "isAvailable", Iap2WireType.VOID),
                ),
            ),
            field(
                17,
                "BluetoothTransport",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "componentID", Iap2WireType.U16),
                    field(1, "name", Iap2WireType.STRING),
                    field(2, "isSupported", Iap2WireType.VOID),
                    field(3, "bluetoothMAC", Iap2WireType.BYTES),
                    field(4, "transportName", Iap2WireType.STRING),
                    field(5, "isAvailable", Iap2WireType.VOID),
                ),
            ),
            field(20, "VehicleInformation", Iap2WireType.GROUP),
            field(21, "VehicleStatus", Iap2WireType.GROUP),
            field(22, "LocationInformation", Iap2WireType.GROUP),
            field(
                24,
                "WirelessCarPlayTransport",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "componentID", Iap2WireType.U16),
                    field(1, "SSID", Iap2WireType.STRING),
                    field(2, "isSupported", Iap2WireType.VOID),
                    field(3, "transportIdentifier", Iap2WireType.U16),
                    field(4, "isAvailable", Iap2WireType.VOID),
                    field(5, "isEnabled", Iap2WireType.VOID),
                ),
            ),
            field(
                30,
                "RouteGuidanceDisplayComponent",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "Identifier", Iap2WireType.U16, required = true),
                    field(1, "Name", Iap2WireType.STRING, required = true),
                    field(2, "MaxCurrentRoadNameLength", Iap2WireType.U16),
                    field(4, "MaxAfterManeuverRoadNameLength", Iap2WireType.U16),
                    field(6, "MaxGuidanceManeuverStorageCapacity", Iap2WireType.U16),
                    // Declared last because the phone only sends 0x5204 lane guidance when the
                    // accessory says it can store it.
                    field(7, "MaxLaneGuidanceDescriptionLength", Iap2WireType.U16),
                    field(8, "MaxLaneGuidanceStorageCapacity", Iap2WireType.U16),
                ),
            ),
        ),
    )
    val IDENTIFICATION_ACCEPTED = endpoint(
        0x1d02,
        "IdentificationAccepted",
        "Identification",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val IDENTIFICATION_REJECTED = endpoint(
        0x1d03,
        "IdentificationRejected",
        "Identification",
        Iap2Direction.BIDIRECTIONAL,
    )
    val CANCEL_IDENTIFICATION = endpoint(
        0x1d05,
        "CancelIdentification",
        "Identification",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val IDENTIFICATION_INFORMATION_UPDATE = endpoint(
        0x1d06,
        "IdentificationInformationUpdate",
        "Identification",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val REQUEST_AUTHENTICATION_CERTIFICATE = endpoint(
        0xaa00,
        "RequestAuthenticationCertificate",
        "AccAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val AUTHENTICATION_CERTIFICATE = endpoint(
        0xaa01,
        "AuthenticationCertificate",
        "AccAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(field(0, "certificate", Iap2WireType.BYTES, required = true)),
    )
    val REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE = endpoint(
        0xaa02,
        "RequestAuthenticationChallengeResponse",
        "AccAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "challenge", Iap2WireType.BYTES, required = true)),
    )
    val AUTHENTICATION_RESPONSE = endpoint(
        0xaa03,
        "AuthenticationResponse",
        "AccAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(field(0, "signatureResponse", Iap2WireType.BYTES, required = true)),
    )
    val AUTHENTICATION_FAILED = endpoint(
        0xaa04,
        "AuthenticationFailed",
        "AccAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val AUTHENTICATION_SUCCEEDED = endpoint(
        0xaa05,
        "AuthenticationSucceeded",
        "AccAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val AUTHENTICATION_CERTIFICATE_SERIAL = endpoint(
        0xaa06,
        "AuthenticationCertificateSerial",
        "AccAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val REQUEST_DEVICE_CERTIFICATE = endpoint(
        0xaa10,
        "RequestDeviceCertificate",
        "DeviceAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val DEVICE_AUTHENTICATION_CERTIFICATE = endpoint(
        0xaa11,
        "AuthenticationCertificate",
        "DeviceAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val REQUEST_DEVICE_CHALLENGE_RESPONSE = endpoint(
        0xaa12,
        "RequestDeviceChallengeResponse",
        "DeviceAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val DEVICE_AUTHENTICATION_RESPONSE = endpoint(
        0xaa13,
        "AuthenticationResponse",
        "DeviceAuthentication",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val DEVICE_AUTHENTICATION_FAILED = endpoint(
        0xaa14,
        "DeviceAuthenticationFailed",
        "DeviceAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val DEVICE_AUTHENTICATION_SUCCEEDED = endpoint(
        0xaa15,
        "DeviceAuthenticationSucceeded",
        "DeviceAuthentication",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val CARPLAY_AVAILABILITY = endpoint(
        0x4300,
        "CarPlayAvailability",
        "CarPlayConnectionRequest",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(
            field(
                0,
                "wired",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "wiredAvailable", Iap2WireType.U8),
                    field(1, "usbIdentifier", Iap2WireType.STRING),
                ),
            ),
            field(
                1,
                "wireless",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "wirelessAvailable", Iap2WireType.U8),
                    field(1, "bluetoothIdentifier", Iap2WireType.STRING),
                ),
            ),
            field(
                2,
                "themeAssets",
                Iap2WireType.GROUP,
                children = listOf(field(0, "themeAssetsAvailable", Iap2WireType.U8)),
            ),
        ),
    )
    val CARPLAY_START_SESSION = endpoint(
        0x4301,
        "CarPlayStartSession",
        "CarPlayConnectionRequest",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(
            field(
                0,
                "wired",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "wiredIP", Iap2WireType.STRING, repeatable = true),
                    field(1, "reserved", Iap2WireType.U32),
                ),
            ),
            field(
                1,
                "wireless",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "SSID", Iap2WireType.STRING),
                    field(1, "passphrase", Iap2WireType.STRING),
                    field(2, "channel", Iap2WireType.U8),
                    field(3, "wirelessIP", Iap2WireType.STRING, repeatable = true),
                    field(4, "securityType", Iap2WireType.U8),
                ),
            ),
            field(2, "airPlayPort", Iap2WireType.U32),
            field(3, "deviceIdentifier", Iap2WireType.STRING),
            field(4, "publicKey", Iap2WireType.STRING),
            field(5, "sourceVersion", Iap2WireType.STRING),
            field(6, "SDKVersion", Iap2WireType.STRING),
            field(
                7,
                "clusterAsset",
                Iap2WireType.GROUP,
                children = listOf(
                    field(0, "assetID", Iap2WireType.STRING),
                    field(1, "assetVersion", Iap2WireType.U32),
                ),
            ),
            field(8, "mutualAuth", Iap2WireType.U8),
        ),
    )
    val AVAILABLE_DIGITAL_CAR_KEYS = endpoint(
        0x4302,
        "AvailableDigitalCarKeys",
        "DigitalCarKey_Matching",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val MATCHED_DIGITAL_CAR_KEYS = endpoint(
        0x4303,
        "MatchedDigitalCarKeys",
        "DigitalCarKey_Matching",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )

    val REQUEST_WIFI_INFORMATION = endpoint(
        0x5700,
        "RequestWiFiInformation",
        "WiFiSharing",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val WIFI_INFORMATION = endpoint(
        0x5701,
        "WiFiInformation",
        "WiFiSharing",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(
            field(0, "status", Iap2WireType.U8, required = true),
            field(1, "securityType", Iap2WireType.U8),
            field(2, "SSID", Iap2WireType.STRING),
            field(3, "passphrase", Iap2WireType.STRING),
        ),
    )
    val REQUEST_ACCESSORY_WIFI_CONFIGURATION = endpoint(
        0x5702,
        "RequestAccessoryWiFiConfigurationInformation",
        "WiFiSharing",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val ACCESSORY_WIFI_CONFIGURATION = endpoint(
        0x5703,
        "AccessoryWiFiConfigurationInformation",
        "WiFiSharing",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(
            field(0, "BSSID", Iap2WireType.BYTES),
            field(1, "SSID", Iap2WireType.STRING),
            field(2, "passphrase", Iap2WireType.STRING),
            field(3, "securityType", Iap2WireType.U8),
            field(4, "channel", Iap2WireType.U8),
        ),
    )

    val GPRMC_DATA_STATUS_VALUES = endpoint(
        0xfff0,
        "GPRMCDataStatusValues",
        "Location",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val START_LOCATION_INFORMATION = endpoint(
        0xfffa,
        "StartLocationInformation",
        "Location",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val LOCATION_INFORMATION = endpoint(
        0xfffb,
        "LocationInformation",
        "Location",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(field(0, "NMEA", Iap2WireType.STRING)),
    )
    val STOP_LOCATION_INFORMATION = endpoint(
        0xfffc,
        "StopLocationInformation",
        "Location",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )

    val START_POWER_UPDATES = endpoint(0xae00, "StartPowerUpdates", "Power", Iap2Direction.ACCESSORY_TO_IPHONE)
    val POWER_UPDATE = endpoint(0xae01, "PowerUpdate", "Power", Iap2Direction.IPHONE_TO_ACCESSORY)
    val STOP_POWER_UPDATES = endpoint(0xae02, "StopPowerUpdates", "Power", Iap2Direction.ACCESSORY_TO_IPHONE)
    val POWER_SOURCE_UPDATE = endpoint(
        0xae03,
        "PowerSourceUpdate",
        "Power",
        Iap2Direction.ACCESSORY_TO_IPHONE,
        fields = listOf(
            field(0, "AvailableCurrentForDevice", Iap2WireType.U16),
            field(1, "IsDeviceBatteryCharging", Iap2WireType.U8),
            field(3, "AvailableSiphoningCurrent", Iap2WireType.U16),
            field(4, "ReserveCurrent", Iap2WireType.U16),
            field(5, "MaxNonSiphoningCurrentExceeded", Iap2WireType.VOID),
        ),
    )
    val ACCESSORY_POWER_UPDATE = endpoint(0xae05, "AccessoryPowerUpdate", "Power", Iap2Direction.ACCESSORY_TO_IPHONE)
    val ACCESSORY_RESET_BASE_CURRENT =
        endpoint(0xae06, "AccessoryResetBaseCurrent", "Power", Iap2Direction.ACCESSORY_TO_IPHONE)

    val START_ROUTE_GUIDANCE_UPDATES = endpoint(
        0x5200,
        "StartRouteGuidanceUpdates",
        "Navigation",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val STOP_ROUTE_GUIDANCE_UPDATES = endpoint(
        0x5203,
        "StopRouteGuidanceUpdates",
        "Navigation",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val START_NOW_PLAYING_UPDATES = endpoint(
        0x5000,
        "StartNowPlayingUpdates",
        "Now Playing",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val STOP_NOW_PLAYING_UPDATES = endpoint(
        0x5002,
        "StopNowPlayingUpdates",
        "Now Playing",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val START_COMMUNICATIONS_UPDATES = endpoint(
        0x4157,
        "startCommunicationsUpdatesHandler",
        "Communications",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val START_CALL_STATE_UPDATES = endpoint(
        0x4154,
        "startCallStateUpdatesHandler",
        "Communications",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val STOP_COMMUNICATIONS_UPDATES = endpoint(
        0x4159,
        "stopCommunicationsUpdatesHandler",
        "Communications",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )
    val STOP_CALL_STATE_UPDATES = endpoint(
        0x4156,
        "stopCallStateUpdatesHandler",
        "Communications",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val DEVICE_INFORMATION_UPDATE = endpoint(
        0x4e09,
        "DeviceInformationUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "deviceName", Iap2WireType.STRING)),
    )
    val DEVICE_LANGUAGE_UPDATE = endpoint(
        0x4e0a,
        "DeviceLanguageUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "language", Iap2WireType.STRING)),
    )
    val DEVICE_TIME_UPDATE = endpoint(
        0x4e0b,
        "DeviceTimeUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(
            field(0, "secondsSinceReferenceDate", Iap2WireType.U64),
            field(1, "timeZoneOffsetMinutes", Iap2WireType.I16),
            field(2, "daylightSavingsOffsetMinutes", Iap2WireType.I8),
        ),
    )
    val DEVICE_UUID_UPDATE = endpoint(
        0x4e0c,
        "DeviceUuidUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "deviceUUID", Iap2WireType.STRING)),
    )
    val WIRELESS_CARPLAY_UPDATE = endpoint(
        0x4e0d,
        "WirelessCarPlayUpdate",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(field(0, "availability", Iap2WireType.U8, required = true)),
    )
    val DEVICE_TRANSPORT_IDENTIFIER = endpoint(
        0x4e0e,
        "DeviceTransportIdentifierNotification",
        "DeviceNotifications",
        Iap2Direction.IPHONE_TO_ACCESSORY,
        fields = listOf(
            field(0, "bluetoothMAC", Iap2WireType.STRING),
            field(1, "usbTransportIdentifier", Iap2WireType.STRING),
        ),
    )

    val EXTERNAL_ACCESSORY_OPEN_SESSION = endpoint(
        0xea00,
        "externalAccessory_openEASession",
        "ExternalAccessory",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val EXTERNAL_ACCESSORY_CLOSE_SESSION = endpoint(
        0xea01,
        "externalAccessory_closeEASession",
        "ExternalAccessory",
        Iap2Direction.IPHONE_TO_ACCESSORY,
    )
    val EXTERNAL_ACCESSORY_STATUS_SESSION = endpoint(
        0xea03,
        "externalaccessory_statusEASessionHandler",
        "ExternalAccessory",
        Iap2Direction.ACCESSORY_TO_IPHONE,
    )

    val PING = endpoint(0x0000, "pingHandler", "Test", Iap2Direction.ACCESSORY_TO_IPHONE)
    val USER_NOTIFICATION = endpoint(0x0001, "userNotificationHandler", "Test", Iap2Direction.ACCESSORY_TO_IPHONE)
    val SEND_PING_ACC = endpoint(0x0004, "sendPingAccHandler", "Test", Iap2Direction.ACCESSORY_TO_IPHONE)
    val PING_ACC = endpoint(0x0005, "pingAccHandler", "Test", Iap2Direction.ACCESSORY_TO_IPHONE)
    val DIGITAL_AUDIO_START = endpoint(0xda00, "digitalAudio_start", "DigitalAudio", Iap2Direction.ACCESSORY_TO_IPHONE)
    val DIGITAL_AUDIO_INFORMATION_UPDATE =
        endpoint(0xda01, "DigitalAudioInformationUpdate", "DigitalAudio", Iap2Direction.IPHONE_TO_ACCESSORY)
    val DIGITAL_AUDIO_STOP = endpoint(0xda02, "digitalAudio_stop", "DigitalAudio", Iap2Direction.ACCESSORY_TO_IPHONE)

    /**
     * The remaining reference endpoints are registered here without a claimed schema. Their body
     * can still be built and read through the generic body API, but no enum or field ID is invented.
     */
    val ALL: List<Iap2Endpoint> = listOf(
        START_IDENTIFICATION,
        IDENTIFICATION_INFORMATION,
        IDENTIFICATION_ACCEPTED,
        IDENTIFICATION_REJECTED,
        CANCEL_IDENTIFICATION,
        IDENTIFICATION_INFORMATION_UPDATE,
        REQUEST_AUTHENTICATION_CERTIFICATE,
        AUTHENTICATION_CERTIFICATE,
        REQUEST_AUTHENTICATION_CHALLENGE_RESPONSE,
        AUTHENTICATION_RESPONSE,
        AUTHENTICATION_FAILED,
        AUTHENTICATION_SUCCEEDED,
        AUTHENTICATION_CERTIFICATE_SERIAL,
        REQUEST_DEVICE_CERTIFICATE,
        DEVICE_AUTHENTICATION_CERTIFICATE,
        REQUEST_DEVICE_CHALLENGE_RESPONSE,
        DEVICE_AUTHENTICATION_RESPONSE,
        DEVICE_AUTHENTICATION_FAILED,
        DEVICE_AUTHENTICATION_SUCCEEDED,
        CARPLAY_AVAILABILITY,
        CARPLAY_START_SESSION,
        AVAILABLE_DIGITAL_CAR_KEYS,
        MATCHED_DIGITAL_CAR_KEYS,
        REQUEST_WIFI_INFORMATION,
        WIFI_INFORMATION,
        REQUEST_ACCESSORY_WIFI_CONFIGURATION,
        ACCESSORY_WIFI_CONFIGURATION,
        GPRMC_DATA_STATUS_VALUES,
        START_LOCATION_INFORMATION,
        LOCATION_INFORMATION,
        STOP_LOCATION_INFORMATION,
        START_POWER_UPDATES,
        POWER_UPDATE,
        STOP_POWER_UPDATES,
        POWER_SOURCE_UPDATE,
        ACCESSORY_POWER_UPDATE,
        ACCESSORY_RESET_BASE_CURRENT,
        START_ROUTE_GUIDANCE_UPDATES,
        STOP_ROUTE_GUIDANCE_UPDATES,
        START_NOW_PLAYING_UPDATES,
        STOP_NOW_PLAYING_UPDATES,
        START_COMMUNICATIONS_UPDATES,
        START_CALL_STATE_UPDATES,
        STOP_COMMUNICATIONS_UPDATES,
        STOP_CALL_STATE_UPDATES,
        DEVICE_INFORMATION_UPDATE,
        DEVICE_LANGUAGE_UPDATE,
        DEVICE_TIME_UPDATE,
        DEVICE_UUID_UPDATE,
        WIRELESS_CARPLAY_UPDATE,
        DEVICE_TRANSPORT_IDENTIFIER,
        EXTERNAL_ACCESSORY_OPEN_SESSION,
        EXTERNAL_ACCESSORY_CLOSE_SESSION,
        EXTERNAL_ACCESSORY_STATUS_SESSION,
        PING,
        USER_NOTIFICATION,
        SEND_PING_ACC,
        PING_ACC,
        DIGITAL_AUDIO_START,
        DIGITAL_AUDIO_INFORMATION_UPDATE,
        DIGITAL_AUDIO_STOP,
        registryEndpoint(0xA100, "StartVehicleStatusUpdates", "Vehicle", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0xA101, "VehicleStatusUpdate", "Vehicle", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0xA102, "StopVehicleStatusUpdates", "Vehicle", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x5001, "NowPlayingUpdate", "Now Playing", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x5003, "SetNowPlayingInfo", "Now Playing", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C00, "StartMediaLibraryInformation", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C01, "MediaLibraryInformation", "Media Library", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4C02, "StopMediaLibraryInformation", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C03, "StartMediaLibraryUpdates", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C04, "MediaLibraryUpdate", "Media Library", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4C05, "StopMediaLibraryUpdates", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C06, "PlayMediaLibraryCurrentSelection", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C07, "PlayMediaLibraryItems", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C08, "PlayMediaLibraryCollection", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4C09, "PlayMediaLibrarySpecial", "Media Library", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x5201, "RouteGuidanceUpdate", "Navigation", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x5202, "RouteGuidanceManeuverUpdate", "Navigation", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x5204, "LaneGuidanceInfoUpdate", "Navigation", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4155, "CallStateUpdate", "Communications", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4158, "CommunicationsUpdate", "Communications", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x415A, "initiateCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415B, "acceptCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415C, "endCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415D, "swapCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415E, "mergeCallHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x415F, "holdStatusUpdateHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4160, "muteStatusUpdateHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4161, "sendDTMFHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4170, "startListUpdatesHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x4171, "ListUpdate", "Communications", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x4172, "stopListUpdatesHandler", "Communications", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(
            0x4E01,
            "BluetoothComponentInfo",
            "Bluetooth connection status",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x4E03,
            "StartBluetoothConnectionUpdates",
            "Bluetooth connection status",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x4E04,
            "BluetoothConnectionUpdate",
            "Bluetooth connection status",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x4E05,
            "StopBluetoothConnectionUpdates",
            "Bluetooth connection status",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0xAD00, "appLinks_start", "App links and launch", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0xAD01, "AppLinksUpdate", "App links and launch", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0xAD02, "appLinks_stop", "App links and launch", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0xAD03, "appLinks_requestAppIcons", "App links and launch", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0xAD04, "AppIconResponse", "App links and launch", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0xEA02, "requestAppLaunchHandler", "External Accessory", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x6800, "hid_StartHIDMsgHandler", "HID and USB host", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x6801, "hid_OutReport", "HID and USB host", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x6802, "hid_AccessoryHIDReportHandler", "HID and USB host", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x6803, "hid_StopHIDMsgHandler", "HID and USB host", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x6804, "hid_GetReport", "HID and USB host", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x6805,
            "hid_AccessoryHIDGetReportResponseHandler",
            "HID and USB host",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x6807, "hid_ComponentUpdate", "HID and USB host", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x7E00,
            "usbHostMode_StartUSBHostModeHandler",
            "HID and USB host",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x7E02,
            "usbHostMode_StopUSBHostModeHandler",
            "HID and USB host",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5400,
            "assistiveTouch_startAssistiveTouchHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5401,
            "assistiveTouch_stopAssistiveTouchHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5402,
            "assistiveTouch_startAssistiveTouchUpdateHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x5403, "NotifyStatusUpdate", "VoiceOver and AssistiveTouch", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x5404,
            "assistiveTouch_stopAssistiveTouchUpdateHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5601,
            "voiceOver_moveCursorHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5602,
            "voiceOver_selectCursorHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5603,
            "voiceOver_scrollPageHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5606,
            "voiceOver_speakTextHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5608,
            "voiceOver_pauseSpeakingHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5609,
            "voiceOver_resumeSpeakingHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x560B,
            "voiceOver_startInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x560C,
            "VoiceOverInformation",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x560D,
            "voiceOver_stopInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x560E,
            "voiceOver_updateInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x560F,
            "voiceOver_startCursorInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5610,
            "VoiceOverCursorInformation",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x5611,
            "voiceOver_stopCursorInformationHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5612,
            "VoiceOver_startVoiceOverHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x5613,
            "VoiceOver_stopVoiceOverHandler",
            "VoiceOver and AssistiveTouch",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x0B00, "oobBtPairing2_startPairing", "BLE and OOB pairing", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x0B01,
            "oobBtPairing2_accessoryInfoHandler",
            "BLE and OOB pairing",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x0B02, "oobBtPairing2_statusHandler", "BLE and OOB pairing", Iap2Direction.ACCESSORY_TO_IPHONE),
        registryEndpoint(0x0B03, "oobBtPairing2_stopPairing", "BLE and OOB pairing", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x10DA,
            "destinationSharing_StartDestinationInformation",
            "Destination sharing",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x10DB,
            "DestinationInformationUpdate",
            "Destination sharing",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x10DC,
            "destinationSharing_StopDestinationInformation",
            "Destination sharing",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x10DD,
            "destinationSharing_DestinationInformationStatus",
            "Destination sharing",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x00B0, "oobBtPairing_startPairing", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0x00B1,
            "oobBtPairing_accessoryInfoHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0x00B2,
            "oobBtPairing_pairingLinkKeyInfo",
            "Other endpoints",
            Iap2Direction.IPHONE_TO_ACCESSORY,
        ),
        registryEndpoint(
            0x00B3,
            "oobBtPairing_completionInfoHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0x00B4, "oobBtPairing_stopPairing", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x0D00, "StartRoadObjectDetection", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x0D01, "RoadObjectDetectionUpdate", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(0x0D02, "StopRoadObjectDetection", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
        registryEndpoint(
            0xB102,
            "blePairing_accessoryStateUpdateHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0xB103,
            "blePairing_accessoryPairingInfoHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(
            0xB105,
            "blePairing_accessoryPairingDataHandler",
            "Other endpoints",
            Iap2Direction.ACCESSORY_TO_IPHONE,
        ),
        registryEndpoint(0xB107, "blePairing_stopBLEUpdates", "Other endpoints", Iap2Direction.IPHONE_TO_ACCESSORY),
    )

    private val byId: Map<Int, Iap2Endpoint> = ALL.associateBy(Iap2Endpoint::id)

    fun byId(id: Int): Iap2Endpoint? = byId[id]

    fun require(id: Int): Iap2Endpoint =
        byId(id) ?: throw IllegalArgumentException("Unknown iAP2 endpoint 0x${id.toString(16).padStart(4, '0')}")

    private fun endpoint(
        id: Int,
        name: String,
        feature: String,
        direction: Iap2Direction,
        confidence: Iap2Confidence = Iap2Confidence.HIGH,
        fields: List<Iap2FieldSpec> = emptyList(),
    ): Iap2Endpoint = Iap2Endpoint(id, name, feature, direction, confidence, fields)

    private fun registryEndpoint(
        id: Int,
        name: String,
        feature: String,
        direction: Iap2Direction,
    ): Iap2Endpoint = Iap2Endpoint(id, name, feature, direction, fields = emptyList())

    private fun field(
        id: Int,
        name: String,
        type: Iap2WireType,
        required: Boolean = false,
        repeatable: Boolean = false,
        children: List<Iap2FieldSpec> = emptyList(),
    ): Iap2FieldSpec = Iap2FieldSpec(id, name, type, required, repeatable, children)
}
