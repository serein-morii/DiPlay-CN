package com.shilapi.xcertplay.orchestration

import java.io.IOException

enum class ConnectionFailureCause {
    CHOSEN_IPHONE_NOT_PAIRED, NO_PAIRED_IPHONE, IPHONE_NOT_CHOSEN, WIFI_DIRECT_CHANNEL_UNAVAILABLE,
    CAR_HOTSPOT_OFF, CAR_HOTSPOT_DETAILS_DIFFER, HOTSPOT_INTERFACE_NOT_FOUND, CAR_HOTSPOT_START_TIMED_OUT,
    CAR_HOTSPOT_START_FAILED,
}

/** Unlike a [com.shilapi.xcertplay.network.WirelessStartupException], it changes what the screen says, not the retries. */
open class ConnectionFailureException(val reason: ConnectionFailureCause, message: String, cause: Throwable? = null) :
    IOException(message, cause)
