package com.shilapi.xcertplay.adb

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAdbTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun packetsRoundTripWithChecksumAndMagic() {
        val bytes = AdbPacket(AdbPacket.OPEN, 1, 0, "shell:id\u0000".toByteArray()).encode()
        val header = ByteBuffer.wrap(bytes, 0, 24).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(AdbPacket.OPEN, header.int)
        header.int; header.int
        assertEquals(9, header.int)
        assertEquals("shell:id\u0000".toByteArray().sumOf { it.toInt() }, header.int)
        assertEquals(AdbPacket.OPEN.inv(), header.int)
        val read = AdbPacket.read(ByteArrayInputStream(bytes))
        assertArrayEquals("shell:id\u0000".toByteArray(), read.payload)
    }

    @Test
    fun publicKeyUsesTheAdbdLayout() {
        val message = String(AdbKeys.publicKeyMessage(key.public), Charsets.UTF_8)
        assertTrue(message.endsWith(" diplay@headunit\u0000"))
        val blob = ByteBuffer.wrap(Base64.getDecoder().decode(message.substringBefore(' '))).order(ByteOrder.LITTLE_ENDIAN)
        val n = (key.public as RSAPublicKey).modulus

        assertEquals(524, blob.capacity())
        assertEquals(64, blob.int)
        // n0inv * n == -1 (mod 2^32)
        val r32 = BigInteger.ONE.shiftLeft(32)
        assertEquals(r32 - BigInteger.ONE, BigInteger.valueOf(blob.int.toLong() and 0xffffffffL).multiply(n).mod(r32))
        assertEquals(n, littleEndian(blob, 256))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n), littleEndian(blob, 256))
        assertEquals(65537, blob.int)
    }

    @Test
    fun knownKeyRunsShellCommands() {
        val adbd = FakeAdbd(key, knowsKey = true, output = "Result: Parcel(00000000 00000002   '........')")
        LocalAdb(key, port = adbd.port).use { adb ->
            assertEquals(LocalAdb.Access.READY, adb.connect(mayAsk = false))
            assertEquals("Result: Parcel(00000000 00000002   '........')", adb.shell("service call autoservice 5"))
        }
        assertEquals(listOf("shell:service call autoservice 5"), adbd.opened)
    }

    @Test
    fun unknownKeyIsOfferedOnlyWhenAsking() {
        val quiet = FakeAdbd(key, knowsKey = false, approves = true)
        LocalAdb(key, port = quiet.port).use { assertEquals(LocalAdb.Access.NOT_APPROVED, it.connect(mayAsk = false)) }
        assertFalse(quiet.offeredKey)

        val asking = FakeAdbd(key, knowsKey = false, approves = true)
        LocalAdb(key, port = asking.port).use { assertEquals(LocalAdb.Access.READY, it.connect(mayAsk = true)) }
        assertTrue(asking.offeredKey)
    }

    @Test
    fun closedPortIsReportedAsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        LocalAdb(key, port = port).use { assertEquals(LocalAdb.Access.UNREACHABLE, it.connect(mayAsk = true)) }
    }

    @Test fun cancellationUnblocksAStalledShellAndPreventsImplicitReconnect() {
        val server = ServerSocket(0)
        val entered = CountDownLatch(1)
        val adbd = thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                AdbPacket.read(input)
                socket.getOutputStream().write(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION,
                    AdbPacket.MAX_PAYLOAD, "device::\u0000".toByteArray()).encode())
                AdbPacket.read(input) // OPEN, then deliberately leave the shell read stalled.
                entered.countDown()
                while (input.read() != -1) { }
            }
            server.close()
        }
        LocalAdb(key, port = server.localPort).use { adb ->
            assertEquals(LocalAdb.Access.READY, adb.connect(mayAsk = false))
            var result: String? = "pending"
            val command = thread { result = adb.shell("cmd connectivity help") }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            adb.cancelPendingOperations()
            command.join(1_000)
            assertFalse("Cancellation must not wait for the five-second read timeout", command.isAlive)
            assertNull(result)
            assertEquals(LocalAdb.Access.UNREACHABLE, adb.connect(mayAsk = false))
            assertNull(adb.shell("cmd connectivity start-tethering wifi"))
        }
        adbd.join(1_000)
    }

    private fun littleEndian(buffer: ByteBuffer, size: Int): BigInteger {
        val bytes = ByteArray(size).also { buffer.get(it) }
        return BigInteger(1, bytes.reversedArray())
    }
}
