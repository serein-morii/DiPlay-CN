package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AirPlayTouchSlotsTest {
    // #444, as a pinch on a Galaxy Note20 sent it: the first finger lifts while the second stays.
    // main then pressed slot 0 again at the second finger and released slot 1 at (0,0).
    @Test fun theFingerThatStaysKeepsItsSlotAndTheOtherIsReleasedWhereItWas() {
        val slots = AirPlayTouchSlots(2)
        assertEquals(listOf(c(0, .6, .5)), slots.assign(listOf(c(0, .6, .5))))
        assertEquals(listOf(c(0, .6, .5), c(1, .4, .3)), slots.assign(listOf(c(0, .6, .5), c(1, .4, .3))))
        // ACTION_POINTER_UP for pointer 0.
        assertEquals(listOf(c(0, .55, .45, down = false), c(1, .42, .35)),
            slots.assign(listOf(c(0, .55, .45, down = false), c(1, .42, .35))))
        // Pointer 1 is now at index 0 and moves on alone.
        assertEquals(listOf(c(1, .43, .36)), slots.assign(listOf(c(1, .43, .36))))
        assertEquals(listOf(c(1, .43, .36, down = false)), slots.assign(listOf(c(1, .43, .36, down = false))))
        assertEquals(emptyList<AirPlayContact>(), slots.assign(emptyList()))
    }

    // Opening the settings menu or DiPlay's home, and the settings swipe, send an empty list.
    @Test fun anEmptyListReleasesEveryFingerWhereItWas() {
        val slots = AirPlayTouchSlots(2)
        slots.assign(listOf(c(3, .1, .2), c(5, .7, .8)))
        assertEquals(listOf(c(0, .1, .2, down = false), c(1, .7, .8, down = false)), slots.assign(emptyList()))
        assertEquals(emptyList<AirPlayContact>(), slots.assign(emptyList()))
    }

    @Test fun aFingerMissingFromTheNextEventIsReleasedWhereItWas() {
        val slots = AirPlayTouchSlots(2)
        slots.assign(listOf(c(0, .1, .2), c(1, .7, .8)))
        assertEquals(listOf(c(0, .15, .25), c(1, .7, .8, down = false)), slots.assign(listOf(c(0, .15, .25))))
    }

    @Test fun aThirdFingerWaitsForAFreeSlot() {
        val slots = AirPlayTouchSlots(2)
        slots.assign(listOf(c(0, .1, .1), c(1, .2, .2)))
        assertEquals(listOf(c(0, .1, .1), c(1, .2, .2)),
            slots.assign(listOf(c(0, .1, .1), c(1, .2, .2), c(2, .3, .3))))
        assertEquals(listOf(c(0, .1, .1, down = false), c(1, .2, .2)),
            slots.assign(listOf(c(0, .1, .1, down = false), c(1, .2, .2), c(2, .3, .3))))
        assertEquals(listOf(c(0, .3, .3), c(1, .2, .2)), slots.assign(listOf(c(1, .2, .2), c(2, .3, .3))))
    }

    @Test fun aReleaseOfAFingerThatWasNeverReportedIsLeftOut() {
        assertEquals(emptyList<AirPlayContact>(), AirPlayTouchSlots(2).assign(listOf(c(4, .5, .5, down = false))))
    }

    @Test fun eachContactGoesInTheSlotItsIdNames() {
        // Slot 0 up at (0,0); slot 1 down at (300,400), coordinates little-endian.
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 0x2c, 0x01, 0x90.toByte(), 0x01),
            AirPlayHid.touchReport(listOf(AirPlayContact(1, 300.0, 400.0, true))))
    }

    private fun c(id: Int, x: Double, y: Double, down: Boolean = true) = AirPlayContact(id, x, y, down)
}
