package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.LaneGuidanceLane
import com.shilapi.xcertplay.hud.BydHudRouteState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class) // Lane guidance logs through android.util.Log.
class CarPlayGlanceTest {
    private fun frame(id: Int, block: Iap2BodyBuilder.() -> Unit) = Iap2Messages.buildRaw(id, block)

    @Before
    fun reset() {
        CarPlayGlance.listener = null
        CarPlayGlance.laneListener = null
        CarPlayGlance.setConnected(true)
        CarPlayGlance.setConnected(false)
    }

    @After
    fun clear() = reset()

    @Test
    fun followsTheNextTurnAndTheSong() {
        val seen = mutableListOf<CarPlayGlance.Snapshot>()
        CarPlayGlance.listener = { seen += it }
        CarPlayGlance.setConnected(true)

        // Maneuver 7 is a right turn onto Main Street; the route update makes it the next one.
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 7); u8(3, 2); string(4, "Main Street") })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 350); u16List(0x0d, listOf(7)) })
        CarPlayGlance.onFrame(frame(0x5001) {
            group(0) { string(1, "Numb"); string(12, "Linkin Park") }
            group(1) { u8(0, 1) }
        })

        val now = CarPlayGlance.snapshot()
        assertTrue(now.connected)
        assertEquals(2, now.maneuverType)
        assertEquals(350, now.distanceMeters)
        assertEquals("Main Street", now.road)
        assertEquals("Numb — Linkin Park", now.song)
        assertTrue(now.playing)
        assertEquals(now, seen.last())
    }

    @Test
    fun routeEndAndSessionEnd() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 1) })
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u32(0x0a, 90); u16List(0x0d, listOf(1)) })
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Podcast") } })

        // Arrived: no turn any more, the song stays.
        CarPlayGlance.onFrame(frame(0x5201) { u8(1, 2) })
        assertNull(CarPlayGlance.snapshot().maneuverType)
        assertEquals("Podcast", CarPlayGlance.snapshot().song)

        CarPlayGlance.setConnected(false)
        assertEquals(CarPlayGlance.Snapshot(), CarPlayGlance.snapshot())
    }

    @Test
    fun expiredGuidanceClearsWithoutAnotherFrameAndNotifiesTheWidget() {
        withRouteClock { advance ->
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 2) })
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(1)) })
            assertEquals(2, CarPlayGlance.snapshot().maneuverType)
            val seen = mutableListOf<CarPlayGlance.Snapshot>()
            CarPlayGlance.listener = { seen += it }
            advance(30_000_000_000L)
            assertNull(CarPlayGlance.snapshot().maneuverType)
            assertNull(seen.single().maneuverType)
            assertTrue(CarPlayGlance.snapshot().connected)
        }
    }

    @Test
    fun aPersistentlyEmptyManeuverListExpiresAfterTheGracePeriod() {
        withRouteClock { advance ->
            CarPlayGlance.setConnected(true)
            CarPlayGlance.onFrame(frame(0x5202) { u16(1, 1); u8(3, 2) })
            CarPlayGlance.onFrame(frame(0x5201) { u8(1, 1); u16List(0x0d, listOf(1)) })
            CarPlayGlance.onFrame(frame(0x5201) { u16List(0x0d, emptyList()) })
            advance(2_000_000_000L)
            assertEquals(2, CarPlayGlance.snapshot().maneuverType)
            advance(1_000_000_000L)
            assertNull(CarPlayGlance.snapshot().maneuverType)
        }
    }

    @Test
    fun anEmptySongTitleClearsTheWidgetSong() {
        CarPlayGlance.setConnected(true)
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "Previous song") } })
        CarPlayGlance.onFrame(frame(0x5001) { group(0) { string(1, "") } })
        assertNull(CarPlayGlance.snapshot().song)
    }

    // Inject the route parser's monotonic clock rather than waiting 30 seconds in each test.
    private fun withRouteClock(test: ((Long) -> Unit) -> Unit) {
        val route = CarPlayGlance.javaClass.getDeclaredField("route").apply { isAccessible = true }
            .get(CarPlayGlance) as BydHudRouteState
        val clock = route.javaClass.getDeclaredField("nanoTime").apply { isAccessible = true }
        val original = clock.get(route)
        var now = 1_000_000_000L
        try {
            clock.set(route, { now })
            test { now += it }
        } finally {
            clock.set(route, original)
        }
    }

    @Test
    fun otherMessagesChangeNothing() {
        CarPlayGlance.setConnected(true)
        val before = CarPlayGlance.snapshot()
        var calls = 0
        CarPlayGlance.listener = { calls++ }

        CarPlayGlance.onFrame(frame(0x4E09) { u8(1, 1) })

        assertEquals(before, CarPlayGlance.snapshot())
        assertEquals(0, calls)
    }

    @Test
    fun laneGuidanceReachesTheLaneListenerWithoutTouchingTheGlance() {
        CarPlayGlance.setConnected(true)
        val before = CarPlayGlance.snapshot()
        val strips = mutableListOf<List<LaneGuidanceLane>>()
        CarPlayGlance.laneListener = { strips += it }

        CarPlayGlance.onFrame(frame(0x5204) {
            group(2) { u16(0, 0); u8(1, 0); i16(2, -90) }
            group(2) { u16(0, 1); u8(1, 2); i16(2, 0); i16(3, 90) }
        })
        // The phone clears the strip with an empty payload when the overlap ends.
        CarPlayGlance.onFrame(frame(0x5204) { })

        assertEquals(2, strips.size)
        assertEquals(listOf(0, 1), strips[0].map { it.index })
        assertEquals(-90, strips[0][0].angle)
        assertEquals(true, strips[0][1].recommended)
        assertEquals(90, strips[0][1].highlightAngle)
        assertTrue(strips[1].isEmpty())
        assertEquals(before, CarPlayGlance.snapshot())
    }
}
