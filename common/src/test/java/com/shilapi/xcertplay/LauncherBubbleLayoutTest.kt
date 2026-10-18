package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the bubble goes and what a touch means, without an Android window. */
class LauncherBubbleLayoutTest {

    @Test fun aBubbleIsAShareOfTheScreenHeightAndTheIconGivesTheWidth() {
        assertEquals(
            LauncherBubbleLayout.Box(126, 72, 1770, 324),
            LauncherBubbleLayout.initialBox(1920, 720, 10, 1.75f, 1f),
        )
    }

    @Test fun aThinBubbleKeepsAUsableMinimumHeight() {
        // 8% of 720px is 57px: below the 36dp floor at density 2.
        assertEquals(
            LauncherBubbleLayout.Box(72, 72, 1160, 324),
            LauncherBubbleLayout.initialBox(1280, 720, 8, 1f, 2f),
        )
    }

    @Test fun aRememberedPlaceStaysOnTheScreen() {
        assertEquals(
            LauncherBubbleLayout.Box(200, 100, 0, 620),
            LauncherBubbleLayout.clamp(LauncherBubbleLayout.Box(200, 100, -50, 900), 1920, 720),
        )
        // A bubble larger than the screen still has a defined place.
        assertEquals(
            LauncherBubbleLayout.Box(2000, 800, 0, 0),
            LauncherBubbleLayout.clamp(LauncherBubbleLayout.Box(2000, 800, 100, 100), 1920, 720),
        )
    }

    @Test fun theHoldPromptSitsAboveTheBubbleAndCentresOnIt() {
        assertEquals(
            LauncherBubbleLayout.Box(200, 40, 1720, 276),
            LauncherBubbleLayout.labelBox(LauncherBubbleLayout.Box(126, 72, 1770, 324), 200, 40, 8, 1920),
        )
    }

    @Test fun theHoldPromptGoesBelowWhenThereIsNoRoomAbove() {
        assertEquals(
            LauncherBubbleLayout.Box(200, 40, 63, 100),
            LauncherBubbleLayout.labelBox(LauncherBubbleLayout.Box(126, 72, 100, 20), 200, 40, 8, 1920),
        )
    }

    @Test fun aMoveIsOnlyADragPastTheSlop() {
        assertFalse(LauncherBubbleLayout.isDrag(6, 0, 6))
        assertFalse(LauncherBubbleLayout.isDrag(-6, 6, 6))
        assertTrue(LauncherBubbleLayout.isDrag(7, 0, 6))
        assertTrue(LauncherBubbleLayout.isDrag(0, -7, 6))
    }

    @Test fun theSizeSettingOnlyOffersItsOwnSteps() {
        assertEquals(listOf(8, 10, 12, 16), LauncherBubbleLayout.sizePercents)
        assertEquals(10, LauncherBubbleLayout.snapSizePercent(10))
        assertEquals(12, LauncherBubbleLayout.snapSizePercent(12))
        // A tie goes to the smaller step, and anything outside the list goes to either end.
        assertEquals(8, LauncherBubbleLayout.snapSizePercent(9))
        assertEquals(10, LauncherBubbleLayout.snapSizePercent(11))
        assertEquals(16, LauncherBubbleLayout.snapSizePercent(15))
        assertEquals(8, LauncherBubbleLayout.snapSizePercent(0))
        assertEquals(16, LauncherBubbleLayout.snapSizePercent(1000))
        assertEquals(8, LauncherBubbleLayout.snapSizePercent(-4))
    }
}
