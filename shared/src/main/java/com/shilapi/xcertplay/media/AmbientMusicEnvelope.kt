package com.shilapi.xcertplay.media

import kotlin.math.sqrt

/** Stores levels, never PCM. Frame intervals follow successful AudioTrack writes. */
internal class AmbientMusicEnvelope {
    private data class Segment(val start: Long, val end: Long, val rms: Double, val bassRms: Double, val bands: IntArray? = null)
    data class Energy(val rms: Double, val bassRms: Double)
    private val segments = ArrayDeque<Segment>()
    private var headEpoch = 0L
    private var previousHead = 0L
    private var playedPeak = 0.0
    private var playedBassPeak = 0.0
    private var playedBandPeak: IntArray? = null

    @Synchronized fun append(start: Long, frames: Long, rms: Double, bassRms: Double = 0.0, bands: IntArray? = null) {
        if (frames <= 0 || !rms.isFinite()) return
        segments.addLast(Segment(start, start + frames, rms.coerceIn(0.0, 1.0), if (bassRms.isFinite()) bassRms.coerceIn(0.0, 1.0) else 0.0, bands))
        while (segments.size > 512) segments.removeFirst()
    }

    @Synchronized fun rms(rawPlaybackHead: Int, playing: Boolean): Double {
        val head = rawPlaybackHead.toLong() and 0xffff_ffffL
        if (head < previousHead && previousHead - head > 0x8000_0000L) headEpoch += 0x1_0000_0000L
        previousHead = head
        val position = headEpoch + head
        while (segments.firstOrNull()?.let { it.end <= position } == true) {
            val played = segments.removeFirst()
            playedPeak = maxOf(playedPeak, played.rms)
            playedBassPeak = maxOf(playedBassPeak, played.bassRms)
            played.bands?.let { playedBandPeak = elementwiseMax(playedBandPeak, it) }
        }
        val segment = segments.firstOrNull()
        return if (playing && segment != null && position >= segment.start) segment.rms else 0.0
    }

    /**
     * The band magnitudes of what has played since the last call, the loudest of each band, so a short onset between
     * two lamp updates is kept. Null when nothing played or no bands were stored.
     */
    @Synchronized fun playedBands(rawPlaybackHead: Int, playing: Boolean): IntArray? {
        rms(rawPlaybackHead, playing)
        val current = segments.firstOrNull()?.takeIf { headEpoch + previousHead >= it.start }?.bands
        val peak = playedBandPeak
        playedBandPeak = null
        return if (!playing) null else elementwiseMax(peak, current)
    }

    private fun elementwiseMax(first: IntArray?, second: IntArray?): IntArray? = when {
        first == null -> second?.copyOf()
        second == null -> first
        else -> IntArray(minOf(first.size, second.size)) { maxOf(first[it], second[it]) }
    }

    /** Preserve already-played short onsets between the 200ms lamp updates. */
    @Synchronized fun playedWindow(rawPlaybackHead: Int, playing: Boolean): Energy {
        val current = rms(rawPlaybackHead, playing)
        val currentBass = segments.firstOrNull()?.takeIf { headEpoch + previousHead >= it.start }?.bassRms ?: 0.0
        val peak = playedPeak
        val bassPeak = playedBassPeak
        playedPeak = 0.0; playedBassPeak = 0.0
        return if (playing) Energy(maxOf(current, peak), maxOf(currentBass, bassPeak)) else Energy(0.0, 0.0)
    }
    fun playedWindowRms(rawPlaybackHead: Int, playing: Boolean): Double = playedWindow(rawPlaybackHead, playing).rms

    fun level(rawPlaybackHead: Int, playing: Boolean, maximum: Int): Int =
        brightness(rms(rawPlaybackHead, playing), maximum)

    fun brightness(rms: Double, maximum: Int): Int {
        // Keep the user's selected brightness as a ceiling; silence stays at raw level 1.
        return (1 + (sqrt(rms) * (maximum.coerceIn(1, 6) - 1)).toInt()).coerceIn(1, maximum.coerceIn(1, 6))
    }

    companion object {
        fun pcmRms(data: ByteArray, offset: Int, length: Int): Double {
            if (offset < 0 || length < 2 || offset > data.size - length) return 0.0
            var square = 0.0
            var samples = 0
            // At most about 256 sample reads per write, including both stereo channels.
            val stride = maxOf(1, length / 512) * 2
            var i = offset
            while (i + 1 < offset + length) {
                val sample = ((data[i].toInt() and 255) or (data[i + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
                square += sample * sample
                samples++
                i += stride
            }
            return if (samples == 0) 0.0 else sqrt(square / samples)
        }
    }
}

internal fun ambientApplyCancelled(error: Throwable): Boolean {
    var current = error
    repeat(8) {
        if (current is java.util.concurrent.CancellationException) return true
        current = current.cause ?: return false
    }
    return false
}
