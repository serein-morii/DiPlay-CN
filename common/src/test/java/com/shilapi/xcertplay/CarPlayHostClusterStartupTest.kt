package com.shilapi.xcertplay

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostClusterStartupTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private lateinit var session: AirPlaySession
    private val displays = mutableListOf<Int>()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        session = mock(AirPlaySession::class.java)
        `when`(controller.activeAirPlaySession()).thenReturn(session)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        set("controller", controller)
        set("sink", sink)
        set("activeAirPlaySession", session)
        CarPlayBackgroundSession.store(controller, sink, 800, 480, activity,
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { it() }
    }

    @After fun tearDown() {
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        ClusterActivityOutput.stop(activity)
        CarPlayBackgroundSession.clear()
        displays.forEach(ShadowDisplayManager::removeDisplay)
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (get(name) as ExecutorService).shutdownNow()
        }
        MapMirrors.streamAspect = MapMirrors.PHYSICAL_STREAM_ASPECT
    }

    @Test fun anAvailableDisplayWaitsForTheMainFrameInsteadOfSessionActive() {
        display()
        mockConstruction(ClusterMapPresentation::class.java).use { windows ->
            invoke("ensureClusterPresentation")
            listener().onSessionActive(session)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(windows.constructed().isEmpty())

            firstFrame()
            assertEquals(1, windows.constructed().size)
            verify(windows.constructed().single()).show()
            firstFrame()
            assertEquals(1, windows.constructed().size)
        }
    }

    @Test fun aLateMagicSentryDisplayReconnectsToReplaceTheVirtualStream() {
        val config = invoke("clusterDisplayConfig") as AirPlayDisplayConfig
        assertEquals(1280, config.widthPixels)
        assertEquals(720, config.heightPixels)
        firstFrame()
        mockConstruction(ClusterMapPresentation::class.java).use { windows ->
            displayAdded(display())
            assertEquals(1, windows.constructed().size)
            assertTrue(get("reconnectScheduled") as Boolean)
            assertEquals(0, get("restartGeneration"))
            assertSame(controller, get("controller"))
            assertEquals(16.0 / 9, MapMirrors.streamAspect, 0.0001)
            val native = invoke("clusterDisplayConfig") as AirPlayDisplayConfig
            assertEquals(1920, native.widthPixels)
            assertEquals(720, native.heightPixels)
        }
    }

    @Test fun aLateDiLink3DisplayReconnectsForItsDifferentNativeSize() {
        val virtual = invoke("clusterDisplayConfig") as AirPlayDisplayConfig
        assertEquals(1280, virtual.widthPixels)
        assertEquals(720, virtual.heightPixels)
        firstFrame()
        mockConstruction(ClusterMapPresentation::class.java).use {
            displayAdded(display(DiLink4ClusterDisplay.NAME, "w1280dp-h480dp-mdpi"))
            assertTrue(get("reconnectScheduled") as Boolean)
            val native = invoke("clusterDisplayConfig") as AirPlayDisplayConfig
            assertNotEquals(virtual.widthPixels to virtual.heightPixels,
                native.widthPixels to native.heightPixels)
        }
    }

    @Test fun anAlreadyNegotiatedNativeDisplayCanReappearWithoutReconnecting() {
        val original = display()
        val native = invoke("clusterDisplayConfig") as AirPlayDisplayConfig
        assertEquals(1920, native.widthPixels)
        assertEquals(720, native.heightPixels)
        ShadowDisplayManager.removeDisplay(original)
        displays.remove(original)
        firstFrame()
        mockConstruction(ClusterMapPresentation::class.java).use { windows ->
            displayAdded(display())
            assertEquals(1, windows.constructed().size)
            assertFalse(get("reconnectScheduled") as Boolean)
            assertSame(controller, get("controller"))
        }
    }

    @Test fun aDisplayAddedDuringTheHandshakeRequestsNativeRecoveryButStillWaitsForTheMainFrame() {
        invoke("clusterDisplayConfig")
        mockConstruction(ClusterMapPresentation::class.java).use { windows ->
            displayAdded(display())
            assertTrue(windows.constructed().isEmpty())
            assertTrue(get("reconnectScheduled") as Boolean)
            firstFrame()
            assertEquals(1, windows.constructed().size)
        }
    }

    @Test fun retainedClusterSurfaceIsNotAttachedBeforeTheNewMainFrame() {
        val cluster = mock(Surface::class.java)
        val main = mock(Surface::class.java)
        invokeSurface("onClusterSurface", cluster)
        invokeSurface("attachSurface", main)
        verify(sink).setSurface(110, main)
        verify(sink, never()).setSurface(111, cluster)
        firstFrame()
        verify(sink).setSurface(111, cluster)
        assertFalse(get("reconnectScheduled") as Boolean)
    }

    @Test fun staleSessionsAndControllerGenerationsCannotLaunchTheCluster() {
        display()
        val stale = mock(AirPlaySession::class.java)
        `when`(stale.hasRenderedMainFrame).thenReturn(true)
        mockConstruction(ClusterMapPresentation::class.java).use { windows ->
            listener().onVideoFrameRendered(stale)
            `when`(session.hasRenderedMainFrame).thenReturn(true)
            listener(-1).onVideoFrameRendered(session)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(windows.constructed().isEmpty())
        }
    }

    @Test fun aReplacementSessionDetachesTheOldClusterSurfaceUntilItsOwnMainFrame() {
        val surface = mock(Surface::class.java)
        set("clusterSurface", surface)
        firstFrame()
        verify(sink).setSurface(111, surface)
        val replacement = mock(AirPlaySession::class.java)
        listener().onSessionActive(replacement)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink).clearSurface(111, surface)
        verify(sink, times(1)).setSurface(111, surface)
        `when`(replacement.hasRenderedMainFrame).thenReturn(true)
        listener().onVideoFrameRendered(replacement)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink, times(2)).setSurface(111, surface)
    }

    @Test fun aFrameReportedBeforeSessionActiveIsRememberedWhenTheHostCatchesUp() {
        display()
        set("activeAirPlaySession", null)
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        mockConstruction(ClusterMapPresentation::class.java).use { windows ->
            listener().onVideoFrameRendered(session)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(windows.constructed().isEmpty())
            listener().onSessionActive(session)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, windows.constructed().size)
        }
    }

    @Test fun lateAdbRouteConfirmationReconnectsWhenTheSessionHasOnlyTheVirtualStream() {
        val virtual = invoke("clusterDisplayConfig") as AirPlayDisplayConfig
        assertEquals(1280, virtual.widthPixels)
        AirPlayPersistence.saveAdbClusterEnabled(activity, true)
        confirmAdbRoute()
        assertFalse(get("adbClusterConfigured") as Boolean)
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        val surface = mock(Surface::class.java)
        invokeSurface("onClusterSurface", surface)
        verify(sink, never()).setSurface(111, surface)
        assertTrue(get("reconnectScheduled") as Boolean)
        assertSame(controller, get("controller"))
    }

    @Test fun lateAdbRouteConfirmationKeepsAnAlreadyNegotiatedNativeStream() {
        AirPlayPersistence.saveAdbClusterEnabled(activity, true)
        val native = invoke("clusterDisplayConfig") as AirPlayDisplayConfig
        assertEquals(1920, native.widthPixels)
        assertEquals(720, native.heightPixels)
        val window = confirmAdbRoute()
        assertTrue(get("adbClusterConfigured") as Boolean)
        val surface = mock(Surface::class.java)
        ClusterActivityOutput.attach(window, surface)
        invokeSurface("onClusterSurface", surface)
        verify(sink, never()).setSurface(111, surface)
        assertFalse(get("reconnectScheduled") as Boolean)
        firstFrame()
        verify(sink).setSurface(111, surface)
        verify(sink, never()).clearSurface(111, surface)
        assertFalse(get("reconnectScheduled") as Boolean)
        assertSame(controller, get("controller"))
    }

    @Test fun adoptingAnAlreadyRenderedBackgroundSessionDoesNotWaitForAnotherFrame() {
        display()
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        set("smoothVideo", false)
        set("activeAirPlaySession", null)
        set("controller", null)
        mockConstruction(ClusterMapPresentation::class.java).use { windows ->
            assertEquals(true, invoke("adoptBackgroundSession"))
            assertEquals(1, windows.constructed().size)
            assertSame(session, get("activeAirPlaySession"))
        }
    }

    @Test fun disablingClusterOutputStillPreventsLaunchAfterMainFrame() {
        display()
        AirPlayPersistence.saveClusterMapEnabled(activity, false)
        mockConstruction(ClusterMapPresentation::class.java).use { windows ->
            firstFrame()
            assertTrue(windows.constructed().isEmpty())
        }
    }

    @Test fun adbProfileIsNegotiatedBeforeTheClusterWindowConfirmsItsRoute() {
        AirPlayPersistence.saveAdbClusterEnabled(activity, true)
        assertFalse(ClusterActivityOutput.hasConfirmedRoute())
        val config = invoke("clusterDisplayConfig") as AirPlayDisplayConfig
        assertEquals(1920, config.widthPixels)
        assertEquals(720, config.heightPixels)
        invoke("ensureClusterPresentation")
        assertFalse(ClusterActivityOutput.launchPending)
        assertFalse((get("clusterLaunchGate") as ClusterLaunchGate).awaitingFocus)
    }

    @Test fun dilink51ProfileDoesNotWaitForItsPhysicalDisplay() {
        ShadowBuild.setFingerprint(DiLink51ClusterLayout.FINGERPRINT)
        assertNull(ClusterMapPresentation.findDisplay(activity))
        assertEquals(DiLink51ClusterLayout.streamConfig(), invoke("clusterDisplayConfig"))
    }

    @Test fun losingTheSessionCancelsAPendingAdbLaunch() {
        AirPlayPersistence.saveAdbClusterEnabled(activity, true)
        firstFrame()
        val gate = get("clusterLaunchGate") as ClusterLaunchGate
        assertTrue(gate.awaitingFocus)
        set("activeAirPlaySession", null)
        invoke("onClusterLaunchTick")
        assertFalse(gate.awaitingFocus)
        assertFalse(ClusterActivityOutput.launchPending)
    }

    private fun firstFrame() {
        `when`(session.hasRenderedMainFrame).thenReturn(true)
        listener().onVideoFrameRendered(session)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun confirmAdbRoute(): AdbClusterActivity {
        ClusterActivityOutput.bind(activity, 0) { }
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        ClusterActivityOutput::class.java.getDeclaredField("launchToken")
            .apply { isAccessible = true }.set(ClusterActivityOutput, token)
        ClusterActivityOutput::class.java.getDeclaredField("expectedDisplay")
            .apply { isAccessible = true }.setInt(ClusterActivityOutput, 1)
        val window = Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        assertTrue(ClusterActivityOutput.confirm(window, token, 1))
        return window
    }

    private fun display(name: String = "fission_magicsentry", qualifiers: String = "w1920dp-h720dp-mdpi"): Int =
        ShadowDisplayManager.addDisplay(qualifiers, 5).also { id ->
            displays += id
            shadowOf(activity.getSystemService(DisplayManager::class.java).getDisplay(id)).apply {
                setName(name)
                setFlags(Display.FLAG_PRESENTATION)
            }
        }

    private fun displayAdded(id: Int) = (get("clusterDisplayListener") as DisplayManager.DisplayListener).onDisplayAdded(id)
    private fun listener(generation: Int = 0) = activity.javaClass
        .getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(activity, generation) as AirPlaySessionListener
    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun invokeSurface(name: String, surface: Surface) = activity.javaClass.getDeclaredMethod(name, Surface::class.java)
        .apply { isAccessible = true }.invoke(activity, surface)
    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
}
