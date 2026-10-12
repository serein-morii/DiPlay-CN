package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarHiddenByAnotherWindowTest {
    private fun session(hideTopBar: Boolean = false, statusBarShown: Boolean? = true) = CarPlaySessionDisplay(
        width = 1280, height = 660, rotation = 0, hideTopBar = hideTopBar, hideBottomBar = false,
        windowWidth = 1280, windowHeight = 660, statusBarShown = statusBarShown,
    )

    @Test fun theReversingCameraHidingTheStatusBarKeepsTheSession() {
        // #643: 1280x660 grew to 1280x720 during reverse, CarPlay restarted at 720 and stayed scaled down at 660.
        assertTrue(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = false))
    }

    @Test fun growthWithTheStatusBarStillShownIsANewWindow() {
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = true))
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 720, statusBarShown = null))
    }

    @Test fun aDriverWhoHidesTheStatusBarGetsTheLargerCanvas() {
        // #536: DiPlay hid the bar itself, so the larger window is the one the driver chose.
        assertFalse(statusBarHiddenByAnotherWindow(session(hideTopBar = true), 1280, 720, statusBarShown = false))
    }

    @Test fun aSessionStartedWithoutAStatusBarIsNotAffected() {
        assertFalse(statusBarHiddenByAnotherWindow(session(statusBarShown = false), 1280, 720, statusBarShown = false))
        assertFalse(statusBarHiddenByAnotherWindow(session(statusBarShown = null), 1280, 720, statusBarShown = false))
    }

    @Test fun onlyAHeightGrowthAtTheSameWidthCounts() {
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1400, 720, statusBarShown = false))
        assertFalse(statusBarHiddenByAnotherWindow(session(), 1280, 660, statusBarShown = false))
    }
}
