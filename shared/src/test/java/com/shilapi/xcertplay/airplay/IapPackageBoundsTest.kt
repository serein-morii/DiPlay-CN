package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IapPackageBoundsTest {
    private val key = ByteArray(32) { 7 }
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun frame(counter: Long, plain: ByteArray): ByteArray {
        val aad = byteArrayOf(plain.size.toByte(), (plain.size shr 8).toByte())
        return aad + AirPlayCrypto.chachaSeal(key, AirPlayCrypto.nonce64(counter), plain, aad)
    }

    private fun header(size: Int, type: String): ByteArray {
        val h = ByteArray(32)
        h[0] = (size ushr 24).toByte(); h[1] = (size ushr 16).toByte()
        h[2] = (size ushr 8).toByte(); h[3] = size.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(h, 16)
        return h
    }

    @Test fun iapTunnelClosesOnOversizedPackageHeader() {
        val closed = CountDownLatch(1)
        val tunnel = IapTunnel(key, expectedPeer = loopback, bindAddress = loopback)
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onClosed(cause: Throwable?) = closed.countDown()
        })
        try {
            Socket(loopback, port).use { client ->
                client.getOutputStream().apply { write(frame(0, header(0x7fffffff, "comm"))); flush() }
                assertTrue("tunnel still open after oversized package header", closed.await(3, TimeUnit.SECONDS))
            }
        } finally {
            tunnel.close()
        }
    }

    @Test fun iapTunnelDeliversRealPackage() {
        val got = CountDownLatch(1)
        var body = ByteArray(0)
        val tunnel = IapTunnel(key, expectedPeer = loopback, bindAddress = loopback)
        val port = tunnel.listen(object : IapTunnel.Listener {
            override fun onIap(bytes: ByteArray) { body = bytes; got.countDown() }
        })
        try {
            Socket(loopback, port).use { client ->
                val payload = byteArrayOf(1, 2, 3)
                client.getOutputStream().apply { write(frame(0, header(32 + payload.size, "comm") + payload)); flush() }
                assertTrue(got.await(3, TimeUnit.SECONDS))
                assertArrayEquals(payload, body)
            }
        } finally {
            tunnel.close()
        }
    }

    // Regression guard, not a reproduction: this channel already rejected an oversized header on main.
    @Test fun videoSettingsChannelClosesOnOversizedPackageHeader() {
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val channel = VideoSettingsChannel(key, key, loopback, logs::add)
        val port = channel.listen(loopback)
        try {
            Socket(loopback, port).use { client ->
                client.getOutputStream().apply { write(frame(0, header(0x7fffffff, "sync"))); flush() }
                client.soTimeout = 3000
                val read = try { client.getInputStream().read() } catch (_: java.io.IOException) { -1 }
                assertTrue("expected close, got $read", read == -1)
            }
        } finally {
            channel.close()
        }
    }
}
