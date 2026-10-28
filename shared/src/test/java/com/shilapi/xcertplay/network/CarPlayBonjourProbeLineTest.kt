package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayBonjourProbeLineTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun probe(serve: (java.net.Socket) -> Unit): Result<String> {
        val server = ServerSocket(0, 1, loopback)
        val worker = Thread {
            runCatching { server.accept().use { socket ->
                socket.getInputStream().read(ByteArray(1024))
                serve(socket)
            } }
        }.apply { isDaemon = true; start() }
        val bonjour = CarPlayBonjour(
            org.robolectric.RuntimeEnvironment.getApplication(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:02", "366.0",
                main = AirPlayDisplayConfig(1280, 720)),
            AirPlayIdentity(ByteArray(32), byteArrayOf(1), "p"),
        )
        return try {
            Result.success(CarPlayBonjour::class.java
                .getDeclaredMethod("probeOnce", InetAddress::class.java, Int::class.java, Int::class.java)
                .apply { isAccessible = true }
                .invoke(bonjour, loopback, server.localPort, 1) as String)
        } catch (error: java.lang.reflect.InvocationTargetException) {
            Result.failure(error.targetException)
        } finally {
            bonjour.close(); server.close(); worker.join(5000)
        }
    }

    @Test fun endlessLineWithoutNewlineIsRejectedBeforeTheReadTimeout() {
        val sent = AtomicLong()
        val started = System.nanoTime()
        val result = probe { socket ->
            val chunk = ByteArray(8192) { 'a'.code.toByte() }
            repeat(8192) {
                socket.getOutputStream().write(chunk)
                sent.addAndGet(chunk.size.toLong())
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val error = result.exceptionOrNull()
        assertTrue("expected an IOException, but the probe returned a ${result.getOrNull()?.length}-char line (server sent ${sent.get()} bytes)", error is IOException)
        assertFalse("read ${sent.get()} bytes then timed out after ${elapsedMs} ms", error is SocketTimeoutException)
    }

    @Test fun realStatusLineIsReturned() {
        val result = probe { it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
        assertEquals("HTTP/1.1 200 OK", result.getOrThrow())
    }

    @Test fun statusLineWithLongReasonPhraseIsReturned() {
        val line = "HTTP/1.1 200 " + "x".repeat(2000)
        assertEquals(line, probe { it.getOutputStream().write("$line\r\n".toByteArray()) }.getOrThrow())
    }
}
