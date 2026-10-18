package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSpeedNmeaTest {
    @Test
    fun encodesLikeARealHeadUnit() {
        // From a production head unit's iAP2 log: parked, two samples 0.16 s apart.
        val reading = VehicleSpeedReading(
            VehicleGear.PARK,
            listOf(VehicleSpeedSample(17_877_092, 0.0), VehicleSpeedSample(17_877_252, 0.0)),
        )

        assertEquals("\$PASCD,17877.092,C,P,0,2,0.00,0.000,0.16,0.000*54\r\n", PascdEncoder.encode(reading))
    }

    @Test
    fun encodesSpeedGearAndChecksum() {
        val reading = VehicleSpeedReading(
            VehicleGear.REVERSE,
            listOf(VehicleSpeedSample(1_000, 1.5), VehicleSpeedSample(1_250, 1.25)),
        )

        val sentence = PascdEncoder.encode(reading)!!.trimEnd()
        assertTrue(sentence.startsWith("\$PASCD,1.000,C,R,0,2,0.00,1.500,0.25,1.250*"))
        val star = sentence.lastIndexOf('*')
        assertEquals(checksum(sentence.substring(1, star)), sentence.substring(star + 1))
        assertNull(PascdEncoder.encode(VehicleSpeedReading(VehicleGear.DRIVE, emptyList())))
    }

    @Test
    fun addsSpeedOnlyWhenTheIphoneAsksForIt() {
        val speed = FakeSpeed()
        val provider = VehicleSpeedLocationProvider(FakePosition("\$GPGGA\r\n"), speed)

        provider.onRequested(setOf(1, 2))
        provider.start()
        assertEquals("\$GPGGA\r\n", provider.latestNmea())
        assertFalse(speed.started)

        provider.onRequested(setOf(1, 2, Iap2LocationMessages.VEHICLE_SPEED_DATA))
        provider.start()
        assertTrue(speed.started)
        assertTrue(provider.latestNmea()!!.startsWith("\$GPGGA\r\n\$PASCD,"))
        provider.stop()
        assertFalse(speed.started)
    }

    @Test
    fun keepsSendingSpeedWithoutAPositionFix() {
        val provider = VehicleSpeedLocationProvider(FakePosition(null), FakeSpeed())

        provider.onRequested(setOf(Iap2LocationMessages.VEHICLE_SPEED_DATA))
        assertTrue(provider.start())
        assertTrue(provider.latestNmea()!!.startsWith("\$PASCD,"))
    }

    // #807: in a tunnel the speed went after about two minutes, and the report could not say why.
    @Test
    fun summarisesWhatReachedTheIphoneOnceAMinute() {
        var now = 1_000L
        val logs = mutableListOf<String>()
        val fixes = mutableListOf<String?>("\$GPGGA\r\n", "\$GPGGA\r\n", null, null)
        val position = object : Iap2LocationProvider {
            override fun start() = true
            override fun stop() = Unit
            override fun latestNmea() = fixes.removeAt(0)
        }
        val provider = VehicleSpeedLocationProvider(position, FakeSpeed(), logs::add, { now })
        provider.onRequested(setOf(Iap2LocationMessages.VEHICLE_SPEED_DATA))
        repeat(3) { provider.latestNmea(); now += 20_000L }
        assertTrue(logs.isEmpty())
        provider.latestNmea()
        assertEquals(listOf("Location: last 60 s updates=4 withPosition=2 withWheelSpeed=4 wheelSpeedSamples=4 " +
            "lastWheelSpeed=50.0 km/h speedRequested=true"), logs)
    }

    @Test
    fun readsTheRequestedSentenceTypes() {
        val request = Iap2Messages.buildRaw(Iap2LocationMessages.START_LOCATION_INFORMATION) {
            void(1)
            void(2)
            void(4)
            u32(0x8004, 100)
        }

        assertEquals(setOf(1, 2, 4, 0x8004), Iap2LocationMessages.requestedComponents(request))
    }

    @Test
    fun identificationOffersSpeedOnlyWhenEnabled() {
        assertFalse(20 in locationComponentIds(vehicleSpeedEnabled = false))
        assertTrue(20 in locationComponentIds(vehicleSpeedEnabled = true))
    }

    private fun locationComponentIds(vehicleSpeedEnabled: Boolean): List<Int> {
        val config = Iap2IdentificationConfig(
            name = "xcertplay",
            modelIdentifier = "xcertplay",
            manufacturer = "xcertplay",
            serialNumber = "xcertplay",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = true,
            vehicleSpeedEnabled = vehicleSpeedEnabled,
        )
        val parameters = Iap2ParameterList.parse(Iap2IdentificationClient.identificationInformation(config).payload)
        return Iap2ParameterList.parse(parameters.first(22)!!.payload).asList().map { it.id }
    }

    private fun checksum(body: String): String {
        var value = 0
        for (character in body) value = value xor character.code
        return "%02X".format(value)
    }

    private class FakePosition(private val nmea: String?) : Iap2LocationProvider {
        override fun start() = nmea != null
        override fun stop() = Unit
        override fun latestNmea() = nmea
    }

    private class FakeSpeed : VehicleSpeedSource {
        var started = false
        override fun start() { started = true }
        override fun stop() { started = false }
        override fun drain() = VehicleSpeedReading(VehicleGear.DRIVE, listOf(VehicleSpeedSample(5_000, 13.9)))
    }
}
