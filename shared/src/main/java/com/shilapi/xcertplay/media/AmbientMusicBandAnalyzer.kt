package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10

/**
 * Sixteen band magnitudes of the PCM being played, in the form the car's lamp controller takes for its music
 * rhythm: values from 1 to 15 at the frequencies BYD's own audio service samples. Goertzel filters run over the last
 * [WINDOW_FRAMES] mono frames, so the low bands see more than one cycle. The result is relative: the loudest recent
 * band sits near 15 and the rest follow in dB below it, with silence at 1.
 */
internal class AmbientMusicBandAnalyzer(private val sampleRate: Int, private val channels: Int) {
    private val ring = DoubleArray(WINDOW_FRAMES)
    private var filled = 0
    private var next = 0
    private var framesSinceAnalysis = 0
    private var peakDb = SILENCE_DB
    private var last = IntArray(BAND_COUNT) { 1 }
    private val coefficients = DoubleArray(BAND_COUNT) { index ->
        2.0 * cos(2.0 * PI * FREQUENCIES[index] / sampleRate.coerceAtLeast(1))
    }

    /** Adds 16-bit little-endian PCM and returns the bands, reused when fewer than [ANALYSIS_FRAMES] frames are new. */
    fun analyze(data: ByteArray, offset: Int, length: Int): IntArray {
        val count = channels.coerceAtLeast(1)
        val frameBytes = count * 2
        if (offset < 0 || length < frameBytes || offset > data.size - length) return last
        var i = offset
        var frames = 0
        while (i + frameBytes <= offset + length) {
            var sample = 0.0
            for (channel in 0 until count) {
                val p = i + channel * 2
                sample += ((data[p].toInt() and 255) or (data[p + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
            }
            ring[next] = sample / count
            next = (next + 1) % WINDOW_FRAMES
            if (filled < WINDOW_FRAMES) filled++
            frames++
            i += frameBytes
        }
        framesSinceAnalysis += frames
        if (filled < WINDOW_FRAMES || framesSinceAnalysis < ANALYSIS_FRAMES) return last
        framesSinceAnalysis = 0
        last = levels()
        return last
    }

    private fun levels(): IntArray {
        val decibels = DoubleArray(BAND_COUNT)
        var framePeak = SILENCE_DB
        for (band in 0 until BAND_COUNT) {
            val coefficient = coefficients[band]
            var s1 = 0.0
            var s2 = 0.0
            var position = next
            for (n in 0 until WINDOW_FRAMES) {
                val s0 = ring[position] + coefficient * s1 - s2
                s2 = s1
                s1 = s0
                position = if (position + 1 == WINDOW_FRAMES) 0 else position + 1
            }
            val power = s1 * s1 + s2 * s2 - coefficient * s1 * s2
            val amplitude = 2.0 * kotlin.math.sqrt(abs(power)) / WINDOW_FRAMES
            decibels[band] = (20.0 * log10(amplitude + 1e-9)).coerceAtLeast(SILENCE_DB)
            framePeak = maxOf(framePeak, decibels[band])
        }
        peakDb = maxOf(framePeak, peakDb - PEAK_DECAY_DB)
        if (peakDb <= SILENCE_DB + 6.0) return IntArray(BAND_COUNT) { 1 }
        return IntArray(BAND_COUNT) { band ->
            val fraction = ((decibels[band] - (peakDb - RANGE_DB)) / RANGE_DB).coerceIn(0.0, 1.0)
            1 + (fraction * (MAX_LEVEL - 1)).toInt()
        }
    }

    companion object {
        const val BAND_COUNT = 16
        const val MAX_LEVEL = 15
        /** The frequencies BYD's audio service reads for the rhythm. */
        val FREQUENCIES = intArrayOf(40, 80, 120, 200, 250, 300, 400, 500, 600, 700, 800, 900, 1200, 1600, 1800, 3500)
        private const val WINDOW_FRAMES = 2048
        private const val ANALYSIS_FRAMES = 1920
        private const val SILENCE_DB = -80.0
        private const val RANGE_DB = 36.0
        // About 4 dB a second at one analysis per 40 ms.
        private const val PEAK_DECAY_DB = 0.16
    }
}
