package com.shilapi.xcertplay.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class AdbPacketTest {
    @Test fun aPacketRoundTrips() {
        val payload = byteArrayOf(1, 2, 3)
        val decoded = AdbPacket.read(ByteArrayInputStream(AdbPacket(AdbPacket.CNXN, 1, 2, payload).encode()))
        assertEquals(AdbPacket.CNXN, decoded.command)
        assertEquals(1, decoded.arg0)
        assertEquals(2, decoded.arg1)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test(timeout = 2_000) fun aStreamThatNeverMakesProgressIsRejected() {
        val stalled = object : InputStream() {
            override fun read(): Int = 0
            override fun read(destination: ByteArray, offset: Int, length: Int): Int = 0
        }
        val error = runCatching { AdbPacket.read(stalled) }.exceptionOrNull()
        assertTrue("expected an IOException, got $error", error is IOException)
    }
}
