package com.shilapi.xcertplay

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.android.util.concurrent.PausedExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostSettingsTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controllers: MockedConstruction<CarPlayController>

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        controllers = mockConstruction(CarPlayController::class.java)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        setField("teardownExecutor", PausedExecutorService())
        setField("airPlayIdentity", AirPlayIdentity.generate())
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1920, 990)
        setField("activeDisplaySize", size)
        CarPlayBackgroundSession::class.java.getDeclaredField("owner").apply { isAccessible = true }
            .set(CarPlayBackgroundSession, activity)
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.USB_CH341)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.WIFI_P2P)
        invoke("loadPersistedSettings")
        invoke("buildContentView")
    }

    @After fun tearDown() {
        (field("shuttingDown") as AtomicBoolean).set(true)
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
        controllers.close()
    }

    @Test fun configuredFingerCountsOpenTheMountedMenuWithoutLeavingCarPlay() {
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(activity))
        for (fingers in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, fingers)
            invoke("loadPersistedSettings")
            gesture(fingers)
            assertTrue(field("menuOpen") as Boolean)
            assertEquals(View.VISIBLE, menu().visibility)
            assertNotNull(menu().parent)
            assertEquals(View.GONE, (field("gestureOverlay") as View).visibility)
            assertNull(shadowOf(activity).nextStartedActivity)
            invoke("cancelSettingsEdits")
        }
    }

    @Test fun swipeTargetHomeOpensDiPlaySettingsInsteadOfTheMenu() {
        AirPlayPersistence.saveSwipeOpensFullSettings(activity, true)
        invoke("loadPersistedSettings")
        gesture(3)
        assertFalse(field("menuOpen") as Boolean)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, started.component!!.className)
        assertEquals("settings", started.getStringExtra("page"))
    }

    @Test
    @Config(qualifiers = "en-w1920dp-h1080dp-mdpi")
    fun wideOverlayMenuKeepsConnectionControlsTappable() {
        invoke("openSettingsMenu")
        val root = menu()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1920, 1080)
        val wireless = views(root).filterIsInstance<android.widget.Switch>()
            .first { it.contentDescription == activity.getString(R.string.wireless_carplay_transport) }
        assertTrue(wireless.height > 0)
        assertEquals(View.VISIBLE, wireless.visibility)
        setField("wirelessEnabled", false)
        wireless.isChecked = false
        wireless.performClick()
        assertTrue(field("wirelessEnabled") as Boolean)
        assertTrue(resolutionSlider().width >= 0)
    }

    @Test fun openFullSettingsButtonAlwaysOpensTheHomeSettingsPage() {
        invoke("openSettingsMenu")
        views(menu()).filterIsInstance<Button>()
            .first { it.text == activity.getString(R.string.open_full_settings) }
            .performClick()
        assertFalse(field("menuOpen") as Boolean)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, started.component!!.className)
        assertEquals("settings", started.getStringExtra("page"))
    }

    @Test fun overlaySwipeTargetChoiceIsKeptWithoutSavingTheMenu() {
        assertFalse(AirPlayPersistence.loadSwipeOpensFullSettings(activity))
        invoke("openSettingsMenu")
        views(menu()).filterIsInstance<RadioButton>()
            .first { it.text == activity.getString(R.string.settings_swipe_target_full) }
            .performClick()
        assertTrue(AirPlayPersistence.loadSwipeOpensFullSettings(activity))
        invoke("cancelSettingsEdits")
        assertTrue(AirPlayPersistence.loadSwipeOpensFullSettings(activity))
    }

    @Test fun wrongFingerCountsAndNonDownwardSwipesDoNotOpenTheMenu() {
        for (configured in 2..4) {
            AirPlayPersistence.saveSettingsGestureFingers(activity, configured)
            invoke("loadPersistedSettings")
            for (actual in 2..4) {
                if (actual == configured) continue
                gesture(actual)
                assertFalse(field("menuOpen") as Boolean)
            }
            gesture(configured, x = 1000f, y = 130f)
            assertFalse(field("menuOpen") as Boolean)
            gesture(configured, y = -500f)
            assertFalse(field("menuOpen") as Boolean)
        }
    }

    @Test fun liftingAFingerCancelsTrackingUntilTheNextGesture() {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_UP, 3, 100f)
        touch(MotionEvent.ACTION_POINTER_DOWN, 3, 100f)
        touch(MotionEvent.ACTION_MOVE, 3, 700f)
        assertFalse(field("menuOpen") as Boolean)
        touch(MotionEvent.ACTION_UP, 1, 700f)
        gesture(3)
        assertTrue(field("menuOpen") as Boolean)
    }

    @Test fun fullSettingsShortcutDiscardsPreviewWithoutRestartingTheSession() {
        val controller = attachController()
        invoke("openSettingsMenu")
        val original = AirPlayPersistence.loadDisplayScalePercent(activity)
        resolutionSlider().progress = 0
        fullSettingsButton().performClick()
        assertFalse(field("menuOpen") as Boolean)
        assertNull(field("settingsBaseline"))
        assertEquals(original, field("displayScalePercent"))
        assertEquals(original, AirPlayPersistence.loadDisplayScalePercent(activity))
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, intent.component!!.className)
        assertEquals("settings", intent.getStringExtra("page"))
    }

    @Test fun returningFromFullSettingsReloadsSavedConnectionPreferences() {
        attachController()
        invoke("openSettingsMenu")
        fullSettingsButton().performClick()
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        AirPlayPersistence.saveWirelessHotspotMode(activity, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(activity, "Updated in full settings")
        invoke("onResume")
        assertEquals(MfiTarget.LOCAL, field("mfiTarget"))
        assertEquals(WirelessHotspotMode.MANUAL, field("wirelessHotspotMode"))
        assertEquals("Updated in full settings", field("manualHotspotSsid"))
    }

    @Test fun openingAndCancellingKeepsTheCurrentControllerAndRestoresControls() {
        val controller = attachController()
        invoke("openSettingsMenu")
        val original = AirPlayPersistence.loadDisplayScaleTenths(activity)
        resolutionSlider().progress = 0
        gestureButton().performClick()
        assertEquals(3, AirPlayPersistence.loadSettingsGestureFingers(activity))
        invoke("cancelSettingsEdits")
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
        assertEquals(original, field("displayScaleTenths"))
        assertEquals(3, field("gestureFingerCount"))
        invoke("openSettingsMenu")
        assertEquals(original * 10 - CarPlayDisplayScale.MIN_PERCENT, resolutionSlider().progress)
        assertEquals(activity.getString(R.string.settings_gesture_fingers, 3), gestureButton().text)
    }

    @Test fun customResolutionSurvivesCancelAndUnrelatedSettingsSave() {
        for (percent in listOf(73, 157, 160)) {
            AirPlayPersistence.saveDisplayScalePercent(activity, percent)
            invoke("openSettingsMenu")
            val slider = resolutionSlider()
            assertEquals(percent - CarPlayDisplayScale.MIN_PERCENT, slider.progress)
            val listener = SeekBar::class.java.getDeclaredField("mOnSeekBarChangeListener")
                .apply { isAccessible = true }.get(slider) as SeekBar.OnSeekBarChangeListener
            listener.onProgressChanged(slider, 0, true)
            assertEquals(percent, AirPlayPersistence.loadDisplayScalePercent(activity))
            invoke("cancelSettingsEdits")
            assertEquals(percent, field("displayScalePercent"))
            assertEquals(percent, AirPlayPersistence.loadDisplayScalePercent(activity))
            invoke("openSettingsMenu")
            invoke("persistMenuSettings")
            assertEquals(percent, AirPlayPersistence.loadDisplayScalePercent(activity))
            invoke("cancelSettingsEdits")
        }
    }

    @Test fun resolutionEndpointLabelsMatchTheActualSliderRange() {
        invoke("openSettingsMenu")
        val labels = views(menu()).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(labels.contains(activity.getString(R.string.custom_resolution_summary, 30)))
        assertTrue(labels.contains(activity.getString(R.string.custom_resolution_summary, 160)))
        assertFalse(labels.contains("2.0x"))
    }

    @Test fun resumingWithTheMenuOpenPreservesUnsavedConnectionEdits() {
        invoke("openSettingsMenu")
        setField("wirelessHotspotMode", WirelessHotspotMode.MANUAL)
        setField("manualHotspotSsid", "Draft hotspot")
        setField("mfiTarget", MfiTarget.LOCAL)
        invoke("onResume")
        assertEquals(WirelessHotspotMode.MANUAL, field("wirelessHotspotMode"))
        assertEquals("Draft hotspot", field("manualHotspotSsid"))
        assertEquals(MfiTarget.LOCAL, field("mfiTarget"))
        invoke("cancelSettingsEdits")
        assertEquals(WirelessHotspotMode.WIFI_P2P, field("wirelessHotspotMode"))
        assertEquals(MfiTarget.USB_CH341, field("mfiTarget"))
    }

    @Test fun lightAppearanceRepaintsAnOpenMenuWithoutLosingDraftState() {
        invoke("openSettingsMenu")
        setField("manualHotspotSsid", "Unsaved hotspot")
        val oldMenu = menu()
        val scroll = views(oldMenu).filterIsInstance<android.widget.ScrollView>().single()
        scroll.scrollTo(0, 120)

        AirPlayPersistence.saveAppAppearance(activity, AppAppearance.LIGHT)
        invoke("refreshAppAppearance")
        shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNotSame(oldMenu, menu())
        assertEquals("Unsaved hotspot", field("manualHotspotSsid"))
        assertEquals(false, field("appNight"))
        // The sheet overlay dims the video; the palette-driven surface is the panel inside it.
        val panel = (menu() as android.view.ViewGroup).getChildAt(0)
        assertEquals(
            DiPlayPalette.LIGHT.overlayBackground,
            (panel.background as android.graphics.drawable.GradientDrawable).color?.defaultColor,
        )
        val heading = views(menu()).filterIsInstance<TextView>()
            .first { it.text == activity.getString(R.string.carplay_settings) }
        assertEquals(DiPlayPalette.LIGHT.overlayPrimaryText, heading.currentTextColor)
    }

    @Test fun savingPersistsSettingsAndRestartsOnce() {
        attachController()
        invoke("openSettingsMenu")
        resolutionSlider().progress = 0
        gestureButton().performClick()
        invoke("saveSettingsAndReconnect")
        assertFalse(field("menuOpen") as Boolean)
        assertEquals(CarPlayDisplayScale.MIN_TENTHS, AirPlayPersistence.loadDisplayScaleTenths(activity))
        assertEquals(4, AirPlayPersistence.loadSettingsGestureFingers(activity))
        assertEquals(1, field("restartGeneration"))
        assertTrue(field("handshakeResetInProgress") as Boolean)
    }

    @Test fun failedStartupWhileMenuIsOpenRecoversOnCancel() {
        attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Authentication failed"))
        assertEquals(0, field("restartGeneration"))
        invoke("cancelSettingsEdits")
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun transportLossWhileMenuIsOpenRecoversOnCancel() {
        attachController()
        invoke("openSettingsMenu")
        val listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 0) as AirPlaySessionListener
        listener.onTransportError("transport lost")
        invoke("cancelSettingsEdits")
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun wifiResetFailureRequiresManualRecoveryAfterCancel() {
        attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        invoke("cancelSettingsEdits")
        assertEquals(View.VISIBLE, (field("wifiRecoveryButton") as View).visibility)
        assertEquals(0, field("restartGeneration"))
        assertFalse(field("reconnectScheduled") as Boolean)
    }

    @Test fun savingWirelessAfterWifiResetFailureStillRequiresManualRecovery() {
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        invoke("saveSettingsAndReconnect")
        assertEquals(View.VISIBLE, (field("wifiRecoveryButton") as View).visibility)
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun savingWiredModeAfterWifiResetFailureRestartsWithTheNewSettings() {
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("Wi-Fi needs a reset", wifiResetRequired = true))
        setField("wirelessEnabled", false)
        invoke("saveSettingsAndReconnect")
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun staleFailureDoesNotRestartWhenTheMenuCloses() {
        val controller = attachController()
        invoke("openSettingsMenu")
        report(CarPlayStatus.Failed("old failure"), generation = -1)
        invoke("cancelSettingsEdits")
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun authenticationChoicesOnlyExposeLocalAndCh341() {
        invoke("openSettingsMenu")
        val options = views(menu()).filterIsInstance<RadioButton>().filter { it.tag is MfiTarget }.toList()
        assertEquals(listOf(MfiTarget.LOCAL, MfiTarget.USB_CH341), options.map { it.tag })
        options.first().performClick()
        invoke("cancelSettingsEdits")
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(activity))
        assertEquals(MfiTarget.USB_CH341, field("mfiTarget"))
    }

    @Test fun selectingLocalWithoutIdentityKeepsTheMenuAndSavedUsbChoice() {
        attachController()
        invoke("openSettingsMenu")
        views(menu()).filterIsInstance<RadioButton>().first { it.tag == MfiTarget.LOCAL }.performClick()
        invoke("saveSettingsAndReconnect")
        assertTrue(field("menuOpen") as Boolean)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(activity))
        assertEquals(View.VISIBLE, (field("mfiErrorView") as View).visibility)
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun switchingToUsbPersistsAndPassesUsbToTheRuntime() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        attachController()
        invoke("openSettingsMenu")
        views(menu()).filterIsInstance<RadioButton>().first { it.tag == MfiTarget.USB_CH341 }.performClick()
        invoke("saveSettingsAndReconnect")
        assertFalse(field("menuOpen") as Boolean)
        assertEquals(MfiTarget.USB_CH341, AirPlayPersistence.loadMfiTarget(activity))
        val config = invoke("createRuntimeConfig") as CarPlayRuntimeConfig
        assertEquals(MfiTarget.USB_CH341, config.mfiTarget)
        assertEquals(listOf(com.shilapi.xcertplay.transport.UsbDeviceId(0x1a86, 0x5512)), config.ch341Devices)
        assertEquals(1, field("restartGeneration"))
    }

    @Test fun localRuntimeDoesNotRequestCh341Devices() {
        AirPlayPersistence.saveMfiTarget(activity, MfiTarget.LOCAL)
        invoke("loadPersistedSettings")
        val config = invoke("createRuntimeConfig") as CarPlayRuntimeConfig
        assertEquals(MfiTarget.LOCAL, config.mfiTarget)
        assertTrue(config.ch341Devices.isEmpty())
    }

    @Test fun ch341AttachmentKeepsTheWirelessSession() {
        val controller = attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x1a86)
        `when`(device.productId).thenReturn(0x5512)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
        assertTrue(AirPlayPersistence.loadWirelessEnabled(activity))
        assertTrue(field("wirelessEnabled") as Boolean)
        assertSame(controller, field("controller"))
        assertFalse((field("shuttingDown") as AtomicBoolean).get())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun iphoneAttachmentSwitchesToWiredTransportWithoutFinishing() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }.invoke(activity, intent)
        assertFalse(AirPlayPersistence.loadWirelessEnabled(activity))
        assertFalse(field("wirelessEnabled") as Boolean)
        assertTrue(field("vpnReady") as Boolean)
        assertFalse((field("shuttingDown") as AtomicBoolean).get())
        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun switchingToUsbRequestsVpnConsentAndDoesNotRepeatPendingConsent() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(Intent("test.VPN_CONSENT"))
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        setField("vpnReady", true) // Previously granted permission may have been revoked.
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        val method = activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
        method.invoke(activity, intent)
        assertTrue(field("awaitingVpnConsent") as Boolean)
        assertFalse(field("vpnReady") as Boolean)
        assertNull(field("controller"))
        assertEquals("test.VPN_CONSENT", shadowOf(activity).nextStartedActivityForResult.intent.action)
        method.invoke(activity, intent)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        assertFalse(activity.isFinishing)
    }

    @Test fun usbAttachmentClosesSettingsAndRestartsInPlace() {
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
        attachController()
        AirPlayPersistence.saveWirelessEnabled(activity, true)
        invoke("loadPersistedSettings")
        invoke("openSettingsMenu")
        assertTrue(field("menuOpen") as Boolean)
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val intent = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        activity.javaClass.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
            .invoke(activity, intent)
        assertFalse(field("menuOpen") as Boolean)
        assertFalse(field("wirelessEnabled") as Boolean)
        assertTrue(field("vpnReady") as Boolean)
        assertNull(field("controller"))
        assertEquals(1, field("restartGeneration"))
        assertFalse(activity.isFinishing)
    }

    @Test fun onlyIphoneAttachmentSelectsWiredTransport() {
        val method = activity.javaClass.getDeclaredMethod("isIphoneUsbAttachment", Intent::class.java)
            .apply { isAccessible = true }
        val device = mock(UsbDevice::class.java)
        `when`(device.vendorId).thenReturn(0x05ac)
        val attached = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, device)
        assertEquals(true, method.invoke(activity, attached))
        assertEquals(false, method.invoke(activity, Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)))
        assertEquals(false, method.invoke(activity, Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, device)))
    }

    @Test fun cancelRestoresSafeAreaResetAfterTheWindowChangesSize() {
        val original = SafeAreaRect(20, 20, 1800, 900)
        AirPlayPersistence.saveSafeAreaRect(activity, 1920, 942, original)
        invoke("openSettingsMenu")
        resizeWindow(1920, 942)
        invoke("resetSafeAreaForCurrentSize")
        assertNull(AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))

        invoke("cancelSettingsEdits")

        assertEquals(original, AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))
    }

    @Test fun cancelRemovesNewSafeAreaSavedAtAnotherWindowSize() {
        invoke("openSettingsMenu")
        resizeWindow(1920, 942)
        invoke("openSafeAreaEditor")
        val editor = field("safeAreaEditorView") as SafeAreaEditorView
        editor.setRect(SafeAreaRect(20, 20, 1800, 900), 1920, 942)
        invoke("saveSafeAreaEditor")
        assertNotNull(AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))

        invoke("cancelSettingsEdits")

        assertNull(AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))
    }

    @Test fun savingKeepsSafeAreaEditsMadeAfterAWindowResize() {
        invoke("openSettingsMenu")
        resizeWindow(1920, 942)
        invoke("openSafeAreaEditor")
        val edited = SafeAreaRect(20, 20, 1800, 900)
        (field("safeAreaEditorView") as SafeAreaEditorView).setRect(edited, 1920, 942)
        invoke("saveSafeAreaEditor")

        invoke("saveSettingsAndReconnect")

        assertFalse(field("menuOpen") as Boolean)
        assertEquals(edited, AirPlayPersistence.loadSafeAreaRect(activity, 1920, 942))
    }

    private fun resizeWindow(width: Int, height: Int) {
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)
        setField("activeDisplaySize", size)
    }

    private fun gesture(fingers: Int, x: Float = 400f, y: Float = 700f) {
        touch(MotionEvent.ACTION_DOWN, 1, 100f)
        for (count in 2..fingers) touch(MotionEvent.ACTION_POINTER_DOWN, count, 100f)
        touch(MotionEvent.ACTION_MOVE, fingers, y, x)
        touch(MotionEvent.ACTION_UP, 1, y, x)
    }

    private fun touch(action: Int, count: Int, y: Float, x: Float = 400f) {
        val pointers = Array(count) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val positions = Array(count) { i -> MotionEvent.PointerCoords().apply { this.x = x + i * 50f; this.y = y; pressure = 1f; size = 1f } }
        val indexedAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or ((count - 1) shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val event = MotionEvent.obtain(0, 10, indexedAction, count, pointers, positions, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try {
            activity.javaClass.getDeclaredMethod("onHostTouch", View::class.java, MotionEvent::class.java)
                .apply { isAccessible = true }.invoke(activity, field("gestureOverlay"), event)
        } finally { event.recycle() }
    }

    private fun attachController(): CarPlayController = CarPlayController(
        activity, CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3)),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(1920, 990)),
        AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
    ).also { setField("controller", it) }

    @Suppress("UNCHECKED_CAST")
    private fun report(status: CarPlayStatus, generation: Int = 0) {
        val reporter = activity.javaClass.getDeclaredMethod("createStatusReporter", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, generation) as (CarPlayStatus) -> Unit
        reporter(status)
    }

    private fun menu() = field("settingsMenu") as View
    private fun resolutionSlider() = views(menu()).filterIsInstance<SeekBar>()
        .first { it.max == CarPlayDisplayScale.MAX_PERCENT - CarPlayDisplayScale.MIN_PERCENT }
    private fun gestureButton() = views(menu()).filterIsInstance<Button>()
        .first { it.text == activity.getString(R.string.settings_gesture_fingers, field("gestureFingerCount")) }
    private fun fullSettingsButton() = views(menu()).filterIsInstance<Button>()
        .first { it.text == activity.getString(R.string.open_full_settings) }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
    private fun invoke(name: String): Any? = activity.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
