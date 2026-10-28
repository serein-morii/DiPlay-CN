package com.shilapi.xcertplay.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import org.junit.Assert.assertTrue
import org.junit.Test

class NtpClockPeerTest {
    @org.junit.Before fun needsIpv6Loopback() {
        org.junit.Assume.assumeTrue("no IPv6 loopback", runCatching {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("::1")).use { true }
        }.getOrDefault(false))
    }

    private val iphone = InetAddress.getByName("::1")
    private val stranger = InetAddress.getByName("127.0.0.1")

    private fun skewedResponse(request: ByteArray): ByteArray {
        val t1 = ByteBuffer.wrap(request).getLong(TRANSMIT)
        val response = ByteArray(32)
        response[0] = 0x80.toByte()
        response[1] = 211.toByte()
        val buffer = ByteBuffer.wrap(response)
        buffer.putLong(ORIGINATE, t1)
        buffer.putLong(RECEIVE, t1 + SKEW)
        buffer.putLong(TRANSMIT, t1 + SKEW)
        return response
    }

    /** Seconds the synced clock is ahead of this host's wall clock. */
    private fun driftSeconds(ntp: NtpClock): Long {
        val synced = ntp.syncedNtp().shiftRight(32).toLong()
        return synced - (System.currentTimeMillis() / 1000 + NTP_EPOCH_OFFSET)
    }

    private fun runExchange(replyFrom: InetAddress): Long {
        val ntp = NtpClock()
        val phone = DatagramSocket(InetSocketAddress(iphone, 0))
        val replier = DatagramSocket(InetSocketAddress(replyFrom, 0))
        try {
            phone.soTimeout = 3000
            val port = ntp.listen()
            ntp.start(iphone, phone.localPort)
            repeat(2) {
                val request = DatagramPacket(ByteArray(64), 64)
                phone.receive(request)
                val response = skewedResponse(request.data.copyOf(32))
                replier.send(DatagramPacket(response, response.size, replyFrom, port))
            }
            Thread.sleep(300)
            return driftSeconds(ntp)
        } finally {
            ntp.close()
            phone.close()
            replier.close()
        }
    }

    @Test
    fun aStrangersResponseCannotMoveTheClock() {
        val drift = runExchange(replyFrom = stranger)
        assertTrue("clock skewed by a non-peer source: drift=${drift}s", Math.abs(drift) < 60)
    }

    @Test
    fun thePhonesResponseStillMovesTheClock() {
        val drift = runExchange(replyFrom = iphone)
        assertTrue("clock did not follow the peer: drift=${drift}s", drift > 3000)
    }

    private companion object {
        const val ORIGINATE = 8
        const val RECEIVE = 16
        const val TRANSMIT = 24
        const val SKEW = 3600L shl 32
        const val NTP_EPOCH_OFFSET = 2_208_988_800L
    }
}
