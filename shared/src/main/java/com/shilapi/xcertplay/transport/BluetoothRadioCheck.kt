package com.shilapi.xcertplay.transport

/**
 * What wireless startup can know about the head unit's Bluetooth before it changes the Wi-Fi.
 *
 * Wireless CarPlay begins over Bluetooth. With the radio off, bringing up the car hotspot or a
 * Wi-Fi Direct group first is work for an attempt that cannot succeed. On a head unit with one
 * radio it can also take the unit off the Wi-Fi it was using, and every retry does it again.
 */
object BluetoothRadioCheck {
    /**
     * Why this attempt cannot reach an iPhone, or null when it can go on. A radio that DiPlay
     * paused itself is turned back on before the handshake, so it does not stop the attempt here.
     */
    fun blocker(adapterPresent: Boolean, enabled: Boolean, pausedByDiPlay: Boolean): String? = when {
        !adapterPresent -> NO_ADAPTER
        !enabled && !pausedByDiPlay -> RADIO_OFF
        else -> null
    }

    const val NO_ADAPTER = "Bluetooth adapter is unavailable"

    /** The words that ask the driver to act; the host screen matches on them. */
    const val RADIO_OFF_MARK = "Turn on Bluetooth"
    const val RADIO_OFF = "$RADIO_OFF_MARK in the head unit's settings, then reconnect"
}
