package com.shilapi.xcertplay.airplay

/**
 * Compares the playback position DiPlay reports in /feedback with the newest RTP sample it has
 * received (#620). The reported position is extrapolated from the wall clock, so it keeps moving
 * while a stream starves and can run past the audio that actually arrived. Whether iOS stops a
 * stream because of that is unknown; these lines let a report tell it apart from packet loss.
 * Diagnostics only: the reported position is not changed.
 */
internal class FeedbackPositionMonitor(
    private val sampleRate: Int,
    private val repeatNs: Long = 10_000_000_000L,
) {
    private var received: Long? = null
    private var baselineLogged = false
    private var lastOverrunNs: Long? = null

    /** [sample] is the RTP timestamp of a received packet, which may arrive out of order. */
    @Synchronized
    fun observeRtp(sample: Int) {
        val unsigned = sample.toLong() and 0xffff_ffffL
        val current = received
        if (current == null || samplesAhead(unsigned, current) > 0) received = unsigned
    }

    /**
     * Returns a report line for [reported], the position just sent to the iPhone: once for the
     * first answer of a stream, then whenever it runs more than [OVERRUN_SECONDS] past the newest
     * received packet, at most once every [repeatNs].
     */
    @Synchronized
    fun onFeedback(type: Int, connectionId: Any?, reported: Long, nowNs: Long): String? {
        val received = received ?: return null
        val ahead = samplesAhead(reported, received)
        val overrun = ahead > OVERRUN_SECONDS * sampleRate
        val first = !baselineLogged
        if (!first && !overrun) return null
        if (!first && lastOverrunNs?.let { nowNs - it < repeatNs } == true) return null
        baselineLogged = true
        if (overrun) lastOverrunNs = nowNs
        return "Audio: feedback type=$type id=${connectionId ?: 0} reported=$reported received=$received " +
            "aheadMs=${ahead * 1000 / sampleRate}" + if (overrun) " overrun=true" else ""
    }

    internal companion object {
        /** More than a packet of slack, so a position inside the newest packet is not an over-run. */
        const val OVERRUN_SECONDS = 0.1

        /** Signed distance from [base] to [sample] on the 32-bit RTP clock. */
        fun samplesAhead(sample: Long, base: Long): Long = ((sample - base) and 0xffff_ffffL).toInt().toLong()
    }
}
