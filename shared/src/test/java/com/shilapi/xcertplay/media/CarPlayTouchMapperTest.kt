package com.shilapi.xcertplay.media

import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayTouchMapperTest {
    private val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)

    @Test fun contentCornersMapToCanvasCorners() {
        contact(MotionEvent.ACTION_DOWN, content.left, content.top).let {
            assertEquals(0.0, it.x, 1e-6)
            assertEquals(0.0, it.y, 1e-6)
            assertTrue(it.down)
        }
        contact(MotionEvent.ACTION_UP, content.left + content.width, content.top + content.height).let {
            assertEquals(1.0, it.x, 1e-6)
            assertEquals(1.0, it.y, 1e-6)
            assertFalse(it.down)
        }
    }

    @Test fun centreRemainsCentred() {
        contact(MotionEvent.ACTION_MOVE, 960f, 471f).let {
            assertEquals(0.5, it.x, 1e-6)
            assertEquals(0.5, it.y, 1e-6)
        }
    }

    @Test fun dragIntoABarClampsToTheCanvasEdge() {
        assertEquals(0.0, contact(MotionEvent.ACTION_MOVE, 0f, 471f).x, 1e-6)
        assertEquals(1.0, contact(MotionEvent.ACTION_MOVE, 1920f, 471f).x, 1e-6)
    }

    @Test fun cancelInABarReleasesTheContact() {
        assertFalse(contact(MotionEvent.ACTION_CANCEL, 0f, 471f).down)
    }

    @Test fun fullViewOverloadKeepsExistingCoordinates() {
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 480f, 247.5f, 0)
        try {
            val contact = CarPlayTouchMapper.contacts(event, 1920, 990).single()
            assertEquals(0.25, contact.x, 1e-6)
            assertEquals(0.25, contact.y, 1e-6)
        } finally {
            event.recycle()
        }
    }

    // #444: the id follows the finger. After pointer 0 lifted, pointer 1 is at index 0.
    @Test fun aContactCarriesItsPointerIdNotItsIndex() {
        val contacts = contacts(MotionEvent.ACTION_MOVE, intArrayOf(1), floatArrayOf(960f), floatArrayOf(471f))
        assertEquals(listOf(1), contacts.map { it.id })
        assertTrue(contacts.single().down)
    }

    @Test fun onlyTheLiftedPointerIsUp() {
        val action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val contacts = contacts(action, intArrayOf(0, 1), floatArrayOf(480f, 960f), floatArrayOf(471f, 471f))
        assertEquals(listOf(0 to true, 1 to false), contacts.map { it.id to it.down })
        assertEquals(0.5, contacts[1].x, 1e-6)
    }

    // The session chooses the two report slots, so it sees every finger.
    @Test fun everyPointerIsMapped() {
        val contacts = contacts(MotionEvent.ACTION_MOVE, intArrayOf(0, 2, 3),
            floatArrayOf(480f, 960f, 1440f), floatArrayOf(471f, 471f, 471f))
        assertEquals(listOf(0, 2, 3), contacts.map { it.id })
    }

    private fun contacts(action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray): List<AirPlayContact> {
        val properties = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id } }.toTypedArray()
        val coords = xs.indices.map { i -> MotionEvent.PointerCoords().apply { x = xs[i]; y = ys[i] } }.toTypedArray()
        val event = MotionEvent.obtain(0, 0, action, ids.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return try {
            CarPlayTouchMapper.contacts(event, content)
        } finally {
            event.recycle()
        }
    }

    private fun contact(action: Int, x: Float, y: Float) = MotionEvent.obtain(0, 0, action, x, y, 0).let {
        try {
            CarPlayTouchMapper.contacts(it, content).single()
        } finally {
            it.recycle()
        }
    }
}
