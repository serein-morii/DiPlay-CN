package com.shilapi.xcertplay.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothLinkCheckTest {
    // Logged on two head units where nothing ever arrived: an Allwinner T8 and the unit in issue 354.
    @Test fun anInstantConnectThatStaysSilentLooksUnreal() {
        for (millis in listOf(4L, 6L, 8L, 13L, 17L, 49L)) {
            assertTrue("$millis ms", BluetoothLinkCheck.looksUnreal(connectMillis = millis, bytesReceived = 0))
        }
    }

    // Logged on a head unit where wireless CarPlay works (report attached to issue 274).
    @Test fun aConnectThatTookAsLongAsARealOneDoesNot() {
        for (millis in listOf(50L, 100L, 111L, 117L, 162L, 407L, 2206L)) {
            assertFalse("$millis ms", BluetoothLinkCheck.looksUnreal(connectMillis = millis, bytesReceived = 0))
        }
    }

    @Test fun oneByteFromThePhoneSettlesIt() {
        assertFalse(BluetoothLinkCheck.looksUnreal(connectMillis = 6, bytesReceived = 1))
    }

    // On the Allwinner T8 a connect to a random service UUID "succeeded" in 12 ms.
    @Test fun onlyAnInstantControlConnectProvesTheLinkUnreal() {
        assertTrue(BluetoothLinkCheck.controlProvesUnreal(controlConnected = true, controlMillis = 12))
        // What a working stack does: the service is not found, however quickly that is known.
        assertFalse(BluetoothLinkCheck.controlProvesUnreal(controlConnected = false, controlMillis = 12))
        assertFalse(BluetoothLinkCheck.controlProvesUnreal(controlConnected = false, controlMillis = 3_000))
        // A connect that took as long as a real one is not evidence either way.
        assertFalse(BluetoothLinkCheck.controlProvesUnreal(controlConnected = true, controlMillis = 400))
    }
}
