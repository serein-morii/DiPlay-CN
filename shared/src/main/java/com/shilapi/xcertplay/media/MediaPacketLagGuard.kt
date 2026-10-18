package com.shilapi.xcertplay.media

import java.util.concurrent.LinkedBlockingQueue

/**
 * Detects a *sustained* compressed-media backlog using RTP timestamps, independently of
 * packet-count or codec frame size. A short Wi-Fi catch-up burst must not be discarded.
 */
internal class MediaPacketLagGuard(private val clockRate: Int) {
    private var congestionSinceNs = 0L
    private var recovering = false

    /** Time between first undelivered RTP packet and the latest packet, respecting 32-bit wrap. */
    fun <T> pendingFrames(queue: LinkedBlockingQueue<T>, newestSample: Int, timestampOf: (T) -> Int): Long {
        if (clockRate <= 0) return 0
        val first = queue.peek() ?: return 0
        val frames = (newestSample.toLong() - timestampOf(first).toLong()) and 0xffff_ffffL
        // An iPhone can restart its RTP timestamp at a track/stream boundary. Do not treat the
        // resulting huge unsigned delta as congestion.
        return if (frames <= clockRate * MAX_VALID_SPAN_SECONDS.toLong()) frames else 0
    }

    fun <T> recover(
        queue: LinkedBlockingQueue<T>,
        newestSample: Int,
        timestampOf: (T) -> Int,
        nowNs: Long,
    ): Int {
        val durationFrames = pendingFrames(queue, newestSample, timestampOf)
        val delayNs = durationFrames * 1_000_000_000L / clockRate.coerceAtLeast(1)
        if (!recovering) {
            if (delayNs <= CONGESTION_THRESHOLD_NS) {
                congestionSinceNs = 0
                return 0
            }
            if (congestionSinceNs == 0L) {
                congestionSinceNs = nowNs
                return 0
            }
            if (nowNs - congestionSinceNs < CONGESTION_GRACE_NS) return 0
            recovering = true
        }
        if (delayNs <= RECOVERY_END_NS) {
            recovering = false
            congestionSinceNs = 0
            return 0
        }
        var removed = 0
        while (queue.isNotEmpty() &&
            pendingFrames(queue, newestSample, timestampOf) * 1_000_000_000L /
            clockRate.coerceAtLeast(1) > RECOVERY_TARGET_NS) {
            if (queue.poll() == null) break
            removed++
        }
        return removed
    }

    internal companion object {
        const val MAX_VALID_SPAN_SECONDS = 16
        const val CONGESTION_THRESHOLD_NS = 1_500_000_000L
        const val CONGESTION_GRACE_NS = 1_500_000_000L
        const val RECOVERY_TARGET_NS = 500_000_000L
        const val RECOVERY_END_NS = 200_000_000L
    }
}
