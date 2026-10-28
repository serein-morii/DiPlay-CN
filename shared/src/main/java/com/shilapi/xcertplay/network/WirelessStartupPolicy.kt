package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    const val FIRST_TCP_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION,

    /** The head unit's Android Bluetooth accepts connections that reach no phone. */
    BLUETOOTH_UNUSABLE,

    /** Android 13+ asked to turn the car hotspot off for Wi-Fi Direct, and the driver said no. */
    WIFI_DIRECT_BESIDE_HOTSPOT;

    /** False when no further attempt can change what went wrong. */
    val retryable: Boolean get() = this != HOTSPOT_CONFIGURATION && this != BLUETOOTH_UNUSABLE &&
        this != WIFI_DIRECT_BESIDE_HOTSPOT
}

class WirelessStartupException(
    val reason: WirelessStartupFailure,
    message: String,
    val knownCause: ConnectionFailureCause? = null,
) : IOException(message)
