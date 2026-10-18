package com.shilapi.xcertplay.media

import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.*
import org.junit.Test

class MediaPacketLagGuardTest {
    private data class Packet(val sample: Int)

    @Test fun prolongedCompressedBacklogCannotDelayNextSongBySeveralSeconds() {
        val packets = LinkedBlockingQueue<Packet>(192)
        val guard = MediaPacketLagGuard(48_000)
        // Recorded congestion reached 192 AAC packets; each carries 1024 samples (~21ms).
        // Replay one new packet every ~21ms while decode is not keeping up.
        var nowNs = 1_000_000_000L
        var dropped = 0
        repeat(192) { n ->
            if (!packets.offer(Packet(n * 1024))) packets.poll().also { packets.offer(Packet(n * 1024)) }
            dropped += guard.recover(packets, n * 1024, Packet::sample, nowNs)
            nowNs += 21_333_333L
        }
        // A delayed track change must not have >1sec of old RTP queued ahead of it.
        val span = (packets.last().sample.toLong() - packets.peek().sample.toLong())
        assertTrue("compressed backlog remained ${span * 1000 / 48_000}ms", span <= 48_000)
        assertTrue("must eliminate stale packets on sustained overload", dropped > 0)
    }

    @Test fun wifiCatchupBurstIsPreservedIfDecoderCatchesUpQuickly() {
        val queue = LinkedBlockingQueue<Packet>(192)
        val guard = MediaPacketLagGuard(48_000)
        repeat(150) { queue.offer(Packet(it * 1024)) }
        // 3.2 seconds' worth of arriving packets after a Wi-Fi outage, delivered instantly.
        assertEquals(0, guard.recover(queue, 149 * 1024, Packet::sample, 1_000_000_000L))
        repeat(110) { queue.poll() } // Healthy consumer immediately catches up
        assertEquals(0, guard.recover(queue, 149 * 1024, Packet::sample, 2_000_000_000L))
        assertEquals(40, queue.size)
    }

    @Test fun rtpWrapDoesNotPretendPacketsAreFourBillionSamplesOld() {
        val q = LinkedBlockingQueue<Packet>(192)
        val g = MediaPacketLagGuard(48_000)
        val oldest = -24_576
        repeat(25) { q.offer(Packet(oldest + it * 1024)) }
        assertEquals(0, g.recover(q, 0, Packet::sample, 1_000_000_000L))
        assertEquals(25, q.size)
    }

    @Test fun noPacketsAndClockResetNeverCauseEviction() {
        val q = LinkedBlockingQueue<Packet>(192)
        val g = MediaPacketLagGuard(48_000)
        assertEquals(0, g.recover(q, 1, Packet::sample, 1_000_000_000L))
        q.offer(Packet(Int.MAX_VALUE))
        assertEquals(0, g.recover(q, 1000, Packet::sample, 9_000_000_000L))
        assertEquals(1, q.size)
    }
}
