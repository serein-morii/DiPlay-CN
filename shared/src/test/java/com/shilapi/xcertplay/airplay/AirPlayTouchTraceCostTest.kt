package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every touch report is a POST /command; its TRACE text is a hex dump that nobody may want. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AirPlayTouchTraceCostTest {
    private class Recorder(private val wants: Boolean) : AirPlaySessionListener {
        val traces = mutableListOf<String>()
        override val wantsTrace: Boolean get() = wants
        override fun onDebugLog(message: String) { if (message.startsWith("TRACE ")) traces += message }
    }

    @Test fun listenerThatDeclinesTraceCostsNoTraceTextForADrag() {
        val listener = Recorder(wants = false)
        drag(listener, MOVES)
        assertEquals("trace chars built for ${MOVES + 2} touch events", 0, listener.traces.sumOf { it.length })
    }

    @Test fun listenerThatWantsTraceStillGetsTheSameHexDump() {
        val listener = Recorder(wants = true)
        drag(listener, MOVES)
        assertEquals(MOVES + 2, listener.traces.size)
        val first = listener.traces.first()
        assertTrue(first, first.startsWith("TRACE airplay event tx headers=POST /command RTSP/1.0\r\n"))
        assertTrue(first, first.contains("CSeq: 1\r\n\r\n bodyHex="))
        val hex = first.substringAfter("bodyHex=")
        val body = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val command = BplistCodec.decode(body) as Map<*, *>
        assertEquals("hidSendReport", command["type"])
        assertTrue(listener.traces.last().contains("CSeq: ${MOVES + 2}\r\n"))
    }

    private fun drag(listener: AirPlaySessionListener, moves: Int) {
        val session = session(listener)
        try {
            session.sendTouch(listOf(AirPlayContact(1, 0.5, 0.5, true)))
            repeat(moves) { session.sendTouch(listOf(AirPlayContact(1, 0.5 + it * 0.001, 0.5, true))) }
            session.sendTouch(emptyList())
        } finally {
            session.close()
        }
    }

    private fun session(listener: AirPlaySessionListener): AirPlaySession {
        val session = AirPlaySession(
            Socket(),
            AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0", main = AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity.generate(), PairingStore(), null, listener, object : AirPlayMediaHandler {},
        )
        val sink = object : Socket() {
            private val out = ByteArrayOutputStream()
            override fun getOutputStream() = out
        }
        set(session, "eventSocket", sink)
        set(session, "eventCipher", ControlCipher(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        return session
    }

    private fun set(session: AirPlaySession, name: String, value: Any) {
        AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }

    private companion object { const val MOVES = 98 }
}
