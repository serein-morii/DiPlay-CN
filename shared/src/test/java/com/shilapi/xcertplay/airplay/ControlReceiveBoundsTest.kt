package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ControlReceiveBoundsTest {
    private fun request(contentLength: Int, body: ByteArray = ByteArray(0)): ByteArray =
        ("POST /x RTSP/1.0\r\nCSeq: 1\r\nContent-Length: $contentLength\r\n\r\n")
            .toByteArray(Charsets.US_ASCII) + body

    private fun assertRejected(buffer: ByteArray) {
        try {
            RtspMessage.parseMessages(buffer)
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
        }
    }

    @Test fun parserRejectsHugeContentLength() = assertRejected(request(1_500_000_000))

    @Test fun parserRejectsHeaderWithoutTerminator() =
        assertRejected("GET /info RTSP/1.0\r\nX: ".toByteArray() + ByteArray(64 * 1024) { 'a'.code.toByte() })

    @Test fun parserKeepsRealSizedMessages() {
        val setup = request(756, ByteArray(756) { it.toByte() })
        val mask = ByteArray(IPhoneCornerMaskWireLimit.MAX_PNG_BYTES + 64 * 1024) { it.toByte() }
        val parsed = RtspMessage.parseMessages(setup + request(mask.size, mask) + "GET /in".toByteArray())
        assertEquals(2, parsed.messages.size)
        assertEquals(756, parsed.messages[0].body.size)
        assertArrayEquals(mask, parsed.messages[1].body)
        assertEquals("GET /in", String(parsed.rest))
    }

    @Test fun parserWaitsForPartialBodyUnderTheCap() {
        val parsed = RtspMessage.parseMessages(request(756, ByteArray(100)))
        assertTrue(parsed.messages.isEmpty())
    }

    @Test fun controlConnectionClosesOnHugeContentLength() {
        withControlPeer { client ->
            client.getOutputStream().apply { write(request(1_500_000_000)); flush() }
            assertClosedWithin(client)
        }
    }

    @Test fun controlConnectionClosesWhenHeaderNeverEnds() {
        withControlPeer { client ->
            val out = client.getOutputStream()
            val chunk = ByteArray(16 * 1024) { 'a'.code.toByte() }
            out.write("GET /info RTSP/1.0\r\nX: ".toByteArray())
            try {
                repeat(256) { out.write(chunk) }
            } catch (_: java.io.IOException) {
                // The server closed first, which is the wanted result.
            }
            assertClosedWithin(client)
        }
    }

    @Test fun controlConnectionStaysOpenAndAnswersRealSizedRequest() {
        withControlPeer { client ->
            client.soTimeout = 5000 // a silent server must fail the test, not hang it
            client.getOutputStream().apply { write(request(756, ByteArray(756))); flush() }
            val status = ByteArray(15)
            client.getInputStream().readNBytes(status, 0, status.size)
            assertEquals("RTSP/1.0 ", String(status, 0, 9))
        }
    }

    private fun assertClosedWithin(client: Socket) {
        client.soTimeout = 3000
        try {
            val read = client.getInputStream().read()
            assertEquals("expected EOF", -1, read)
        } catch (_: SocketTimeoutException) {
            fail("control connection still open 3 s after an oversized request")
        } catch (_: java.io.IOException) {
            // Reset by peer counts as closed.
        }
    }

    private fun withControlPeer(block: (Socket) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val client = Socket(InetAddress.getByName("127.0.0.1"), server.localPort)
        val accepted = server.accept()
        val session = AirPlaySession(
            socket = accepted,
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                main = AirPlayDisplayConfig(1280, 720)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
        )
        try {
            session.start()
            block(client)
        } finally {
            session.close()
            client.close()
            server.close()
        }
    }
}
