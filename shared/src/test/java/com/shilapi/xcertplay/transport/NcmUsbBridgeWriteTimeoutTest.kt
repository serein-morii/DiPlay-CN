package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** A NAKed NCM bulk OUT must not hold a USB transfer for the full timeout before the session (#634). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [NcmWriteConnectionShadow::class])
class NcmUsbBridgeWriteTimeoutTest {
    @Before fun reset() { NcmWriteReplay.reset() }

    @Test fun framesBeforeThePhoneTakesOneUseTheShortTimeoutThenTheCallers() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, NcmWriteReplay.ALL, NcmWriteReplay.ALL))
        val diagnostics = withBridge { ncm -> repeat(4) { ncm.send(frame, 2_000) } }
        val short = NcmUsbBridge.NOT_READY_WRITE_TIMEOUT_MILLIS
        assertEquals(listOf(short, short, short, 2_000), NcmWriteReplay.timeouts)
        assertEquals(listOf("NCM bulk-out ready after 2 dropped frames"), diagnostics)
    }

    @Test fun theLinkOpensWithTheFirstFrameThePhoneTakes() {
        NcmWriteReplay.results.addAll(listOf(-1, -1, NcmWriteReplay.ALL))
        val opened = mutableListOf<Long?>()
        withBridge { ncm -> repeat(3) { ncm.send(frame, 2_000); opened += ncm.linkOpenedAtNanos } }
        assertNull(opened[0])
        assertNull(opened[1])
        assertNotNull(opened[2])
    }

    @Test fun aTimeoutAfterThePipeWasReadyKeepsTheCallersTimeout() {
        NcmWriteReplay.results.addAll(listOf(NcmWriteReplay.ALL, -1, NcmWriteReplay.ALL))
        withBridge { ncm -> repeat(3) { ncm.send(frame, 2_000) } }
        assertEquals(listOf(NcmUsbBridge.NOT_READY_WRITE_TIMEOUT_MILLIS, 2_000, 2_000), NcmWriteReplay.timeouts)
    }

    @Test fun aShorterCallerTimeoutIsKept() {
        NcmWriteReplay.results.add(-1)
        withBridge { ncm -> ncm.send(frame, 5) }
        assertEquals(listOf(5), NcmWriteReplay.timeouts)
    }

    private fun withBridge(block: (NcmUsbBridge) -> Unit): List<String> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val diagnostics = mutableListOf<String>()
        val ncm = NcmUsbBridge(connection, endpoint(0x06), endpoint(0x88), null, emptyList(), null, diagnostics::add)
        try { block(ncm) } finally { ncm.close() }
        return diagnostics
    }

    private companion object {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
    }
}

object NcmWriteReplay {
    const val ALL = Int.MAX_VALUE
    /** One result per bulkTransfer: a byte count, or [ALL] for the whole frame. */
    val results = ArrayDeque<Int>()
    val timeouts = mutableListOf<Int>()
    fun reset() { results.clear(); timeouts.clear() }
}

@Implements(UsbDeviceConnection::class)
class NcmWriteConnectionShadow {
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray, length: Int, timeout: Int): Int {
        NcmWriteReplay.timeouts += timeout
        return NcmWriteReplay.results.pollFirst()?.takeIf { it != NcmWriteReplay.ALL } ?: length
    }
    @Implementation fun close() = Unit
}
