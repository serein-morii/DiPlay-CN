package com.shilapi.xcertplay.iap2.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A real 0x5204 payload captured on the car (Apple Maps, three lanes, the third recommended):
 *
 * <pre>
 * id 0 component 42
 * id 1 lane guidance index 0
 * id 2 lane 0: index 0, status 0, angle -90
 * id 2 lane 1: index 1, status 0, angle 0
 * id 2 lane 2: index 2, status 2, angle 0, angle-highlight 90
 * </pre>
 */
class Iap2LaneGuidanceTest {

    private val sample = hex(
        "00 06 00 00 00 2a" +
            " 00 06 00 01 00 00" +
            " 00 15 00 02 00 06 00 00 00 00 00 05 00 01 00 00 06 00 02 ff a6" +
            " 00 15 00 02 00 06 00 00 00 01 00 05 00 01 00 00 06 00 02 00 00" +
            " 00 1b 00 02 00 06 00 00 00 02 00 05 00 01 02 00 06 00 02 00 00" +
            " 00 06 00 03 00 5a",
    )

    @Test
    fun theCapturedPayloadHasThreeLanes() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(3, lanes.size)
        assertEquals(listOf(0, 1, 2), lanes.map { it.index })
    }

    @Test
    fun anglesAreSignedDegrees() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(-90, lanes[0].angle)
        assertEquals(0, lanes[1].angle)
        assertEquals(0, lanes[2].angle)
    }

    @Test
    fun theThirdLaneIsRecommendedAndHighlighted() {
        val lanes = Iap2LaneGuidance.parse(sample)
        assertEquals(0, lanes[0].status)
        assertEquals(0, lanes[1].status)
        assertEquals(2, lanes[2].status)
        assertEquals(true, lanes[2].recommended)
        assertEquals(90, lanes[2].highlightAngle)
        assertEquals(null, lanes[0].highlightAngle)
    }

    @Test
    fun aMalformedPayloadYieldsNoLanes() {
        assertEquals(emptyList<LaneGuidanceLane>(), Iap2LaneGuidance.parse(byteArrayOf(0, 1, 2)))
    }

    private fun hex(text: String): ByteArray =
        text.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
