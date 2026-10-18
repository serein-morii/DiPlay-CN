package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class NcmTrafficCounterTest {
    private fun ethernet(etherType: Int, payload: ByteArray) =
        ByteArray(12) + byteArrayOf((etherType shr 8).toByte(), etherType.toByte()) + payload

    private fun tcp(destinationPort: Int, flags: Int) = ByteArray(20).also {
        it[2] = (destinationPort shr 8).toByte()
        it[3] = destinationPort.toByte()
        it[13] = flags.toByte()
    }

    private fun ipv6(nextHeader: Int, payload: ByteArray) =
        ethernet(0x86dd, ByteArray(40).also { it[6] = nextHeader.toByte() } + payload)

    private fun ipv4(protocol: Int, payload: ByteArray) =
        ethernet(0x0800, ByteArray(20).also { it[0] = 0x45; it[9] = protocol.toByte() } + payload)

    @Test fun countsConnectionAttemptsToTheAirPlayListenerApart() {
        val counter = NcmTrafficCounter()
        counter.received(ipv6(6, tcp(7000, 0x02)))
        counter.received(ipv6(6, tcp(7000, 0x02)))
        // SYN-ACK and plain ACK are replies, not attempts.
        counter.received(ipv6(6, tcp(7000, 0x12)))
        counter.received(ipv6(6, tcp(7000, 0x10)))
        counter.received(ipv6(17, ByteArray(8)))
        counter.received(ipv4(6, tcp(5353, 0x02)))
        counter.sent()
        assertEquals(
            "fromIphone=6 ipv6=5 ipv4=1 tcpConnectToAirPlay=2 tcpConnectOther=5353:1 toIphone=1",
            counter.summary(7000),
        )
    }

    @Test fun shortAndForeignFramesAreCountedButNotParsed() {
        val counter = NcmTrafficCounter()
        counter.received(ByteArray(5))
        counter.received(ethernet(0x0806, ByteArray(28)))
        counter.received(ethernet(0x86dd, ByteArray(10)))
        assertEquals(
            "fromIphone=3 ipv6=1 ipv4=0 tcpConnectToAirPlay=0 tcpConnectOther=none toIphone=0",
            counter.summary(7000),
        )
    }
}
