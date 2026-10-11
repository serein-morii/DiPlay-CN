package com.shilapi.xcertplay.network

import android.os.Build

/**
 * Identifies the Geely / Lynk & Co head unit (`geely_fx11`, model `CS11_A4`, DesaySV, Android 11)
 * that the wheel-key support was written and tested on.
 *
 * Confirmed on the car: it maps the steering-wheel keys to vendor-private key codes and routes them
 * only through its own input service, so no standard Android `KeyEvent` reaches a media session or
 * an accessibility service. [com.shilapi.xcertplay.GeelyWheelKeys] subscribes to that service, and
 * [com.shilapi.xcertplay.media.MicrophoneUplink] uses the voice-communication audio source for Siri,
 * which is the only source this firmware opens the microphone for.
 *
 * Every use is gated by [detected], so other head units keep the stock behaviour, and unit tests
 * (where `Build` reports Robolectric) never take this path.
 */
object GeelyHeadUnit {

    private val identifiers: List<String>
        get() = runCatching {
            listOf(Build.PRODUCT, Build.DEVICE, Build.MODEL, Build.MANUFACTURER, Build.BRAND)
                .map { it.lowercase() }
        }.getOrDefault(emptyList())

    /** Product code of the head unit. */
    private const val PRODUCT = "geely_fx11"

    /** Model name the head unit reports. */
    private const val MODEL = "cs11_a4"

    val detected: Boolean = identifiers.any { id ->
        id == PRODUCT || id == MODEL || id.contains("geely") || id.contains("lynk")
    }
}
