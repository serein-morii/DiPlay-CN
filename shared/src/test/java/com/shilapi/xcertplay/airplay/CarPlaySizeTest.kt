package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlaySizeTest {
    @Test
    fun `presets map back to themselves`() {
        CarPlaySize.entries.forEach { assertEquals(it, CarPlaySize.exact(it.widthMillimeters)) }
    }

    @Test
    fun `only the three preset widths count as a preset`() {
        assertNull(CarPlaySize.exact(200))
        assertNull(CarPlaySize.exact(275))
        assertNull(CarPlaySize.exact(400))
    }

    @Test
    fun `presets fit the reported width range`() {
        CarPlaySize.entries.forEach {
            assertEquals(it.widthMillimeters, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(it.widthMillimeters))
        }
    }

    @Test
    fun `physical length moves in 5 mm steps`() {
        assertEquals(275, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(275))
        assertEquals(275, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(277))
        assertEquals(280, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(278))
        assertEquals(25, AirPlayDisplaySettings.widthPhysicalMmProgress(225))
    }

    @Test
    fun `lengths saved with the earlier 25 mm steps stay as they were`() {
        (AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM step 25).forEach {
            assertEquals(it, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(it))
        }
    }
}
