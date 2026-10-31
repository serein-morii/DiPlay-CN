package com.shilapi.xcertplay.media

/**
 * Plays silence where Wi-Fi lost music packets (#1205).
 *
 * Without it the decoded music closes up over each hole and runs ahead of the iPhone: every lost packet
 * drains the buffer by its length, the clock trim slows the music to its limit and still falls behind, and
 * playback stops to refill. One head unit lost about 16 packets every 5 s and stopped 23 times in 14 minutes.
 * Silence keeps the music on the iPhone's clock, so the buffer holds its level.
 *
 * Only a hole left by lost packets is filled. Packets DiPlay drops on purpose to catch up keep their hole,
 * and a decoder that does not pass the packets' timestamps through shows no hole, so it plays as before.
 */
internal class LostMusicFill(private val sampleRate: Int) {
    private val afterLossUs = ArrayDeque<Long>()
    private var nextUs: Long? = null

    /** The packet that starts at [ptsUs] arrived after one or more packets were lost. */
    fun lossBefore(ptsUs: Long) {
        if (afterLossUs.size == MAX_PENDING) afterLossUs.removeFirst()
        afterLossUs.addLast(ptsUs)
    }

    /** Frames of silence to play before a decoded buffer that starts at [ptsUs] and holds [frames]. */
    fun silenceBefore(ptsUs: Long, frames: Int): Int {
        val halfFrameUs = frames * 500_000L / sampleRate
        // A timestamp restart leaves marks far ahead that no buffer will reach.
        while (afterLossUs.isNotEmpty() &&
            afterLossUs.first().let { it < ptsUs - halfFrameUs || it > ptsUs + STALE_AHEAD_US }) {
            afterLossUs.removeFirst()
        }
        val expected = nextUs
        nextUs = ptsUs + frames * 1_000_000L / sampleRate
        if (expected == null || afterLossUs.isEmpty() || afterLossUs.first() > ptsUs + halfFrameUs) return 0
        afterLossUs.removeFirst()
        val missingUs = ptsUs - expected
        if (missingUs < halfFrameUs || missingUs > MAX_FILL_US) return 0
        return ((missingUs * sampleRate + 500_000L) / 1_000_000L).toInt()
    }

    internal companion object {
        /** About 23 AAC packets. A longer hole is a stall or a skip, not a few lost packets. */
        const val MAX_FILL_US = 500_000L
        const val STALE_AHEAD_US = 2_000_000L
        const val MAX_PENDING = 64
    }
}
