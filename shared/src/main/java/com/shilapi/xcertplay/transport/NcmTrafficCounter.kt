package com.shilapi.xcertplay.transport

/**
 * Counts the Ethernet frames on the NCM link, so a wired attempt that never gets its AirPlay session can tell an
 * iPhone that never tried (no frames, or no TCP connection attempt) from one whose packets arrive but do not reach
 * DiPlay's listener (#889, #890). Only headers are read, never payloads. Free of Android types for JVM tests.
 */
internal class NcmTrafficCounter(private val nanoTime: () -> Long = System::nanoTime) {
    private var fromIphone = 0L
    private var ipv6 = 0L
    private var ipv4 = 0L
    private var toIphone = 0L
    private val synsByPort = LinkedHashMap<Int, Int>()
    private val lastSynNanosByPort = HashMap<Int, Long>()

    @Synchronized fun received(frame: ByteArray) {
        fromIphone++
        val etherType = u16(frame, ETHER_TYPE_OFFSET) ?: return
        val tcp = when (etherType) {
            ETHER_TYPE_IPV6 -> {
                ipv6++
                if (u8(frame, ETHERNET_HEADER + IPV6_NEXT_HEADER_OFFSET) == PROTOCOL_TCP) ETHERNET_HEADER + IPV6_HEADER else null
            }
            ETHER_TYPE_IPV4 -> {
                ipv4++
                val headerLength = ((u8(frame, ETHERNET_HEADER) ?: return) and 0x0f) * 4
                if (u8(frame, ETHERNET_HEADER + IPV4_PROTOCOL_OFFSET) == PROTOCOL_TCP) ETHERNET_HEADER + headerLength else null
            }
            else -> null
        } ?: return
        val flags = u8(frame, tcp + TCP_FLAGS_OFFSET) ?: return
        if (flags and TCP_SYN == 0 || flags and TCP_ACK != 0) return
        val port = u16(frame, tcp + TCP_DESTINATION_PORT_OFFSET) ?: return
        if (port in synsByPort || synsByPort.size < MAX_PORTS) {
            synsByPort[port] = (synsByPort[port] ?: 0) + 1
            lastSynNanosByPort[port] = nanoTime()
        }
    }

    /** Whether the iPhone tried to connect to [port] at or after [sinceNanos] ([System.nanoTime] time). */
    @Synchronized fun connectionAttemptSince(port: Int, sinceNanos: Long): Boolean =
        lastSynNanosByPort[port]?.let { it - sinceNanos >= 0 } == true

    @Synchronized fun sent() {
        toIphone++
    }

    /** One line for the report; [airPlayPort] is DiPlay's listener, whose connection attempts are named apart. */
    @Synchronized fun summary(airPlayPort: Int?): String {
        val toAirPlay = airPlayPort?.let { synsByPort[it] } ?: 0
        val others = synsByPort.filterKeys { it != airPlayPort }.entries.joinToString(",") { "${it.key}:${it.value}" }
        return "fromIphone=$fromIphone ipv6=$ipv6 ipv4=$ipv4 tcpConnectToAirPlay=$toAirPlay " +
            "tcpConnectOther=${others.ifEmpty { "none" }} toIphone=$toIphone"
    }

    private fun u8(frame: ByteArray, offset: Int): Int? = frame.getOrNull(offset)?.toInt()?.and(0xff)

    private fun u16(frame: ByteArray, offset: Int): Int? {
        val high = u8(frame, offset) ?: return null
        val low = u8(frame, offset + 1) ?: return null
        return (high shl 8) or low
    }

    private companion object {
        const val ETHERNET_HEADER = 14
        const val ETHER_TYPE_OFFSET = 12
        const val ETHER_TYPE_IPV4 = 0x0800
        const val ETHER_TYPE_IPV6 = 0x86dd
        const val IPV6_HEADER = 40
        const val IPV6_NEXT_HEADER_OFFSET = 6
        const val IPV4_PROTOCOL_OFFSET = 9
        const val PROTOCOL_TCP = 6
        const val TCP_DESTINATION_PORT_OFFSET = 2
        const val TCP_FLAGS_OFFSET = 13
        const val TCP_SYN = 0x02
        const val TCP_ACK = 0x10
        const val MAX_PORTS = 8
    }
}
