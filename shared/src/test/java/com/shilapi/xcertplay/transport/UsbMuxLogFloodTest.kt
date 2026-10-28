package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** A wired session sends and receives thousands of ACKs and large writes. Logcat must not fill. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE,
    shadows = [EvidenceUsbConnectionShadow::class, EvidenceUsbRequestShadow::class])
class UsbMuxLogFloodTest {
    @Before fun reset() {
        UsbEvidenceReplay.reset()
        ShadowLog.clear()
    }

    @Test fun pureAckFramesDoNotLogOneLineEach() {
        UsbEvidenceReplay.transfers.add(versionReply)
        repeat(FRAMES) { UsbEvidenceReplay.transfers.add(pureAck(it)) }
        val host = Iap2UsbMuxHost.open(pipe())
        awaitReads(FRAMES + 1)
        host.close()
        val lines = lines("usbmux TCP control")
        println("RX pure-ACK frames=$FRAMES logged=$lines")
        assertTrue("logged $lines lines for $FRAMES pure ACK frames", lines <= MAX_LINES)
    }

    @Test fun largeWritesDoNotLogOneLineEach() {
        UsbEvidenceReplay.transfers.add(versionReply)
        val host = Iap2UsbMuxHost.open(pipe())
        repeat(FRAMES) { host.sendTcp(1, 2, it, 0, 0x18, ByteArray(600)) }
        host.close()
        val lines = lines("usbmux TX")
        println("TX writes=$FRAMES logged=$lines")
        assertTrue("logged $lines lines for $FRAMES large writes", lines <= MAX_LINES)
    }

    @Test fun theFirstFramesOfEachKindAreStillLogged() {
        UsbEvidenceReplay.transfers.add(versionReply)
        UsbEvidenceReplay.transfers.add(pureAck(0))
        val host = Iap2UsbMuxHost.open(pipe())
        awaitReads(2)
        host.sendTcp(1, 2, 0, 0, 0x18, ByteArray(600))
        host.close()
        assertEquals(1, lines("usbmux TCP control"))
        assertEquals(1, lines("usbmux TX"))
    }

    @Test fun resetAndFinFramesAreLoggedAfterTheAckCapIsReached() {
        UsbEvidenceReplay.transfers.add(versionReply)
        repeat(60) { UsbEvidenceReplay.transfers.add(pureAck(it)) }
        UsbEvidenceReplay.transfers.add(pureAck(60).also { it[29] = 0x04 })
        UsbEvidenceReplay.transfers.add(pureAck(61).also { it[29] = 0x01 })
        val host = Iap2UsbMuxHost.open(pipe())
        awaitReads(63)
        host.close()
        val control = ShadowLog.getLogsForTag("xcertplay-usb").map { it.msg }
            .filter { it.startsWith("usbmux TCP control") }
        val acks = control.count { it.contains("flags=16 ") }
        println("control lines=${control.size} acks=$acks rst=${control.count { it.contains("flags=4 ") }} fin=${control.count { it.contains("flags=1 ") }}")
        assertTrue("pure ACK lines $acks exceed the cap", acks <= MAX_LINES)
        assertEquals("RST line", 1, control.count { it.contains("flags=4 ") })
        assertEquals("FIN line", 1, control.count { it.contains("flags=1 ") })
    }

    private fun lines(prefix: String) =
        ShadowLog.getLogsForTag("xcertplay-usb").count { it.msg.startsWith(prefix) }

    private fun awaitReads(count: Int) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (UsbEvidenceReplay.completedReads < count && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals(count, UsbEvidenceReplay.completedReads)
    }

    // proto 6, length 36, magic, mux seq, mux ack; TCP: ports, seq, ack, offset 5, ACK flag, window.
    private fun pureAck(sequence: Int): ByteArray = hex(
        "00 00 00 06 00 00 00 24 fa ce fa ce 00 00 00 00 " +
            "f2 7e 00 01 00 00 00 00 00 00 00 01 50 10 02 00 00 00 00 00").also { it[15] = sequence.toByte() }

    private fun pipe(): Iap2UsbSession {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        return Iap2UsbSession(connection, endpoint(0x04), endpoint(0x85))
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val FRAMES = 200
        const val MAX_LINES = 50
        val versionReply = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 20, 0, 0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}
