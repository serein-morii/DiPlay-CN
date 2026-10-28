package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ScreenStreamPeerTest {
    @org.junit.Before fun needsIpv6Loopback() {
        org.junit.Assume.assumeTrue("no IPv6 loopback", runCatching {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("::1")).use { true }
        }.getOrDefault(false))
    }

    private val key = ByteArray(32) { it.toByte() }
    private val frames = LinkedBlockingQueue<ByteArray>()
    private val configs = LinkedBlockingQueue<ByteArray>()
    private val closes = LinkedBlockingQueue<String>()

    private val listener = object : ScreenStream.Listener {
        override fun onFrame(naluBytes: ByteArray) { frames.add(naluBytes) }
        override fun onConfig(codecData: ByteArray) { configs.add(codecData) }
        override fun onClosed(cause: Throwable?) { closes.add(cause?.toString() ?: "peer EOF") }
    }

    private fun header(opcode: Int, bodySize: Int) = ByteArray(128).also {
        it[0] = bodySize.toByte()
        it[1] = (bodySize shr 8).toByte()
        it[OPCODE] = opcode.toByte()
    }

    private fun sealedFrame(counter: Long, nal: ByteArray): ByteArray {
        val plain = byteArrayOf(0, 0, 0, nal.size.toByte()) + nal
        val head = header(0, plain.size + ScreenCodec.TAG_SIZE)
        return head + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, head)
    }

    private fun send(port: Int, bytes: ByteArray) {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            Thread.sleep(SETTLE_MILLIS)
        }
    }

    @Test
    fun aStrangersBadFrameDoesNotEndTheSession() {
        val stream = ScreenStream(key, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)))
        try {
            val port = stream.listen(listener)
            send(port, header(0, 32) + ByteArray(32) { 7 })
            assertNull("session closed by stranger: ${closes.peek()}", closes.peek())
        } finally { stream.close() }
    }

    @Test
    fun aStrangersConfigIsNotDelivered() {
        val stream = ScreenStream(key, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)))
        try {
            val port = stream.listen(listener)
            val avcc = byteArrayOf(1, 0x64, 0, 0x1f, 0xff.toByte(), 0xe1.toByte(), 0, 4, 0x67, 0x64, 0, 0x1f)
            send(port, header(1, avcc.size) + avcc)
            assertNull("stranger config reached the decoder: ${configs.peek()?.size} bytes", configs.peek())
        } finally { stream.close() }
    }

    @Test
    fun aFrameShorterThanTheTagIsDropped() {
        val stream = ScreenStream(key, InetAddress.getLoopbackAddress())
        try {
            val port = stream.listen(listener)
            send(port, header(0, 8) + byteArrayOf(0, 0, 0, 4, 1, 2, 3, 4))
            assertNull("unsealed frame reached the decoder: ${frames.peek()?.size} bytes", frames.peek())
        } finally { stream.close() }
    }

    @Test
    fun theControlPeersSealedFrameIsDelivered() {
        val stream = ScreenStream(key, InetAddress.getLoopbackAddress())
        try {
            val port = stream.listen(listener)
            send(port, sealedFrame(0, byteArrayOf(0x65, 0x11)))
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x11), frames.poll(3, TimeUnit.SECONDS))
        } finally { stream.close() }
    }

    @Test
    fun theControlPeersConfigIsStillDelivered() {
        val stream = ScreenStream(key, InetAddress.getLoopbackAddress())
        try {
            val port = stream.listen(listener)
            val avcc = byteArrayOf(1, 0x64, 0, 0x1f, 0xff.toByte(), 0xe1.toByte(), 0, 4, 0x67, 0x64, 0, 0x1f)
            send(port, header(1, avcc.size) + avcc)
            assertNotNull(configs.poll(3, TimeUnit.SECONDS))
        } finally { stream.close() }
    }

    @Test
    fun aStrangerHoldingAnOpenConnectionDoesNotBlockTheControlPeer() {
        val ipv6Peer = InetAddress.getByName("::1")
        val stream = ScreenStream(key, ipv6Peer)
        try {
            val port = stream.listen(listener)
            Socket(InetAddress.getByName("127.0.0.1"), port).use {
                Thread.sleep(SETTLE_MILLIS)
                Socket(ipv6Peer, port).use { peer ->
                    peer.getOutputStream().write(sealedFrame(0, byteArrayOf(0x65, 0x22)))
                    assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x22), frames.poll(3, TimeUnit.SECONDS))
                }
            }
        } finally { stream.close() }
    }

    @Test
    fun anIpv4MappedControlPeerIsMatchedToItsIpv4Connection() {
        val mapped = InetAddress.getByAddress(ByteArray(10) + byteArrayOf(-1, -1, 127, 0, 0, 1))
        val stream = ScreenStream(key, mapped)
        try {
            val port = stream.listen(listener)
            send(port, sealedFrame(0, byteArrayOf(0x65, 0x33)))
            assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 0x33), frames.poll(3, TimeUnit.SECONDS))
        } finally { stream.close() }
    }

    private companion object {
        const val OPCODE = 4
        const val SETTLE_MILLIS = 700L
    }
}
