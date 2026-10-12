package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterActivityOutputTest {
    @Test fun launchRejectsWrongTokenAndDisplayAndStopInvalidatesTheTicket() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        val owner = Any()
        ClusterActivityOutput.bind(owner, 4) { }
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        ClusterActivityOutput::class.java.getDeclaredField("launchToken")
            .apply { isAccessible = true }.set(ClusterActivityOutput, token)
        ClusterActivityOutput::class.java.getDeclaredField("expectedDisplay")
            .apply { isAccessible = true }.setInt(ClusterActivityOutput, 7)
        val activity = org.robolectric.Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        try {
            assertFalse(ClusterActivityOutput.confirm(activity, "wrong", 7))
            assertFalse(ClusterActivityOutput.confirm(activity, token, 0))
            assertFalse(ClusterActivityOutput.confirm(activity, token, 8))
            assertTrue(ClusterActivityOutput.confirm(activity, token, 7))
            assertTrue(ClusterActivityOutput.hasConfirmedRoute())
            var restored = false
            ClusterActivityOutput.completeLaunch(token, current = true, accepted = false) { restored = true }
            assertFalse(restored)
            assertTrue(ClusterActivityOutput.hasConfirmedRoute())
            ClusterActivityOutput.stopForSettings()
            assertFalse(ClusterActivityOutput.acceptsToken(token))
            assertFalse(ClusterActivityOutput.confirm(activity, token, 7))
            assertFalse(ClusterActivityOutput.hasConfirmedRoute())
        } finally { ClusterActivityOutput.stop(owner) }
    }

    @Test fun theRouteSettlesWhenTheWindowConfirmsOrTheShellRejectsTheLaunch() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        val owner = Any()
        ClusterActivityOutput.bind(owner, 4) { }
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        fun admit(display: Int) {
            ClusterActivityOutput::class.java.getDeclaredField("launchToken")
                .apply { isAccessible = true }.set(ClusterActivityOutput, token)
            ClusterActivityOutput::class.java.getDeclaredField("expectedDisplay")
                .apply { isAccessible = true }.setInt(ClusterActivityOutput, display)
        }
        val activity = org.robolectric.Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        try {
            assertFalse(ClusterActivityOutput.routeSettling())
            ClusterActivityOutput.launchPending = true
            assertTrue(ClusterActivityOutput.routeSettling())
            admit(display = 1)
            ClusterActivityOutput.completeLaunch(token, current = true, accepted = true) { }
            // The shell accepted the launch; the window has not reported its display yet.
            assertTrue(ClusterActivityOutput.routeSettling())
            assertTrue(ClusterActivityOutput.confirm(activity, token, 1))
            assertFalse(ClusterActivityOutput.routeSettling())

            ClusterActivityOutput.stopForSettings()
            ClusterActivityOutput.bind(owner, 4) { }
            admit(display = 1)
            ClusterActivityOutput.launchPending = true
            ClusterActivityOutput.completeLaunch(token, current = true, accepted = false) { }
            assertFalse(ClusterActivityOutput.routeSettling())
        } finally { ClusterActivityOutput.stop(owner) }
    }

    @Test fun rejectedShellLaunchInvalidatesAdmissionBeforeRestoringOemState() {
        val owner = Any()
        ClusterActivityOutput.bind(owner, 4) { }
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        ClusterActivityOutput::class.java.getDeclaredField("launchToken")
            .apply { isAccessible = true }.set(ClusterActivityOutput, token)
        var restored = false
        ClusterActivityOutput.completeLaunch(token, current = true, accepted = false) {
            assertFalse(ClusterActivityOutput.acceptsToken(token))
            restored = true
        }
        assertTrue(restored)
        ClusterActivityOutput.stop(owner)
    }

    @Test fun staleEditorsCannotChangeOrClearANewerCalibrationPreview() {
        val first = Any()
        val second = Any()
        val a = com.shilapi.xcertplay.airplay.SafeAreaRect(300, 100, 1500, 620)
        val b = com.shilapi.xcertplay.airplay.SafeAreaRect(200, 80, 1600, 640)
        ClusterActivityOutput.beginSafeAreaPreview(first, a)
        ClusterActivityOutput.updateSafeAreaPreview(first, b)
        assertEquals(b, ClusterActivityOutput.previewRect)
        ClusterActivityOutput.beginSafeAreaPreview(second, a)
        ClusterActivityOutput.updateSafeAreaPreview(first, b)
        ClusterActivityOutput.endSafeAreaPreview(first)
        assertEquals(a, ClusterActivityOutput.previewRect)
        ClusterActivityOutput.endSafeAreaPreview(second)
        assertNull(ClusterActivityOutput.previewRect)
    }

    @Test fun overlayStateSurvivesHandoffAndOnlyItsOwnerCanClearIt() {
        val host = Any()
        val newerHost = Any()
        val guidance = com.shilapi.xcertplay.hud.ClusterTurnGuidance(2, 0, 80, "Road",
            remainingMeters = 4200L, remainingSeconds = 630L)
        ClusterActivityOutput.bind(host, 4) { }
        ClusterActivityOutput.setTurnCard(guidance, 20, 50, 70, 60, true)
        ClusterActivityOutput.bind(newerHost, 5) { }
        ClusterActivityOutput.stop(host)
        assertEquals(guidance, ClusterActivityOutput.guidance)
        assertEquals(20, ClusterActivityOutput.cardX)
        assertEquals(50, ClusterActivityOutput.cardY)
        assertEquals(70, ClusterActivityOutput.cardSize)
        assertEquals(60, ClusterActivityOutput.cardOpacity)
        assertTrue(ClusterActivityOutput.cardNight)
        ClusterActivityOutput.stop(newerHost)
        assertNull(ClusterActivityOutput.guidance)
        assertFalse(ClusterActivityOutput.streamActive)
    }

    @Test fun surfaceRecreationRejectsStaleDetachAndRebindsNewHost() {
        val host = Any()
        val oldActivity = Any()
        val newActivity = Any()
        val firstTexture = SurfaceTexture(0)
        val secondTexture = SurfaceTexture(0)
        val first = Surface(firstTexture)
        val second = Surface(secondTexture)
        val events = mutableListOf<Surface?>()
        try {
            ClusterActivityOutput.bind(host, 4) { events.add(it) }
            ClusterActivityOutput.attach(oldActivity, first)
            ClusterActivityOutput.attach(newActivity, second)
            ClusterActivityOutput.detach(oldActivity, first)
            assertSame(second, ClusterActivityOutput.surface)
            assertSame(second, events.last())
            val newHost = Any()
            var rebound: Surface? = null
            ClusterActivityOutput.bind(newHost, 5) { rebound = it }
            assertSame(second, rebound)
            ClusterActivityOutput.stop(host)
            assertSame(second, ClusterActivityOutput.surface)
            ClusterActivityOutput.detach(newActivity, second)
            assertNull(ClusterActivityOutput.surface)
            ClusterActivityOutput.stop(newHost)
        } finally {
            first.release(); second.release()
            firstTexture.release(); secondTexture.release()
        }
    }
    @Test fun legacyRetryCompletesOnlyForItsValidActiveOutputAndResetsOnStop() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveLegacyClusterEnabled(app, true)
        val owner = org.robolectric.Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ClusterActivityOutput.bind(owner, 4) {}
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        ClusterActivityOutput::class.java.getDeclaredField("launchToken")
            .apply { isAccessible = true }.set(ClusterActivityOutput, token)
        ClusterActivityOutput::class.java.getDeclaredField("expectedDisplay")
            .apply { isAccessible = true }.setInt(ClusterActivityOutput, 7)
        val window = org.robolectric.Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        val stale = org.robolectric.Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        val presented = ClusterActivityOutput::class.java.getDeclaredField("legacyPresented")
            .apply { isAccessible = true }
        val texture = SurfaceTexture(0)
        val output = Surface(texture)
        try {
            assertTrue(ClusterActivityOutput.confirm(window, token, 7))
            assertFalse(presented.getBoolean(ClusterActivityOutput))
            ClusterActivityOutput.attach(window, output)
            ClusterActivityOutput.presented(window)
            assertFalse(presented.getBoolean(ClusterActivityOutput))
            ClusterActivityOutput.setStreamActive(true)
            ClusterActivityOutput.presented(stale)
            assertFalse(presented.getBoolean(ClusterActivityOutput))
            assertTrue(output.isValid)
            ClusterActivityOutput.presented(window)
            assertTrue(presented.getBoolean(ClusterActivityOutput))
            ClusterActivityOutput.activity.clear()
            ClusterActivityOutput.ensure(owner)
            assertFalse(ClusterActivityOutput.launchPending)
            assertTrue(presented.getBoolean(ClusterActivityOutput))
            ClusterActivityOutput.retry(force = true)
            assertFalse(presented.getBoolean(ClusterActivityOutput))
            assertTrue(ClusterActivityOutput.confirm(window, token, 7))
            ClusterActivityOutput.presented(window)
            assertTrue(presented.getBoolean(ClusterActivityOutput))
            ClusterActivityOutput.stop(owner)
            assertFalse(presented.getBoolean(ClusterActivityOutput))
            assertFalse(ClusterActivityOutput.acceptsToken(token))
        } finally {
            ClusterActivityOutput.stop(owner)
            output.release(); texture.release()
            AirPlayPersistence.saveLegacyClusterEnabled(app, false)
        }
    }

}
