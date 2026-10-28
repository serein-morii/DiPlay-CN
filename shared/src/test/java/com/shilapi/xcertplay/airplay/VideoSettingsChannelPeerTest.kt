package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSettingsChannelPeerTest {
    private val readKey = ByteArray(32) { 1 }
    private val writeKey = ByteArray(32) { 2 }
    private val logs = CopyOnWriteArrayList<String>()

    @Test
    fun aStrangersBadFrameDoesNotEndTheChannel() {
        val channel = VideoSettingsChannel(readKey, writeKey, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))) { logs += it }
        try {
            val port = channel.listen(InetAddress.getLoopbackAddress())
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.getOutputStream().write(byteArrayOf(32, 0) + ByteArray(32 + 16) { 9 })
                socket.getOutputStream().flush()
                Thread.sleep(700)
            }
            assertTrue("stranger ended the channel: $logs", logs.none { it.contains("ended") })
        } finally { channel.close() }
    }

    @Test
    fun theControlPeersSyncIsAnswered() {
        val channel = VideoSettingsChannel(readKey, writeKey, InetAddress.getLoopbackAddress()) { logs += it }
        try {
            val port = channel.listen(InetAddress.getLoopbackAddress())
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                socket.soTimeout = 3000
                val sync = ByteArray(32)
                sync[3] = 32
                "sync".toByteArray(Charsets.US_ASCII).copyInto(sync, 4)
                val aad = byteArrayOf(32, 0)
                socket.getOutputStream().write(
                    aad + AirPlayCrypto.chachaSeal(readKey, AirPlayCrypto.nonce64(0), sync, aad),
                )
                socket.getOutputStream().flush()
                val reply = ByteArray(2 + 32 + 16)
                var read = 0
                while (read < reply.size) {
                    val n = socket.getInputStream().read(reply, read, reply.size - read)
                    assertTrue("channel closed before replying", n >= 0)
                    read += n
                }
                val plain = AirPlayCrypto.chachaOpen(
                    writeKey, AirPlayCrypto.nonce64(0), reply.copyOfRange(2, reply.size), reply.copyOfRange(0, 2),
                )
                assertEquals("rply", String(plain, 4, 4, Charsets.US_ASCII))
            }
        } finally { channel.close() }
    }
}
