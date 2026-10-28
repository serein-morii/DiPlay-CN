package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** 每次绑定生成独立身份，防止旧 accept 和旧清理作用于新 listener。 */
class AirPlayListenerIdentity(val generation: Int) {
    val id: Long = nextId.incrementAndGet()
    private companion object { val nextId = AtomicLong() }
}

data class AirPlayTcpAccepted(
    val listener: AirPlayListenerIdentity,
    val internal: Boolean,
    val acceptedAtNanos: Long,
)

internal fun isInternalAirPlayPeer(peer: InetAddress, local: InetAddress): Boolean =
    peer.isLoopbackAddress || peer == local ||
        runCatching { NetworkInterface.getByInetAddress(peer) != null }.getOrDefault(true)

/**
 * Accepts until a connection comes from [expected], the control-session peer; others are closed unread.
 * Java reports an IPv4-mapped peer as its IPv4 address and ignores the scope of a link-local one,
 * so a plain address comparison covers the hotspot, Wi-Fi Direct and USB NCM paths.
 */
internal fun ServerSocket.acceptFrom(expected: InetAddress, onRejected: (InetAddress?) -> Unit = {}): Socket {
    while (true) {
        val socket = accept()
        if (socket.inetAddress == expected) return socket
        onRejected(socket.inetAddress)
        runCatching { socket.close() }
    }
}
