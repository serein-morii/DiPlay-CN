package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IapTunnelPeerTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val iap = LinkedBlockingQueue<ByteArray>()
    private val opens = LinkedBlockingQueue<String?>()
    private val closes = LinkedBlockingQueue<String>()

    private val listener = object : IapTunnel.Listener {
        override fun onOpen(remoteAddress: String?) { opens.add(remoteAddress) }
        override fun onIap(bytes: ByteArray) { iap.add(bytes) }
        override fun onClosed(cause: Throwable?) { closes.add(cause?.toString() ?: "peer EOF") }
    }

    private fun frame(counter: Long, plain: ByteArray): ByteArray {
        val aad = byteArrayOf(plain.size.toByte(), (plain.size shr 8).toByte())
        return aad + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, aad)
    }

    private fun commPackage(body: ByteArray): ByteArray {
        val size = 32 + body.size
        val header = ByteArray(32)
        header[3] = size.toByte()
        "comm".toByteArray(Charsets.US_ASCII).copyInto(header, 16)
        return header + body
    }

    private fun send(port: Int, bytes: ByteArray) {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            Thread.sleep(SETTLE_MILLIS)
        }
    }

    @Test
    fun aStrangersBadFrameDoesNotEndTheTunnel() {
        val tunnel = IapTunnel(key, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)))
        try {
            val port = tunnel.listen(listener)
            send(port, byteArrayOf(32, 0) + ByteArray(32 + 16) { 9 })
            assertNull("tunnel closed by stranger: ${closes.peek()}", closes.peek())
            assertNull("stranger was announced as the peer: ${opens.peek()}", opens.peek())
        } finally { tunnel.close() }
    }

    @Test
    fun theControlPeersSealedPackageIsDelivered() {
        val tunnel = IapTunnel(key, InetAddress.getLoopbackAddress())
        try {
            val port = tunnel.listen(listener)
            send(port, frame(0, commPackage(byteArrayOf(1, 2, 3))))
            assertArrayEquals(byteArrayOf(1, 2, 3), iap.poll(3, TimeUnit.SECONDS))
        } finally { tunnel.close() }
    }

    private companion object { const val SETTLE_MILLIS = 700L }
}
