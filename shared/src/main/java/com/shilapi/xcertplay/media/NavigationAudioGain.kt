package com.shilapi.xcertplay.media

import kotlin.math.pow
import kotlin.math.roundToInt

/** Per-track navigation attenuation; never changes the head unit's system stream volume. */
object NavigationAudioGain {
    const val MIN_DB = -20
    const val MAX_DB = 0

    fun clamp(db: Int): Int = db.coerceIn(MIN_DB, MAX_DB)

    /** Amplitude scale (not a percent); -20 dB = 0.1 and 0 dB = 1.0. */
    fun linear(db: Int): Float = 10.0.pow(clamp(db) / 20.0).toFloat()

    /** Scales 16-bit little-endian PCM in place by [db]; 0 dB leaves it untouched. */
    fun scalePcm16(data: ByteArray, offset: Int, length: Int, db: Int) {
        val gain = linear(db)
        if (gain >= 1f) return
        val end = offset + (length and 1.inv())
        var i = offset
        while (i < end) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)
            val scaled = (sample * gain).roundToInt()
            data[i] = scaled.toByte()
            data[i + 1] = (scaled shr 8).toByte()
            i += 2
        }
    }
}
