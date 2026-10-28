package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.VehicleGear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcarxWheelSpeedTest {
    @Test fun ecarxGearEventsMapToTheSelectorPositions() {
        assertEquals(VehicleGear.PARK, EcarxWheelSpeed.gear(2097712))
        assertEquals(VehicleGear.REVERSE, EcarxWheelSpeed.gear(2097728))
        assertEquals(VehicleGear.NEUTRAL, EcarxWheelSpeed.gear(2097680))
        assertEquals(VehicleGear.DRIVE, EcarxWheelSpeed.gear(2097696))
        assertEquals(VehicleGear.DRIVE, EcarxWheelSpeed.gear(2097665)) // GEAR_FIRST, manual mode
        assertEquals(VehicleGear.DRIVE, EcarxWheelSpeed.gear(2097674)) // GEAR_TENTH
        assertNull(EcarxWheelSpeed.gear(-1)) // GEAR_UNKNOWN
        assertNull(EcarxWheelSpeed.gear(2097664))
    }

    @Test fun theRawSpeedIsTakenAsMetersPerSecond() {
        assertEquals(0.0, EcarxWheelSpeed.metersPerSecond(0f)!!, 0.0)
        assertEquals(16.5, EcarxWheelSpeed.metersPerSecond(16.5f)!!, 1e-6)
        assertNull(EcarxWheelSpeed.metersPerSecond(-1f))
        assertNull(EcarxWheelSpeed.metersPerSecond(Float.NaN))
        assertNull(EcarxWheelSpeed.metersPerSecond(500f))
    }
}
