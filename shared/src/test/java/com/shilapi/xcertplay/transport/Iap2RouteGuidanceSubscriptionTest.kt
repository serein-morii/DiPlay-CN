package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2RouteGuidanceSubscriptionTest {
    private fun config() = Iap2IdentificationConfig(
        name = "xcertplay",
        modelIdentifier = "xcertplay",
        manufacturer = "xcertplay",
        serialNumber = "xcertplay",
        firmwareVersion = "1.0.0",
        hardwareVersion = "1.0",
        carPlayUsbInterfaceNumber = 3,
    )

    @Test
    fun identificationDeclaresRouteGuidanceDisplayComponent() {
        val parameters = Iap2ParameterList.parse(
            Iap2IdentificationClient.identificationInformation(config()).payload,
        ).asList()
        val component = Iap2ParameterList.parse(parameters.single { it.id == 30 }.payload).asList()
        assertArrayEquals(byteArrayOf(0, 42), component.single { it.id == 0 }.payload)
        assertArrayEquals("RouteGuidance\u0000".encodeToByteArray(), component.single { it.id == 1 }.payload)
        assertArrayEquals(byteArrayOf(0, 8), component.single { it.id == 6 }.payload)
        // Without these two the phone never sends 0x5204 lane guidance at all (#927).
        assertArrayEquals(byteArrayOf(0, 64), component.single { it.id == 7 }.payload)
        assertArrayEquals(byteArrayOf(0, 8), component.single { it.id == 8 }.payload)
    }

    @Test
    fun laneGuidanceUpdatesAreDeclaredToThePhone() {
        val parameters = Iap2ParameterList.parse(
            Iap2IdentificationClient.identificationInformation(config()).payload,
        ).asList()
        // Parameter 7 is the list of messages the accessory can receive, two bytes each.
        val received = parameters.single { it.id == 7 }.payload.let { payload ->
            (payload.indices step 2).map { i ->
                ((payload[i].toInt() and 0xff) shl 8) or (payload[i + 1].toInt() and 0xff)
            }
        }

        assertTrue(received.contains(0x5204))
    }

    @Test
    fun startRouteGuidanceUpdatesRequestsDeclaredComponent() {
        val frame = Iap2ControlMessages.subscriptions().single { it.messageId == 0x5200 }

        assertEquals(0x5200, frame.messageId)
        assertArrayEquals(
            byteArrayOf(0, 6, 0, 0, 0, 42, 0, 4, 0, 1, 0, 4, 0, 2, 0, 4, 0, 3),
            frame.payload,
        )
    }
}
