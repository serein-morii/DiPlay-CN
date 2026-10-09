package com.shilapi.xcertplay

import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostDisplaySizeTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controllerConstruction: MockedConstruction<CarPlayController>
    private val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        AirPlayPersistence.saveAdaptPipResolution(activity, false)
        // Source-only tests have no provisioned local authentication identity.
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        // Exercise host startup without launching vendor-service workers or real transports.
        controllerConstruction = mockConstruction(CarPlayController::class.java)
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        setField("activeDisplaySize", size(1920, 990))
    }

    @After fun tearDown() {
        (getField("shuttingDown") as AtomicBoolean).set(true)
        (getField("mainHandler") as Handler).removeCallbacksAndMessages(null)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(null)
        (getField("controller") as? CarPlayController)?.let {
            CarPlayMediaKeys.detach(it)
            it.close()
            it.awaitClosed(1000)
        }
        (getField("sink") as? AndroidMediaSink)?.close()
        (getField("teardownExecutor") as ExecutorService).shutdownNow()
        (getField("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllerConstruction.close()
    }

    @Test fun surroundViewOpenAndCloseKeepsTheNegotiatedCanvas() {
        val display = startSession()
        applySize(1920, 942)
        assertEquals(size(1920, 942), getField("activeDisplaySize"))
        applySize(1920, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
        assertEquals(2, keepLogs())
    }

    @Test fun aNarrowWindowIsNotTreatedAsScreenRotation() {
        val display = startSession()
        applySize(700, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
        assertEquals(1, keepLogs())
    }

    @Test fun aNarrowWindowWithAdaptPipResolutionTriggersReconnect() {
        AirPlayPersistence.saveAdaptPipResolution(activity, true)
        val display = startSession()
        applySize(700, 990)
        assertEquals(1, getField("restartGeneration"))
        assertNull(getField("sessionDisplay"))
    }

    @Test fun resumingASquareCanvasInTheSameWindowDoesNotReconnect() {
        AirPlayPersistence.saveAdaptPipResolution(activity, true)
        val display = startSession(windowHeight = 1080, canvasHeight = 1920)
        val video = object : TextureView(activity) {
            override fun post(action: Runnable): Boolean = Handler(Looper.getMainLooper()).post(action)
        }.apply { layout(0, 0, 1920, 1080) }
        setField("videoView", video)

        invoke("onResume")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertSame(display, getField("sessionDisplay"))
        assertEquals(size(1920, 1080), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertNull(getField("pendingDisplaySize"))
    }

    @Test fun aRealPipAspectChangeStillReconnectsWithASquareCanvas() {
        AirPlayPersistence.saveAdaptPipResolution(activity, true)
        startSession(windowHeight = 1080, canvasHeight = 1920)

        applySize(700, 1080)

        assertEquals(1, getField("restartGeneration"))
        assertNull(getField("sessionDisplay"))
    }


    @Test fun connectingInANarrowWindowRebuildsWhenTheCameraCloses() {
        startSession(windowWidth = 700)
        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertNull(getField("sessionDisplay"))
    }

    @Test fun connectingInAReducedHeightWindowRebuildsWhenTheCameraCloses() {
        startSession(windowHeight = 942)
        applySize(1920, 990)
        assertEquals(1, getField("restartGeneration"))
        assertNull(getField("sessionDisplay"))
    }

    @Test fun aScaledDownCanvasKeepsTheSessionWhenTheOriginalWindowReturns() {
        val display = startSession(canvasWidth = 1536, canvasHeight = 792)
        applySize(700, 990)
        applySize(1920, 990)
        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun aScaledUpCanvasStillRebuildsWhenTheStartupWindowGrows() {
        startSession(windowWidth = 700, canvasWidth = 1400, canvasHeight = 1980)
        applySize(1000, 990)
        assertEquals(1, getField("restartGeneration"))
        assertNull(getField("sessionDisplay"))
    }

    @Test fun actualScreenRotationStillRebuildsTheSession() {
        startSession(rotation = Surface.ROTATION_90)
        applySize(990, 1920)
        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        assertNull(getField("sessionDisplay"))
    }

    @Test fun rotationIsHandledEvenIfTheViewSizeIsUnchanged() {
        startSession(rotation = Surface.ROTATION_180)
        scheduleSize(1920, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test fun dismissingAnUnchangedSettingsMenuDoesNotScheduleADisplayRestart() {
        val display = startSession(rotation = Surface.ROTATION_180)
        val video = object : TextureView(activity) {
            override fun post(action: Runnable): Boolean = Handler(Looper.getMainLooper()).post(action)
        }.apply { layout(0, 0, 1920, 990) }
        setField("videoView", video)

        invoke("openSettingsMenu")
        invoke("cancelSettingsEdits")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
        assertNull(getField("pendingDisplaySize"))
    }

    @Test fun anExplicitBarLayoutChangeStillRebuildsTheSession() {
        startSession()
        setField("hideTopBar", false)
        applySize(1920, 942)
        assertEquals(1, getField("restartGeneration"))
    }

    @Test fun resumingReadsSavedBarsAndRebuildsEvenWhenTheWindowSizeIsUnchanged() {
        startSession()
        // 测试窗口未挂载，使用主线程队列执行布局后的刷新。
        val video = object : TextureView(activity) {
            override fun post(action: Runnable): Boolean = Handler(Looper.getMainLooper()).post(action)
        }.apply { layout(0, 0, 1920, 990) }
        setField("videoView", video)
        AirPlayPersistence.saveHideTopBar(activity, true)
        AirPlayPersistence.saveHideBottomBar(activity, false)

        invoke("onResume")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertEquals(true, getField("hideTopBar"))
        assertEquals(false, getField("hideBottomBar"))
        assertWindowBars(true, false)
        assertEquals(1, getField("restartGeneration"))
        assertTrue(getField("handshakeResetInProgress") as Boolean)
    }

    @Test fun resumingWithUnchangedBarsKeepsTheSession() {
        val display = startSession()

        invoke("onResume")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertSame(display, getField("sessionDisplay"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun resumingWhileEditingDoesNotOverwriteTheBarPreview() {
        startSession()
        setField("menuOpen", true)
        setField("hideTopBar", false)
        setField("hideBottomBar", true)

        invoke("onResume")

        assertEquals(false, getField("hideTopBar"))
        assertEquals(true, getField("hideBottomBar"))
        assertWindowBars(false, true)
        assertTrue(AirPlayPersistence.loadHideTopBar(activity))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun fullscreenApplicationSupportsAllFourBarCombinations() {
        for (hideTop in listOf(false, true)) {
            for (hideBottom in listOf(false, true)) {
                setField("hideTopBar", hideTop)
                setField("hideBottomBar", hideBottom)

                invoke("applyFullscreenMode")

                assertWindowBars(hideTop, hideBottom)
            }
        }
    }

    @Test fun menuBarChangesPreviewWithoutSavingAndCancelRestoresTheSavedValues() {
        invoke("loadPersistedSettings")
        setField("settingsBaseline", invoke("captureSettingsBaseline"))
        setField("menuOpen", true)
        val controls = invoke("buildFullscreenSection") as ViewGroup

        menuBarSwitch(controls, R.string.hide_the_navigation_bar).performClick()

        assertEquals(false, getField("hideBottomBar"))
        assertWindowBars(true, false)
        assertTrue(AirPlayPersistence.loadHideBottomBar(activity))

        invoke("cancelSettingsEdits")

        assertEquals(true, getField("hideBottomBar"))
        assertWindowBars(true, true)
        assertTrue(AirPlayPersistence.loadHideBottomBar(activity))
    }

    @Test fun savingMenuBarsPreservesAnIndependentCombination() {
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        invoke("loadPersistedSettings")
        setField("menuOpen", true)
        val controls = invoke("buildFullscreenSection") as ViewGroup
        menuBarSwitch(controls, R.string.hide_the_status_bar).performClick()

        invoke("saveSettingsAndReconnect")

        assertFalse(AirPlayPersistence.loadHideTopBar(activity))
        assertTrue(AirPlayPersistence.loadHideBottomBar(activity))
        assertEquals(false, getField("menuOpen"))
    }

    @Test fun quickOpenAndCloseCancelsThePendingShrink() {
        startSession()
        scheduleSize(700, 990)
        scheduleSize(1920, 990)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertNull(getField("pendingDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test fun anOngoingHandshakeResetOnlyRecordsTheNewSize() {
        setField("handshakeResetInProgress", true)
        applySize(700, 990)
        assertEquals(size(700, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
    }

    @Test @Config(sdk = [30]) fun rotatingBackDuringTeardownStartsWithTheLatestSize() {
        allowStartup()
        startSession(windowWidth = 2250, windowHeight = 1080)
        applySize(1080, 2250)
        assertTrue(getField("handshakeResetInProgress") as Boolean)
        applySize(2250, 1080)

        finishTeardown()

        assertSessionSize(2250, 1080)
        assertEquals(1, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test @Config(sdk = [30]) fun teardownWaitsForAPendingRotationToSettle() {
        allowStartup()
        startSession(windowWidth = 2250, windowHeight = 1080)
        applySize(1080, 2250)
        scheduleSize(2250, 1080)

        finishTeardown()
        assertNull(getField("sessionDisplay"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertSessionSize(2250, 1080)
        assertNull(getField("pendingDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test @Config(sdk = [30]) fun cancellingAPendingRotationResumesStartup() {
        allowStartup()
        startSession(windowWidth = 2250, windowHeight = 1080)
        applySize(1080, 2250)
        scheduleSize(2250, 1080)
        finishTeardown()
        assertNull(getField("sessionDisplay"))

        scheduleSize(1080, 2250)

        assertSessionSize(1080, 2250)
        assertNull(getField("pendingDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test @Config(sdk = [30]) fun aPendingSameSizeRotationResumesAfterTeardown() {
        allowStartup()
        startSession(rotation = Surface.ROTATION_180)
        scheduleSize(1920, 990)
        activity.javaClass.getDeclaredMethod("restartCarPlay", String::class.java)
            .apply { isAccessible = true }.invoke(activity, "Test reconnect")
        finishTeardown()
        assertNull(getField("sessionDisplay"))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

        assertSessionSize(1920, 990)
        assertNull(getField("pendingDisplaySize"))
        assertEquals(1, getField("restartGeneration"))
    }

    @Test @Config(sdk = [30]) fun teardownRechecksStartupPrerequisites() {
        allowStartup()
        startSession()
        applySize(990, 1920)
        setField("vpnReady", false)

        finishTeardown()

        assertNull(getField("controller"))
        assertNull(getField("sessionDisplay"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test fun initialSizeDetectionKeepsTheNormalStartupPath() {
        setField("activeDisplaySize", null)
        applySize(1920, 990)
        assertEquals(size(1920, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertEquals(0, keepLogs())
    }

    @Test fun resizeWithoutASessionRecordsTheSizeWithoutAnotherTeardown() {
        applySize(700, 990)
        assertEquals(size(700, 990), getField("activeDisplaySize"))
        assertEquals(0, getField("restartGeneration"))
        assertFalse(getField("handshakeResetInProgress") as Boolean)
    }

    @Test fun textureTransformFitsTheNegotiatedCanvas() {
        startSession()
        val view = TextureView(activity).apply { layout(0, 0, 1920, 942) }
        setField("videoView", view)
        activity.javaClass.getDeclaredMethod("updateVideoLayout", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 1920, 942)
        val points = floatArrayOf(0f, 0f, 1920f, 942f)
        view.getTransform(Matrix()).mapPoints(points)
        val content = CarPlayVideoLayout.fit(1920, 990, 1920, 942)
        assertArrayEquals(floatArrayOf(content.left, content.top, content.left + content.width,
            content.top + content.height), points, 0.001f)
    }

    @Test fun touchesStartingInABarStaySuppressedUntilRelease() {
        startSession()
        val view = View(activity).apply { layout(0, 0, 1920, 942) }
        touch(view, MotionEvent.ACTION_DOWN, 1f, 471f)
        assertEquals(true, getField("touchOutsideContent"))
        touch(view, MotionEvent.ACTION_MOVE, 960f, 471f)
        assertEquals(true, getField("touchOutsideContent"))
        touch(view, MotionEvent.ACTION_UP, 960f, 471f)
        assertEquals(false, getField("touchOutsideContent"))
        touch(view, MotionEvent.ACTION_DOWN, 960f, 471f)
        assertEquals(false, getField("touchOutsideContent"))
    }

    @Test fun adoptingABackgroundSessionPreservesItsCanvasOnResize() {
        val display = CarPlaySessionDisplay(1536, 792, Surface.ROTATION_0, true, true, 1920, 990)
        val sink = AndroidMediaSink()
        val controller = CarPlayController(activity,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1536, heightPixels = 792)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {})
        try {
            CarPlayBackgroundSession.store(controller, sink, 1920, 990, Any(), display) {}
            val adopted = activity.javaClass.getDeclaredMethod("adoptBackgroundSession")
                .apply { isAccessible = true }.invoke(activity)
            assertEquals(true, adopted)
            applySize(700, 990)
            assertSame(controller, getField("controller"))
            assertSame(sink, getField("sink"))
            assertEquals(display, getField("sessionDisplay"))
            assertEquals(display, CarPlayBackgroundSession.snapshot()?.display)
            assertFalse(controller.isClosed())
            assertEquals(0, getField("restartGeneration"))
            applySize(1920, 990)
            assertSame(display, getField("sessionDisplay"))
            assertEquals(0, getField("restartGeneration"))
            applySize(2000, 990)
            assertEquals(1, getField("restartGeneration"))
            assertNull(getField("sessionDisplay"))
        } finally {
            controller.close()
            controller.awaitClosed(1000)
            sink.close()
        }
    }

    @Test fun adoptingBackgroundClusterRestoresNativeAdbFlagButRejectsTheVirtualFallback() {
        AirPlayPersistence.saveAdbClusterEnabled(activity, true)
        val sink = AndroidMediaSink()
        val display = CarPlaySessionDisplay(1920, 990, Surface.ROTATION_0, true, true, 1920, 990)
        try {
            for ((clusterSize, native) in listOf((1920 to 720) to true, (1280 to 720) to false, null to false)) {
                val controller = org.mockito.Mockito.mock(CarPlayController::class.java)
                org.mockito.Mockito.`when`(controller.configuredClusterSize()).thenReturn(clusterSize)
                CarPlayBackgroundSession.store(controller, sink, 1920, 990, Any(), display) {}
                assertEquals(true, invoke("adoptBackgroundSession"))
                assertEquals("Adopted $clusterSize must keep the native/virtual distinction", native,
                    getField("adbClusterConfigured"))
            }
        } finally {
            AirPlayPersistence.saveAdbClusterEnabled(activity, false)
            sink.close()
        }
    }

    private fun startSession(
        rotation: Int = Surface.ROTATION_0,
        windowWidth: Int = 1920,
        windowHeight: Int = 990,
        canvasWidth: Int = windowWidth,
        canvasHeight: Int = windowHeight,
    ): CarPlaySessionDisplay =
        CarPlaySessionDisplay(canvasWidth, canvasHeight, rotation, true, true, windowWidth, windowHeight).also {
            setField("activeDisplaySize", size(windowWidth, windowHeight))
            setField("sessionDisplay", it)
        }

    private fun keepLogs(): Int = ShadowLog.getLogsForTag("xcertplay-usb").count {
        it.msg.contains("keeping CarPlay session")
    }

    private fun allowStartup() {
        setField("airPlayIdentity", AirPlayIdentity.generate())
        setField("mfiTarget", MfiTarget.LOCAL)
        setField("vpnReady", true)
        setField("microphonePermissionResolved", true)
    }

    private fun finishTeardown() {
        (getField("teardownExecutor") as PausedExecutorService).runAll()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertSessionSize(width: Int, height: Int) {
        val display = getField("sessionDisplay") as? CarPlaySessionDisplay
        assertNotNull("CarPlay must resume after the display settles", display)
        assertEquals(width, display!!.windowWidth)
        assertEquals(height, display.windowHeight)
        assertEquals(width, display.width)
        assertEquals(height, display.height)
    }

    private fun size(width: Int, height: Int): Any = sizeClass
        .getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .apply { isAccessible = true }.newInstance(width, height)

    private fun applySize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("applyDisplaySize", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size(width, height))
    }

    private fun scheduleSize(width: Int, height: Int) {
        activity.javaClass.getDeclaredMethod("scheduleDisplaySize", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, width, height)
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0, 0, action, x, y, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, view, event)
        } finally {
            event.recycle()
        }
    }

    private fun getField(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)

    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    @Suppress("DEPRECATION")
    private fun assertWindowBars(hideTop: Boolean, hideBottom: Boolean) {
        assertEquals(hideTop, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        assertEquals(hideBottom, activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION != 0)
    }

    private fun menuBarSwitch(controls: ViewGroup, label: Int): Switch = (0 until controls.childCount)
        .mapNotNull { controls.getChildAt(it) as? ViewGroup }
        .flatMap { row -> (0 until row.childCount).map { row.getChildAt(it) } }
        .filterIsInstance<Switch>().single { it.contentDescription == activity.getString(label) }

    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
