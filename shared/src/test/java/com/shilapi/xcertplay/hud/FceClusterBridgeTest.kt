package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29])
class FceClusterBridgeTest {
    private val writes = mutableListOf<Pair<Int, Int>>()
    private lateinit var app: Context

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        BydOutputSettings.setEnabled(app, true)
        BydOutputSettings.setFceCluster(app, true)
        FceClusterBridge.resetForTest({ id, value -> writes += id to value; true }, app)
    }

    @After
    fun tearDown() = FceClusterBridge.resetForTest({ _, _ -> true }, null)

    private fun maneuver(index: Int, type: Int, side: Int = 0) = FceClusterBridge.onFrame(
        Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            tlvs(tlv(0x01, 0, index), tlv(0x03, type), tlv(0x08, side))),
    )

    private fun route(state: Int, distance: Int, current: Int? = null) = FceClusterBridge.onFrame(
        Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, tlvs(
            tlv(0x01, state),
            tlv(0x0a, distance ushr 24 and 0xff, distance ushr 16 and 0xff, distance ushr 8 and 0xff, distance and 0xff),
            *listOfNotNull(current?.let { tlv(0x0d, 0, it) }).toTypedArray(),
        )),
    )

    private val status = FceClusterProperties.NAVI_STATUS
    private val icon = FceClusterProperties.ICON
    private val icon2 = FceClusterProperties.ICON_SET_2
    private val distance = FceClusterProperties.DISTANCE

    @Test
    fun `nothing is written until the driver opts in`() {
        BydOutputSettings.setFceCluster(app, false)
        maneuver(index = 2, type = 1)
        route(state = 1, distance = 178, current = 2)

        assertEquals(emptyList<Pair<Int, Int>>(), writes)
    }

    @Test
    fun `carplay maneuver reaches the cluster`() {
        maneuver(index = 2, type = 1)
        route(state = 1, distance = 178, current = 2)

        assertEquals(listOf(status to 2, icon2 to 0, icon to 19, distance to 180), writes)
    }

    @Test
    fun `next maneuver and distance updates write only the changes`() {
        maneuver(index = 0, type = 1)
        maneuver(index = 1, type = 30)
        route(state = 1, distance = 300, current = 0)
        writes.clear()

        route(state = 1, distance = 250, current = 0)
        route(state = 1, distance = 1530, current = 1)

        assertEquals(listOf(distance to 250, icon2 to 0, icon to 44, distance to 1500), writes)
    }

    @Test
    fun `arrival clears the cluster`() {
        maneuver(index = 0, type = 12)
        route(state = 1, distance = 40, current = 0)
        writes.clear()

        route(state = 2, distance = 0)

        assertEquals(listOf(icon to 0, icon2 to 0, distance to 0, status to 1, status to 0), writes)
    }

    @Test
    fun `session end clears the cluster`() {
        maneuver(index = 0, type = 2)
        route(state = 1, distance = 90, current = 0)
        writes.clear()

        FceClusterBridge.clear()

        assertEquals(listOf(icon to 0, icon2 to 0, distance to 0, status to 1, status to 0), writes)
    }

    private fun raw(vararg bytes: Int) = FceClusterBridge.onFrame(
        Iap2Frame(BydHudRouteState.ROUTE_GUIDANCE_UPDATE, ByteArray(bytes.size) { bytes[it].toByte() }),
    )

    // The two RouteGuidanceUpdate frames an older iPhone sent with Google Maps: a source name and
    // "no route guidance", never a state or a maneuver.
    private fun googleMapsSource() = raw(
        0x00, 0x10, 0x00, 0x13, 'G'.code, 'o'.code, 'o'.code, 'g'.code, 'l'.code, 'e'.code, ' '.code,
        'M'.code, 'a'.code, 'p'.code, 's'.code, 0x00,
        0x00, 0x05, 0x00, 0x14, 0x00,
    )

    private fun emptySource() = raw(0x00, 0x05, 0x00, 0x13, 0x00)

    @Test
    fun `navigation app without maneuvers unlocks the view without an arrow`() {
        googleMapsSource()
        assertEquals(listOf(status to 2), writes)
    }

    @Test
    fun `empty source name closes the view`() {
        googleMapsSource()
        writes.clear()
        emptySource()
        assertEquals(listOf(icon to 0, icon2 to 0, distance to 0, status to 1, status to 0), writes)
    }

    @Test
    fun `empty source before any navigation writes nothing`() {
        emptySource()
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `active route state without a maneuver unlocks the view without an arrow`() {
        route(state = 1, distance = 0)
        assertEquals(listOf(status to 2), writes)
    }

    @Test
    fun `a route that comes back after session end unlocks the view again`() {
        maneuver(index = 0, type = 1)
        route(state = 1, distance = 200, current = 0)
        FceClusterBridge.clear()
        writes.clear()

        route(state = 5, distance = 0) // rerouting, maneuvers not resent yet
        assertEquals(listOf(status to 2), writes)
    }

    @Test
    fun `no route set closes the view even with a source name`() {
        googleMapsSource()
        writes.clear()
        route(state = 0, distance = 0)
        assertEquals(listOf(icon to 0, icon2 to 0, distance to 0, status to 1, status to 0), writes)
    }

    @Test
    fun `disabled setting sends nothing`() {
        BydOutputSettings.setEnabled(app, false)
        maneuver(index = 0, type = 1)
        route(state = 1, distance = 100, current = 0)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `frames before initialize are ignored`() {
        FceClusterBridge.resetForTest({ id, value -> writes += id to value; true }, null)
        maneuver(index = 0, type = 1)
        route(state = 1, distance = 100, current = 0)
        assertTrue(writes.isEmpty())
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
