package com.shilapi.xcertplay.hud

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydHudRouteStateTest {
    @Test
    fun `combines maneuver and route updates`() {
        val state = BydHudRouteState()
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 7), tlv(0x03, 1), tlv(0x08, 0)),
        )

        val change = state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 150), tlv(0x0d, 0, 7)),
        )

        assertEquals(BydHudRouteChange.GUIDANCE, change)
        assertEquals(BydHudGuidance(distanceMeters = 150, maneuver = 1, gaode = 1), state.current())
    }

    @Test
    fun `lane update attaches arrows to the current maneuver`() {
        val state = populatedState()
        val change = state.accept(
            BydHudRouteState.LANE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x03, 1, 1, 3)),
        )
        assertEquals(BydHudRouteChange.GUIDANCE, change)
        val apple = state.currentApple()
        assertEquals(listOf(1, 1, 3), apple?.lanes)
        assertEquals(1, apple?.laneHighlight)
    }

    @Test
    fun `maps right-hand u-turn`() {
        val state = BydHudRouteState()
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 3), tlv(0x03, 4), tlv(0x08, 1)),
        )
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 50), tlv(0x0d, 0, 3)),
        )

        assertEquals(BydHudGuidance(distanceMeters = 50, maneuver = 8, gaode = 10), state.current())
    }

    @Test
    fun `route end clears guidance`() {
        val state = populatedState()

        val change = state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 2)))

        assertEquals(BydHudRouteChange.CLEAR, change)
        assertNull(state.current())
    }

    @Test
    fun `overlay keeps the last instruction across a NoRouteSet handoff`() {
        val state = populatedState(keepAcrossNoRoute = true)
        val before = state.current()

        val change = state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))

        assertEquals(BydHudRouteChange.NONE, change)
        assertEquals(before, state.current())
    }

    @Test
    fun `overlay still clears on arrival even when NoRouteSet is ignored`() {
        val state = populatedState(keepAcrossNoRoute = true)

        val change = state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 2)))

        assertEquals(BydHudRouteChange.CLEAR, change)
        assertNull(state.current())
    }

    @Test
    fun `overlay treats NoRouteSet at the destination as the real end`() {
        val state = BydHudRouteState(keepAcrossNoRoute = true)
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 1), tlv(0x03, 10), tlv(0x08, 0)),
        )
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 8), tlv(0x0d, 0, 1)),
        )
        assertEquals(8, state.current()!!.distanceMeters)

        val change = state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))

        assertEquals(BydHudRouteChange.CLEAR, change)
        assertNull(state.current())
    }

    @Test
    fun `overlay treats NoRouteSet at remaining zero as the real end`() {
        val state = populatedState(keepAcrossNoRoute = true)
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x07, 0, 0, 0, 0, 0, 0, 0, 0), tlv(0x0d, 0, 1)),
        )

        val change = state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 0)))

        assertEquals(BydHudRouteChange.CLEAR, change)
        assertNull(state.current())
    }

    @Test
    fun `malformed frame does not alter active guidance`() {
        val state = populatedState()
        val before = state.current()

        val change = state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            byteArrayOf(0, 8, 0, 1, 1),
        )

        assertEquals(BydHudRouteChange.NONE, change)
        assertEquals(before, state.current())
    }


    @Test
    fun `carries after-maneuver road and arrival time`() {
        val state = BydHudRouteState()
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 2), tlv(0x03, 2), tlv(0x04, *utf8z("Am Wehr")), tlv(0x08, 0)),
        )
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(
                tlv(0x01, 1), tlv(0x03, *utf8z("Hauptstraße")), tlv(0x05, 0, 0, 0, 0, 0x68, 0xd5, 0x2a, 0x40),
                tlv(0x0a, 0, 0, 1, 44), tlv(0x0d, 0, 2),
            ),
        )

        val guidance = state.current()!!
        assertEquals("Am Wehr", guidance.road)
        assertEquals(0x68d52a40L, guidance.arrivalEpochSeconds)
        assertEquals(300, guidance.distanceMeters)
    }

    @Test
    fun `brief empty maneuver list keeps guidance and a lasting one hides it without losing maneuvers`() {
        var now = 0L
        val state = populatedState(nanoTime = { now })

        assertEquals(BydHudRouteChange.NONE, state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 5), tlv(0x0d))))
        now = 2_000_000_000L
        assertEquals(2, state.current()!!.maneuver) // a blip does not blank the outputs
        now = 3_100_000_000L
        assertNull(state.current())

        // The iPhone lists the same maneuver again without resending its 0x5202 details.
        val resumed = state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 20), tlv(0x0d, 0, 1)),
        )
        assertEquals(BydHudRouteChange.GUIDANCE, resumed)
        assertEquals(2, state.current()!!.maneuver)
    }

    @Test
    fun `falls back to current road`() {
        val state = BydHudRouteState()
        state.accept(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 0), tlv(0x03, 1)))
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x03, *utf8z("Hauptstraße")), tlv(0x0d, 0, 0)),
        )
        assertEquals("Hauptstraße", state.current()!!.road)
    }

    @Test
    fun `roundabout exit has blank arrow and exit icon`() {
        val state = BydHudRouteState()
        state.accept(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, tlvs(tlv(0x01, 0, 0), tlv(0x03, 29)))
        state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x01, 1), tlv(0x0d, 0, 0)))
        val guidance = state.current()!!
        assertEquals(99, guidance.maneuver)
        assertEquals(26, guidance.gaode)
    }

    @Test
    fun `silent route expires and fresh update restores cached maneuver`() {
        var now = 0L
        val state = populatedState(nanoTime = { now })
        now = 30_000_000_000L
        assertNull(state.current())
        state.accept(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(tlv(0x0a, 0, 0, 0, 20)))
        assertEquals(20, state.current()!!.distanceMeters)
    }

    @Test
    fun `clear prevents a keepalive from restoring guidance`() {
        val state = populatedState()
        state.clear()
        assertNull(state.current())
        assertNull(state.currentApple())
    }

    private fun utf8z(value: String): IntArray =
        (value.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff } + 0).toIntArray()

    private fun populatedState(
        nanoTime: () -> Long = System::nanoTime,
        keepAcrossNoRoute: Boolean = false,
    ): BydHudRouteState =
        BydHudRouteState(nanoTime, keepAcrossNoRoute = keepAcrossNoRoute).also { state ->
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, 1), tlv(0x03, 2), tlv(0x08, 0)),
        )
        state.accept(
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE,
            tlvs(tlv(0x01, 1), tlv(0x0a, 0, 0, 0, 25), tlv(0x0d, 0, 1)),
        )
    }

    private fun tlv(type: Int, vararg value: Int): ByteArray = ByteArrayOutputStream().apply {
        val length = value.size + 4
        write(length ushr 8)
        write(length)
        write(type ushr 8)
        write(type)
        value.forEach(::write)
    }.toByteArray()

    private fun tlvs(vararg values: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        values.forEach(::write)
    }.toByteArray()
}
