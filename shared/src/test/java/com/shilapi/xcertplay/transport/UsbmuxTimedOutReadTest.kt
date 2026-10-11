package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * A USBMUX read that completes between its timeout and the cancel ended the whole session every
 * few minutes on a Telechips head unit (#610). The read is drained and its bytes kept instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [CompatibilityUsbConnectionShadow::class, CompatibilityUsbRequestShadow::class])
class UsbmuxTimedOutReadTest {
    @Before fun reset() { UsbQueueReplay.reset() }

    @Test fun aReadThatCompletesAfterItsTimeoutKeepsTheSessionAndItsBytes() {
        UsbQueueReplay.timeouts = 1
        UsbQueueReplay.cancelResult = false
        val pipe = pipe()
        try {
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
            // The pipe stays usable afterwards.
            UsbQueueReplay.cancelResult = true
            assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(100))
        } finally { pipe.close() }
    }

    @Test fun aCancelledReadWithoutBytesIsAnOrdinaryTimeout() {
        UsbQueueReplay.timeouts = 1
        UsbQueueReplay.emptyCompletion = true
        val pipe = pipe()
        try { assertNull(pipe.read(100)) } finally { pipe.close() }
        assertTrue(UsbQueueReplay.cancels >= 1)
    }

    @Test fun aReadThatNeitherCancelsNorCompletesStillFails() {
        UsbQueueReplay.timeouts = 2
        UsbQueueReplay.cancelResult = false
        val pipe = pipe()
        try {
            pipe.read(100)
            fail("Expected the stuck read to end the session")
        } catch (error: IphoneUsbException.DeviceUnavailable) {
            assertEquals("Android could not cancel timed out USBMUX read request", error.message)
        } finally { pipe.close() }
    }

    private fun pipe() = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))
}
