package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FceClusterOutputTest {
    private class FakeWriter : FcePropertyWriter {
        val writes = mutableListOf<Pair<Int, Int>>()
        var failing = emptySet<Int>()
        override fun setInt(propertyId: Int, value: Int): Boolean {
            if (propertyId in failing) return false
            writes += propertyId to value
            return true
        }
    }

    private val status = FceClusterProperties.NAVI_STATUS
    private val icon = FceClusterProperties.ICON
    private val icon2 = FceClusterProperties.ICON_SET_2
    private val distance = FceClusterProperties.DISTANCE

    @Test
    fun `first guidance unlocks the view and resets icon set 2 before the icon`() {
        val writer = FakeWriter()
        val output = FceClusterOutput(writer)

        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 200))

        assertEquals(listOf(status to 2, icon2 to 0, icon to 19, distance to 200), writer.writes)
        assertTrue(output.active)
    }

    @Test
    fun `only changed values are written`() {
        val writer = FakeWriter()
        val output = FceClusterOutput(writer)
        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 200))
        writer.writes.clear()

        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 200))
        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 150))
        output.update(FceClusterGuidance(FceClusterIcons.RIGHT, 150))

        assertEquals(listOf(distance to 150, icon2 to 0, icon to 20), writer.writes)
    }

    @Test
    fun `stop sends the factory end sequence once`() {
        val writer = FakeWriter()
        val output = FceClusterOutput(writer)
        output.update(FceClusterGuidance(FceClusterIcons.RIGHT, 80))
        writer.writes.clear()

        output.stop()
        output.stop()

        assertEquals(listOf(icon to 0, icon2 to 0, distance to 0, status to 1, status to 0), writer.writes)
        assertFalse(output.active)
    }

    @Test
    fun `stop without guidance writes nothing`() {
        val writer = FakeWriter()
        FceClusterOutput(writer).stop()
        assertTrue(writer.writes.isEmpty())
    }

    @Test
    fun `failed status write keeps the output inactive and retries next time`() {
        val writer = FakeWriter().apply { failing = setOf(FceClusterProperties.NAVI_STATUS) }
        val output = FceClusterOutput(writer)

        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 200))
        assertFalse(output.active)
        assertTrue(writer.writes.isEmpty())

        writer.failing = emptySet()
        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 200))
        assertEquals(listOf(status to 2, icon2 to 0, icon to 19, distance to 200), writer.writes)
    }

    @Test
    fun `icon is never written while icon set 2 could not be reset`() {
        val writer = FakeWriter().apply { failing = setOf(FceClusterProperties.ICON_SET_2) }
        val output = FceClusterOutput(writer)

        output.update(FceClusterGuidance(FceClusterIcons.U_TURN_LEFT, 120))
        writer.failing = emptySet()
        output.update(FceClusterGuidance(FceClusterIcons.U_TURN_LEFT, 120))

        assertEquals(listOf(status to 2, distance to 120, icon2 to 0, icon to 23), writer.writes)
    }

    @Test
    fun `distance is clamped to the 17-bit signal`() {
        val writer = FakeWriter()
        FceClusterOutput(writer).update(FceClusterGuidance(FceClusterIcons.STRAIGHT, 500_000))
        assertEquals(distance to FceClusterOutput.MAX_DISTANCE_METERS, writer.writes.last())
    }

    @Test
    fun `standby unlocks the view without touching icon or distance`() {
        val writer = FakeWriter()
        val output = FceClusterOutput(writer)
        output.standby()
        output.standby()
        assertEquals(listOf(status to 2), writer.writes)
        assertTrue(output.active)
    }

    @Test
    fun `standby after guidance blanks the arrow and distance but keeps the view`() {
        val writer = FakeWriter()
        val output = FceClusterOutput(writer)
        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 200))
        writer.writes.clear()

        output.standby()
        output.update(FceClusterGuidance(FceClusterIcons.RIGHT, 90))

        assertEquals(listOf(icon to 0, distance to 0, icon2 to 0, icon to 20, distance to 90), writer.writes)
    }

    @Test
    fun `restarts with status 2 after a stop`() {
        val writer = FakeWriter()
        val output = FceClusterOutput(writer)
        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 200))
        output.stop()
        writer.writes.clear()

        output.update(FceClusterGuidance(FceClusterIcons.LEFT, 200))

        assertEquals(listOf(status to 2, icon2 to 0, icon to 19, distance to 200), writer.writes)
    }
}
