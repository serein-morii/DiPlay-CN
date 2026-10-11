package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class FceManeuverCodesTest {
    private fun icon(type: Int, side: Int = 0) = FceManeuverCodes.icon(type, side)

    @Test
    fun `turns map to the cluster arrows`() {
        assertEquals(FceClusterIcons.LEFT, icon(1))
        assertEquals(FceClusterIcons.RIGHT, icon(2))
        assertEquals(FceClusterIcons.LEFT, icon(20))
        assertEquals(FceClusterIcons.RIGHT, icon(21))
        assertEquals(FceClusterIcons.SHARP_LEFT, icon(47))
        assertEquals(FceClusterIcons.SHARP_RIGHT, icon(48))
        assertEquals(FceClusterIcons.SLIGHT_LEFT, icon(49))
        assertEquals(FceClusterIcons.SLIGHT_RIGHT, icon(50))
    }

    @Test
    fun `keep and highway changes use the slight arrows`() {
        listOf(13, 22, 52).forEach { assertEquals(FceClusterIcons.SLIGHT_LEFT, icon(it)) }
        listOf(14, 23, 53).forEach { assertEquals(FceClusterIcons.SLIGHT_RIGHT, icon(it)) }
    }

    @Test
    fun `u-turn follows the driving side`() {
        listOf(4, 18, 19, 26).forEach {
            assertEquals(FceClusterIcons.U_TURN_LEFT, icon(it, side = 0))
            assertEquals(FceClusterIcons.U_TURN_RIGHT, icon(it, side = 1))
        }
    }

    @Test
    fun `roundabout exits 1 to 10 use the numbered glyphs`() {
        assertEquals(42, icon(28))
        assertEquals(44, icon(30))
        assertEquals(51, icon(37))
        assertEquals(FceClusterIcons.ROUNDABOUT_ENTER, icon(38))
        assertEquals(FceClusterIcons.ROUNDABOUT_ENTER, icon(46))
        assertEquals(FceClusterIcons.ROUNDABOUT_ENTER, icon(6))
        assertEquals(FceClusterIcons.ROUNDABOUT_EXIT, icon(7))
    }

    @Test
    fun `arrivals show the flag and roads show straight`() {
        listOf(10, 12, 24, 25, 27).forEach { assertEquals(FceClusterIcons.DESTINATION, icon(it)) }
        listOf(3, 5, 8, 9, 11, 15, 16, 17, 51).forEach { assertEquals(FceClusterIcons.STRAIGHT, icon(it)) }
    }

    @Test
    fun `unknown maneuvers show no arrow`() {
        listOf(0, 54, 99, -1).forEach { assertEquals(FceClusterIcons.NONE, icon(it)) }
    }

    @Test
    fun `every mapped glyph is one the cluster draws`() {
        val drawn = setOf(0, 3, 16, 17, 18, 19, 20, 21, 22, 23, 24, 52, 53) + (42..51)
        (0..60).forEach { type -> listOf(0, 1).forEach { side -> assert(icon(type, side) in drawn) { "$type/$side" } } }
    }

    @Test
    fun `distance is rounded as the driver reads it`() {
        assertEquals(0, FceManeuverCodes.distance(-5))
        assertEquals(180, FceManeuverCodes.distance(178))
        assertEquals(990, FceManeuverCodes.distance(994))
        assertEquals(1000, FceManeuverCodes.distance(996))
        assertEquals(1500, FceManeuverCodes.distance(1530))
        assertEquals(12500, FceManeuverCodes.distance(12_460))
    }
}
