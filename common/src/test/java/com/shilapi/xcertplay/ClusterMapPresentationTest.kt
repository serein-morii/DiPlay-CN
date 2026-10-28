package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterMapPresentationTest {
    @Test fun repeatedActiveNotificationsDoNotRestartTheFade() = withMeasuredPresentation { presentation ->
        presentation.setStreamActive(true)
        val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
        val label: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        video.alpha = 0.6f
        label.alpha = 0.4f
        presentation.setStreamActive(true)
        assertEquals(0.6f, video.alpha, 0.001f)
        assertEquals(0.4f, label.alpha, 0.001f)
    }

    @Test fun reversingTheFadeKeepsTheCurrentOpacityAndWaitingLabelVisible() = withMeasuredPresentation { presentation ->
        presentation.setStreamActive(true)
        val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
        val label: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        video.alpha = 0.6f
        label.alpha = 0.4f
        presentation.setStreamActive(false)
        assertEquals(android.view.View.VISIBLE, label.visibility)
        assertEquals(0.4f, label.alpha, 0.001f)
        assertEquals(0.6f, video.alpha, 0.001f)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    @Test fun aNewPresentationStartsWithVisibleWaitingTextAndHiddenVideo() = withPresentation { presentation ->
        val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
        val label: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        assertEquals(if (video is android.view.SurfaceView) 1f else 0f, video.alpha, 0.001f)
        assertEquals(1f, label.alpha, 0.001f)
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    @Test fun measuredDarkClusterKeepsReadableWaitingText() {
        val original = android.os.Build.FINGERPRINT
        val contrast = DiLink51ClusterLayout.contrast(context)
        org.robolectric.shadows.ShadowBuild.setFingerprint(DiLink51ClusterLayout.FINGERPRINT)
        DiLink51ClusterLayout.saveContrast(context, DiLink51ClusterLayout.Contrast.DARK)
        try {
            withPresentation(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.Theme.MAP) { presentation ->
                val label: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
                assertEquals(android.graphics.Color.WHITE, spinner(label).indeterminateTintList?.defaultColor)
            }
        } finally {
            org.robolectric.shadows.ShadowBuild.setFingerprint(original)
            DiLink51ClusterLayout.saveContrast(context, contrast)
        }
    }

    @Test @Config(sdk = [28, 29])
    fun legacySurfaceUsesAnOpaquePlaceholderWithoutChangingSurfaceAlpha() = withPresentation { presentation ->
        val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
        val label: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
        assertTrue(video is android.view.SurfaceView)
        val background = label.background as android.graphics.drawable.ColorDrawable
        assertEquals(255, android.graphics.Color.alpha(background.color))
        // DiPlay's dark theme (the default) gives a black placeholder.
        assertEquals(android.graphics.Color.BLACK, background.color)
        assertEquals(1f, video.alpha, 0.001f)
        presentation.setStreamActive(true)
        assertEquals(1f, video.alpha, 0.001f)
        label.alpha = 0.4f
        presentation.setStreamActive(false)
        assertEquals(1f, video.alpha, 0.001f)
        assertEquals(0.4f, label.alpha, 0.001f)
        assertEquals(android.view.View.VISIBLE, label.visibility)
    }

    @Test fun legacyPlaceholderFollowsALightAppTheme() {
        val appearance = AirPlayPersistence.loadAppAppearance(context)
        AirPlayPersistence.saveAppAppearance(context, AppAppearance.LIGHT)
        try {
            withPresentation { presentation ->
                val label: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "waitingLabel")
                assertEquals(android.graphics.Color.rgb(233, 238, 246), (label.background as android.graphics.drawable.ColorDrawable).color)
                assertEquals(android.graphics.Color.DKGRAY, spinner(label).indeterminateTintList?.defaultColor)
            }
        } finally {
            AirPlayPersistence.saveAppAppearance(context, appearance)
        }
    }

    private fun spinner(view: android.view.View): android.widget.ProgressBar =
        (view as android.view.ViewGroup).getChildAt(0) as android.widget.ProgressBar

    private fun withMeasuredPresentation(block: (ClusterMapPresentation) -> Unit) {
        val original = android.os.Build.FINGERPRINT
        org.robolectric.shadows.ShadowBuild.setFingerprint(DiLink51ClusterLayout.FINGERPRINT)
        try { withPresentation(DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.Theme.MAP, block = block) }
        finally { org.robolectric.shadows.ShadowBuild.setFingerprint(original) }
    }

    private fun withPresentation(name: String = DiLink51ClusterLayout.BASE,
        theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.Theme.SCENARIO,
        spec: String = "w1920dp-h720dp-mdpi",
        block: (ClusterMapPresentation) -> Unit) {
        val id = display(name, spec)
        val presentation = ClusterMapPresentation(context, manager.getDisplay(id), theme) {}
        try { presentation.create(); block(presentation) }
        finally { presentation.dismiss(); ShadowDisplayManager.removeDisplay(id) }
    }

    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(DisplayManager::class.java)

    private fun display(name: String, spec: String = "w960dp-h360dp"): Int {
        // Display.TYPE_VIRTUAL (5) is hidden from the public Android SDK.
        val id = ShadowDisplayManager.addDisplay(spec, 5)
        shadowOf(manager.getDisplay(id)).apply {
            setName(name)
            setFlags(Display.FLAG_PRESENTATION)
        }
        return id
    }

    @Test fun sideMapViewportFollowsTheClusterWidth() {
        assertSideViewport(DiLink51ClusterLayout.FINGERPRINT, "w1920dp-h720dp-mdpi", left = 1320, width = 600)
        assertSideViewport(DiLink51ClusterLayout.DENZA_N9_FINGERPRINT, "w2560dp-h720dp-mdpi", left = 1760, width = 800)
    }

    private fun assertSideViewport(fingerprint: String, spec: String, left: Int, width: Int) {
        val original = android.os.Build.FINGERPRINT
        org.robolectric.shadows.ShadowBuild.setFingerprint(fingerprint)
        try {
            withPresentation(DiLink51ClusterLayout.SIDE, DiLink51ClusterLayout.Theme.SCENARIO, spec) { presentation ->
                val video: android.view.View = org.robolectric.util.ReflectionHelpers.getField(presentation, "videoView")
                assertTrue(video is android.view.TextureView)
                val params = video.layoutParams as android.widget.FrameLayout.LayoutParams
                assertEquals(left, params.leftMargin)
                assertEquals(width, params.width)
                assertEquals(720, params.height)
            }
        } finally {
            org.robolectric.shadows.ShadowBuild.setFingerprint(original)
        }
    }

    @Test fun theDenzaN9SelectsItsWideSharedLayers() {
        val original = android.os.Build.FINGERPRINT
        org.robolectric.shadows.ShadowBuild.setFingerprint(DiLink51ClusterLayout.DENZA_N9_FINGERPRINT)
        val (base, full, side) = listOf(DiLink51ClusterLayout.BASE, DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.SIDE)
            .map { display(it, "w2560dp-h720dp-mdpi") }
        try {
            assertEquals(side, ClusterMapPresentation.findDisplay(context, DiLink51ClusterLayout.Theme.SCENARIO)?.displayId)
            assertEquals(full, ClusterMapPresentation.findDisplay(context, DiLink51ClusterLayout.Theme.MAP)?.displayId)
        } finally {
            listOf(side, full, base).forEach { ShadowDisplayManager.removeDisplay(it) }
            org.robolectric.shadows.ShadowBuild.setFingerprint(original)
        }
    }

    @Test fun legacyFirmwareKeepsOriginalBaseDisplayPreference() {
        val base = display("fission_bg_XDJAScreenProjection")
        val shared = display("shared_fission_bg_XDJAScreenProjection_0")
        try {
            assertEquals(base, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(shared)
            ShadowDisplayManager.removeDisplay(base)
        }
    }

    @Test fun baseDisplayStillWorksWhenNoSharedLayerExists() {
        val base = display("fission_bg_XDJAScreenProjection")
        try {
            assertEquals(base, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(base)
        }
    }

    @Test fun unrelatedPresentationDisplayIsNotUsedForTheCluster() {
        val other = display("Passenger display")
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(other)
        }
    }
    @Test fun dilink4MeasuredProjectionIsSelected() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun dilink3SmallerProjectionDisplayIsSelected() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1280dp-h480dp-mdpi")
        try {
            assertEquals(id, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun dilink4WrongGeometryIsRejected() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1280dp-h720dp-mdpi")
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun dilink4RequiresPresentationFlag() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        shadowOf(manager.getDisplay(id)).setFlags(0)
        try {
            assertNull(ClusterMapPresentation.findDisplay(context))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun existingDilink5DisplayKeepsPriority() {
        val legacy = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        val current = display(DiLink51ClusterLayout.BASE)
        try {
            assertEquals(current, ClusterMapPresentation.findDisplay(context)?.displayId)
        } finally {
            ShadowDisplayManager.removeDisplay(current)
            ShadowDisplayManager.removeDisplay(legacy)
        }
    }

    @Test fun disabledClusterStillReportsDisplayWithoutNavigationReceiver() {
        val enabled = AirPlayPersistence.loadClusterMapEnabled(context)
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        try {
            assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.navigationAvailable(context))
            val report = ClusterMapPresentation.diagnosticReport(context)
            assertTrue(report.contains("clusterEnabled=false"))
            assertTrue(report.contains("navigationReceiverAvailable=false"))
            assertTrue(report.contains("1920x720"))
            assertTrue(report.contains("selectedCluster=$id:${DiLink4ClusterDisplay.NAME}"))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
            AirPlayPersistence.saveClusterMapEnabled(context, enabled)
        }
    }

    @Test fun diagnosticsDistinguishVisibleDisplayFromEligiblePresentation() {
        val id = display(DiLink4ClusterDisplay.NAME, "w1920dp-h720dp-mdpi")
        shadowOf(manager.getDisplay(id)).setFlags(0)
        try {
            val report = ClusterMapPresentation.diagnosticReport(context)
            assertTrue(report.contains("$id:${DiLink4ClusterDisplay.NAME}"))
            assertTrue(report.contains("selectedCluster=none"))
        } finally {
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun legacyHolderSurfaceIsReportedAsPhysicalOutputWithoutTakingItsOwnership() {
        val id = display("fission_bg_XDJAScreenProjection")
        val reported = mutableListOf<android.view.Surface?>()
        val presentation = ClusterMapPresentation(context, manager.getDisplay(id)) { reported += it }
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        val holder = org.mockito.Mockito.mock(android.view.SurfaceHolder::class.java)
        org.mockito.Mockito.`when`(holder.surface).thenReturn(surface)
        fun find(view: android.view.View): android.view.SurfaceView? {
            if (view is android.view.SurfaceView) return view
            if (view is android.view.ViewGroup) for (child in 0 until view.childCount) {
                find(view.getChildAt(child))?.let { return it }
            }
            return null
        }
        try {
            presentation.create()
            val view = find(presentation.window!!.decorView) ?: error("Legacy route must use SurfaceView")
            val callbacks = (view.holder as org.robolectric.shadows.ShadowSurfaceView.FakeSurfaceHolder).callbacks
            callbacks.forEach { it.surfaceCreated(holder) }
            assertSame(surface, presentation.outputSurface)
            assertSame(surface, reported.last())
            presentation.setMapVisible(false)
            assertFalse(presentation.mapVisible)
            presentation.setMapVisible(true)
            assertTrue(presentation.mapVisible)
            callbacks.forEach { it.surfaceDestroyed(holder) }
            assertNull(presentation.outputSurface)
            assertNull(reported.last())
            org.mockito.Mockito.verify(surface, org.mockito.Mockito.never()).release()
        } finally {
            presentation.dismiss()
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun musicUpdatesAndFadesPreserveTheActiveMapSurface() {
        val id = display("fission_bg_xdjaVirtualSurface", "w1280dp-h480dp-mdpi")
        val reported = mutableListOf<android.view.Surface?>()
        val presentation = ClusterMapPresentation(context, manager.getDisplay(id)) { reported += it }
        val surface = org.mockito.Mockito.mock(android.view.Surface::class.java)
        val holder = org.mockito.Mockito.mock(android.view.SurfaceHolder::class.java)
        org.mockito.Mockito.`when`(holder.surface).thenReturn(surface)
        fun find(view: android.view.View): android.view.SurfaceView? {
            if (view is android.view.SurfaceView) return view
            if (view is android.view.ViewGroup) for (child in 0 until view.childCount) {
                find(view.getChildAt(child))?.let { return it }
            }
            return null
        }
        try {
            presentation.show()
            val view = find(presentation.window!!.decorView) ?: error("Legacy route must use SurfaceView")
            val callbacks = (view.holder as org.robolectric.shadows.ShadowSurfaceView.FakeSurfaceHolder).callbacks
            callbacks.forEach { it.surfaceCreated(holder) }
            assertSame(surface, presentation.outputSurface)
            assertSame(surface, reported.last())
            presentation.setStreamActive(true)
            ClusterMusicSettings.saveEnabled(context, true)
            ClusterMusicSettings.savePermanent(context, true)
            val count = reported.size
            repeat(10) { index ->
                val art = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                ClusterMusicState.publish(com.shilapi.xcertplay.media.CarPlayNowPlaying(
                    title = "Track $index", artworkTransferId = index), art)
                ClusterMusicSettings.save(context, "size", 291 + index)
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
                assertSame(surface, presentation.outputSurface)
                assertTrue(presentation.isShowing)
                assertTrue(presentation.mapVisible)
                assertEquals(count, reported.size)
            }
            ClusterMusicSettings.saveEnabled(context, false)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertSame(surface, presentation.outputSurface)
            callbacks.forEach { it.surfaceDestroyed(holder) }
            assertNull(presentation.outputSurface)
            assertNull(reported.last())
            org.mockito.Mockito.verify(surface, org.mockito.Mockito.never()).release()
        } finally {
            presentation.dismiss()
            ClusterMusicState.publish(com.shilapi.xcertplay.media.CarPlayNowPlaying(), null)
            context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            ShadowDisplayManager.removeDisplay(id)
        }
    }

}
