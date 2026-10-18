package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelSiriKeyTest {
    @Test fun pressesCloserThanTheGapBelongToOneHold() {
        val siri = WheelSiriKey()
        assertTrue(siri.opens(1_000))
        assertFalse(siri.opens(1_100))
        // The gap counts from the latest press, so a long hold stays one press.
        assertFalse(siri.opens(1_450))
        assertFalse(siri.opens(1_800))
        assertTrue(siri.opens(1_800 + WheelSiriKey.REPEAT_GAP_MILLIS))
    }

    @Test fun secondDistinctPressInsideTheConfiguredWindowOpensSiri() {
        val siri = WheelSiriKey()
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_000, 600))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(1_450, 600))
        // A completed pair never lends its second press to the next pair.
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_700, 600))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(2_250, 600))
    }

    @Test fun expiredPairsAndHeldKeyRepeatsDoNotOpenSiri() {
        val siri = WheelSiriKey()
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_000, 500))
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(1_700, 500))
        for (time in 1_800L..2_200L step 100) {
            assertEquals(WheelSiriKey.DoublePressAction.REPEAT, siri.onDoublePress(time, 500))
        }
        siri.reset()
        assertEquals(WheelSiriKey.DoublePressAction.FIRST, siri.onDoublePress(3_000, 500))
        assertEquals(WheelSiriKey.DoublePressAction.CONFIRMED, siri.onDoublePress(3_300, 500))
    }
}
