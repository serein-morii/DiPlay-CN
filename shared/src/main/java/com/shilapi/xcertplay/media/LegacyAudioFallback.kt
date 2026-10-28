package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log

/** A ROM can reject a legacy stream by throwing or returning an uninitialized track. */
internal object LegacyAudioFallback {
    fun <T> build(
        createLegacy: () -> T,
        isInitialized: (T) -> Boolean,
        release: (T) -> Unit,
        createFallback: () -> T,
    ): T {
        val candidate = try { createLegacy() } catch (_: RuntimeException) { null }
        if (candidate != null) {
            if (isInitialized(candidate)) return candidate
            release(candidate)
        }
        return createFallback()
    }

    /**
     * The attributes of a chosen legacy stream, or null to keep usage routing. Android accepts only its
     * defined legacy stream IDs here. BYD audio policy can map these standard streams to vehicle outputs;
     * arbitrary channel numbers are not valid AudioAttributes legacy stream types.
     */
    fun attributes(streamType: Int): AudioAttributes? {
        if (streamType !in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) return null
        return try {
            AudioAttributes.Builder().setLegacyStreamType(streamType).build()
        } catch (error: Exception) {
            Log.w(TAG, "legacy audio stream $streamType rejected; keeping usage routing", error)
            null
        }
    }

    private const val TAG = "xcertplay-usb"
}
