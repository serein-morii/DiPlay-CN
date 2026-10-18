package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.io.IOException

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

    /**
     * Why this attempt cannot reach an iPhone, taking state into account.
     */
    fun blockerForState(adapterPresent: Boolean, state: Int?, pausedByDiPlay: Boolean): String? = when {
        !adapterPresent || state == null -> NO_ADAPTER
        (state == android.bluetooth.BluetoothAdapter.STATE_OFF ||
            state == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF) && !pausedByDiPlay -> RADIO_OFF
        else -> null
    }

    /**
     * What [blocker]'s answer is thrown as. A radio that is off can be switched on, so that attempt is
     * retried; no retry gives a head unit an adapter, so that one stops with its cause.
     */
    fun failure(blocker: String): IOException =
        if (blocker == NO_ADAPTER) WirelessStartupException(WirelessStartupFailure.BLUETOOTH_UNUSABLE, blocker)
        else IOException(blocker)

    /** Ends in the words the host screen matches on for Bluetooth that apps cannot use. */
    const val NO_ADAPTER = "Bluetooth adapter is unavailable: Android on this head unit ${BluetoothLinkCheck.NO_SOCKETS_MARK}"

    /** The words that ask the driver to act; the host screen matches on them. */
    const val RADIO_OFF_MARK = "Turn on Bluetooth"
    const val RADIO_OFF = "$RADIO_OFF_MARK in the head unit's settings, then reconnect"
}
