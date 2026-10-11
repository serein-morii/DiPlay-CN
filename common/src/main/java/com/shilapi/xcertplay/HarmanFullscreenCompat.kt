package com.shilapi.xcertplay

import android.view.View
import android.view.Window

/** Opt-in legacy system-UI mode observed to reclaim the OEM side panel on Harman firmware. */
@Suppress("DEPRECATION")
internal object HarmanFullscreenCompat {
    internal const val OEM_FULLSCREEN_BIT = 0x8
    internal const val SYSTEM_UI_FLAGS =
        View.SYSTEM_UI_FLAG_IMMERSIVE or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            OEM_FULLSCREEN_BIT

    fun apply(window: Window) {
        window.decorView.systemUiVisibility = SYSTEM_UI_FLAGS
    }

    fun clear(window: Window) {
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility and OEM_FULLSCREEN_BIT.inv()
    }
}
