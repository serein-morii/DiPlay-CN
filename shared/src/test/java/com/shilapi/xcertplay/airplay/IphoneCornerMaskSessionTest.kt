package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class IphoneCornerMaskSessionTest {
    private val sample = ByteArray(96) { it.toByte() }

    private fun session(native: Boolean, received: MutableList<Pair<ByteArray, Int>>): AirPlaySession =
        AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "366.0",
                main = AirPlayDisplayConfig(1280, 720, mainDisplayCornerMasks = true,
                    useIphoneCornerMask = native)),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {
                override fun onCornerMask(session: AirPlaySession, png: ByteArray, displayWidth: Int) {
                    received += png to displayWidth
                }
            },
            media = object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? =
                    if (type == 110) 43210 else null
            },
        )

    private fun handle(session: AirPlaySession, method: String, payload: Map<String, Any?>): RtspMessage.Response =
        AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(session,
                RtspMessage.Request(method, "rtsp://test", "RTSP/1.0", emptyMap(),
                    BplistCodec.encode(payload))) as RtspMessage.Response

    @Test fun receivesMaskFromMainScreenSetupOnlyWhenExplicitlyEnabled() {
        for (native in listOf(false, true)) {
            val seen = mutableListOf<Pair<ByteArray, Int>>()
            val session = session(native, seen)
            try {
                val response = handle(session, "SETUP", mapOf("streams" to listOf(
                    mapOf("type" to 110, "topLeftCornerMask" to sample))))
                assertEquals(200, response.status ?: 200)
                if (native) {
                    assertEquals(1, seen.size)
                    assertEquals(1280, seen.single().second)
                    assertArrayEquals(sample, seen.single().first)
                    assertNotSame(sample, seen.single().first)
                } else assertTrue(seen.isEmpty())
            } finally { session.close() }
        }
    }

    @Test fun receivesRuntimeMaskUpdateAndIgnoresMalformedOrOversizedData() {
        val seen = mutableListOf<Pair<ByteArray, Int>>()
        val session = session(true, seen)
        try {
            handle(session, "SET_PARAMETER", mapOf("update" to mapOf("topLeftCornerMask" to sample)))
            assertEquals(1, seen.size)
            assertArrayEquals(sample, seen.single().first)
            handle(session, "SET_PARAMETER", mapOf("update" to mapOf("unrelated" to true)))
            assertEquals(1, seen.size)
            handle(session, "SET_PARAMETER", mapOf("topLeftCornerMask" to ByteArray(
                IPhoneCornerMaskWireLimit.MAX_PNG_BYTES + 1)))
            assertEquals(1, seen.size)
        } finally { session.close() }
    }
}
