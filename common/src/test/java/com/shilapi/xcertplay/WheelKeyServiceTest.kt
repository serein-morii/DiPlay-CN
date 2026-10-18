package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.time.Duration
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelKeyServiceTest {
    private lateinit var service: WheelKeyService
    private var route: Any? = "visible-phone-one-map"
    private var learned = 0

    @Before fun setUp() {
        service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE).edit().clear().commit()
        service.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        service.mapRoute = { route }
        WheelZoomSettings.setEnabled(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.MODE, WheelKey(KeyEvent.KEYCODE_F1, 0, "?"))
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.ZOOM_IN, WheelKey(KeyEvent.KEYCODE_F2, 0, "?"))
        connect()
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
    }

    @After fun tearDown() {
        service.onDestroy()
        BydOutputSettings.setCarPlayCallControls(service, false)
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
    }

    private val knobs = mutableListOf<com.shilapi.xcertplay.airplay.AirPlayKnobState>()
    private var phone: Any? = "phone-one"
    private var routeStarted = false

    private fun joystickSetUp() {
        service.session = { phone }
        service.knob = { knobs.add(it) }
        service.routeActive = { routeStarted }
        WheelZoomSettings.setJoystick(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.JOYSTICK, WheelKey(KeyEvent.KEYCODE_F4, 0, "?"))
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.NEXT, WheelKey(KeyEvent.KEYCODE_F5, 0, "?"))
    }

    private fun press(code: Int): Pair<Boolean, Boolean> = key(code, true) to key(code, false)

    private fun connect() {
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
    }

    private fun key(code: Int, down: Boolean, repeat: Int = 0, time: Long = 0): Boolean = service.javaClass
        .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }.invoke(service,
            KeyEvent(time, time, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, repeat, 0, -1, 0),
        ) as Boolean

    private fun zoomOn() {
        assertTrue(key(KeyEvent.KEYCODE_F1, true))
        assertTrue(key(KeyEvent.KEYCODE_F1, false))
    }

    private fun learn(cancelled: () -> Unit = {}): Boolean =
        WheelKeyService.learn(WheelZoomSettings.Role.MODE, cancelled) { _, _ -> learned++ }

    @Test fun disabledSwitchCancelsLearningAndRestoresItsVisiblePrompt() {
        val assign = Button(service).apply { text = "Press a key" }
        assertTrue(learn { assign.text = "Assign" })
        WheelZoomSettings.setEnabled(service, false)
        assertEquals("Assign", assign.text.toString())
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(0, learned)
        assertEquals(KeyEvent.KEYCODE_F1, WheelZoomSettings.key(service, WheelZoomSettings.Role.MODE)?.code)
    }

    @Test fun cancelAndTimeoutDoNotLeaveTheNextKeyCaptured() {
        var cancelled = 0
        assertTrue(learn { cancelled++ })
        WheelKeyService.cancelLearning()
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertTrue(learn { cancelled++ })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WheelKeyService.LEARNING_TIMEOUT_MILLIS))
        assertEquals(2, cancelled)
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(0, learned)
    }

    @Test fun completedLearningOwnsRepeatsAndReleaseEvenAfterCancellationAndDisable() {
        assertTrue(learn())
        assertTrue(key(KeyEvent.KEYCODE_F3, true))
        WheelKeyService.cancelLearning()
        WheelZoomSettings.setEnabled(service, false)
        assertTrue(key(KeyEvent.KEYCODE_F3, true, repeat = 1))
        assertTrue(key(KeyEvent.KEYCODE_F3, false))
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(1, learned)
    }

    @Test fun unbindingCancelsLearningAndRebindingDoesNotReviveIt() {
        var cancelled = 0
        assertTrue(learn { cancelled++ })
        service.onUnbind(Intent())
        assertFalse(WheelKeyService.connected())
        connect()
        assertEquals(1, cancelled)
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(0, learned)
    }

    @Test fun pollingEndsZoomWithoutAKeyAndReconnectKeepsVolume() {
        zoomOn()
        route = null
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        route = "visible-phone-two-map"
        assertFalse(key(KeyEvent.KEYCODE_F2, true))
        assertFalse(key(KeyEvent.KEYCODE_F2, false))
    }

    @Test fun disablingAndReenablingEndsZoomButPreservesAHeldRelease() {
        zoomOn()
        assertTrue(key(KeyEvent.KEYCODE_F2, true))
        WheelZoomSettings.setEnabled(service, false)
        assertTrue(key(KeyEvent.KEYCODE_F2, false))
        WheelZoomSettings.setEnabled(service, true)
        assertFalse(key(KeyEvent.KEYCODE_F2, true))
        assertFalse(key(KeyEvent.KEYCODE_F2, false))
    }

    @Test fun callChangesPreserveCompletePressesAndCancelLearning() {
        zoomOn()
        val audio = service.getSystemService(AudioManager::class.java)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        assertFalse(key(KeyEvent.KEYCODE_F2, true))
        audio.mode = AudioManager.MODE_NORMAL
        assertFalse(key(KeyEvent.KEYCODE_F2, false))
        assertTrue(key(KeyEvent.KEYCODE_F2, true))
        audio.mode = AudioManager.MODE_RINGTONE
        assertTrue(key(KeyEvent.KEYCODE_F2, false))
        assertTrue(learn())
        assertFalse(key(KeyEvent.KEYCODE_F3, true))
        assertFalse(key(KeyEvent.KEYCODE_F3, false))
        assertEquals(0, learned)
    }

    @Test fun joystickKeysDriveTheKnobAndTheModeKeyGoesBackOnlyWhileItIsOn() {
        joystickSetUp()
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F5))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F2))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F1))
        assertEquals(listOf(1, -1), knobs.take(2).map { it.wheel })
        assertTrue(knobs[2].back)
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        // With the joystick off the mode key switches the zoom again.
        zoomOn()
        assertEquals(3, knobs.size)
    }

    @Test fun joystickEndsAfterIdleAndWhenARouteStarts() {
        joystickSetUp()
        press(KeyEvent.KEYCODE_F4)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WheelZoomSettings.JOYSTICK_IDLE_MILLIS))
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        press(KeyEvent.KEYCODE_F4)
        routeStarted = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_100))
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        assertTrue(knobs.isEmpty())
    }

    @Test fun joystickWithoutAutoOffStaysOnButEndsWithTheSettingOrANewPhone() {
        joystickSetUp()
        WheelZoomSettings.setJoystickAutoOff(service, false)
        press(KeyEvent.KEYCODE_F4)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WheelZoomSettings.JOYSTICK_IDLE_MILLIS * 2))
        assertEquals(true to true, press(KeyEvent.KEYCODE_F5))
        phone = "phone-two"
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        press(KeyEvent.KEYCODE_F4)
        WheelZoomSettings.setJoystick(service, false)
        assertEquals(false to false, press(KeyEvent.KEYCODE_F5))
        assertEquals(1, knobs.size)
    }

    @Test fun learningTakesTheNextKeyBeforeTheJoystick() {
        joystickSetUp()
        WheelZoomSettings.setEnabled(service, false)
        assertTrue(learn())
        assertEquals(true to true, press(KeyEvent.KEYCODE_F4))
        assertEquals(1, learned)
        assertEquals(KeyEvent.KEYCODE_F4, WheelZoomSettings.key(service, WheelZoomSettings.Role.MODE)?.code)
        assertTrue(knobs.isEmpty())
    }

    @Test fun experimentalVoiceKeysRemainWithTheCarUntilOptIn() {
        service.session = { "active-phone" }
        BydOutputSettings.setCarPlayCallControls(service, false)
        assertEquals(false to false, press(327))
        assertEquals(false to false, press(328))
        BydOutputSettings.setCarPlayCallControls(service, true)
        assertEquals(true to true, press(327))
        assertEquals(true to true, press(328))
        BydOutputSettings.setCarPlayCallControls(service, false)
        assertEquals(false to false, press(327))
    }

    @Test fun anAssignedBydVoiceKeyStillUsesVoiceKeyPairWhenDoublePressIsOff() {
        val code = CarPlayMediaButton.KEYCODE_BYD_CARPLAY_VOICE
        service.session = { "active-phone" }
        BydOutputSettings.setCarPlayCallControls(service, true)
        WheelZoomSettings.setSiriKey(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.SIRI, WheelKey(code, 0, "?"))
        WheelZoomSettings.setSiriDoublePress(service, false)
        val controller = mock(CarPlayController::class.java)
        `when`(controller.requestSiri()).thenReturn(true)
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1, 1,
            this, mock(CarPlaySessionDisplay::class.java)) { it() }

        assertTrue(key(code, true, time = 10_000))
        assertTrue(key(code, false, time = 10_000))

        verify(controller, times(1)).requestSiri()
        assertFalse(VoiceKeyPair.shared.allowGenericKey(10_460))
    }

    @Test
    fun theAllowedListKeepsOtherServicesAndRebindsAListedButStoppedService() {
        val ours = "com.shihab.diplay/com.shilapi.xcertplay.WheelKeyService"
        val car = "com.byd.airconditioning/.gesture.AcGestureService:com.android.systemui/.custom.StatusBarAccessibilityService"
        assertEquals(null to "$car:$ours", WheelKeyService.allowedServices("$car\n", ours))
        assertEquals(car to "$car:$ours", WheelKeyService.allowedServices("$ours:$car", ours))
        assertEquals("" to ours, WheelKeyService.allowedServices(ours, ours))
        for (empty in listOf("", "null", " \n")) assertEquals(null to ours, WheelKeyService.allowedServices(empty, ours))
        assertNull(WheelKeyService.allowedServices(null, ours))
    }

    private var siriRequests = 0

    private fun siriSetUp() {
        WheelZoomSettings.setEnabled(service, false)
        WheelZoomSettings.setSiriKey(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.SIRI, WheelKey(KeyEvent.KEYCODE_F6, 0, "?"))
        service.session = { phone }
        service.requestSiri = { siriRequests++; true }
    }

    @Test fun aHeldSiriKeySentAsRepeatedPressesOpensSiriOnce() {
        siriSetUp()
        for (time in 0L..500L step 100) {
            assertTrue(key(KeyEvent.KEYCODE_F6, true, time = time))
            assertTrue(key(KeyEvent.KEYCODE_F6, false, time = time))
        }
        assertEquals(1, siriRequests)
        assertTrue(key(KeyEvent.KEYCODE_F6, true, time = 2_000))
        assertTrue(key(KeyEvent.KEYCODE_F6, false, time = 2_000))
        assertEquals(2, siriRequests)
    }

    @Test fun doublePressModeLeavesBothPressesToTheVehicleAndDelaysSiri() {
        siriSetUp()
        WheelZoomSettings.setSiriDoublePress(service, true)
        WheelZoomSettings.setSiriDoublePressIntervalMillis(service, 600)
        WheelZoomSettings.setSiriTriggerDelayMillis(service, 300)

        assertFalse(key(KeyEvent.KEYCODE_F6, true, time = 1_000))
        assertFalse(key(KeyEvent.KEYCODE_F6, false, time = 1_120))
        assertEquals(0, siriRequests)
        assertFalse(key(KeyEvent.KEYCODE_F6, true, time = 1_450))
        // The delay starts only after the second release, so a held second press cannot open Siri.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(0, siriRequests)
        assertFalse(key(KeyEvent.KEYCODE_F6, false, time = 1_560))
        assertEquals(0, siriRequests)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(299))
        assertEquals(0, siriRequests)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, siriRequests)
    }

    @Test fun doublePressParametersHaveSafeDefaultsAndPersistAllowedValues() {
        assertFalse(WheelZoomSettings.siriDoublePress(service))
        assertEquals(600, WheelZoomSettings.siriDoublePressIntervalMillis(service))
        assertEquals(300, WheelZoomSettings.siriTriggerDelayMillis(service))

        WheelZoomSettings.setSiriDoublePress(service, true)
        WheelZoomSettings.setSiriDoublePressIntervalMillis(service, 800)
        WheelZoomSettings.setSiriTriggerDelayMillis(service, 500)

        assertTrue(WheelZoomSettings.siriDoublePress(service))
        assertEquals(800, WheelZoomSettings.siriDoublePressIntervalMillis(service))
        assertEquals(500, WheelZoomSettings.siriTriggerDelayMillis(service))
        val report = WheelKeyService.report(service)
        assertTrue(report, report.contains("siriDoublePress=true"))
        assertTrue(report, report.contains("siriDoubleIntervalMs=800"))
        assertTrue(report, report.contains("siriTriggerDelayMs=500"))
    }

    @Test fun delayedDoublePressNeverCarriesIntoAnotherCarPlaySession() {
        siriSetUp()
        WheelZoomSettings.setSiriDoublePress(service, true)
        WheelZoomSettings.setSiriTriggerDelayMillis(service, 300)
        key(KeyEvent.KEYCODE_F6, true, time = 1_000)
        key(KeyEvent.KEYCODE_F6, false, time = 1_120)
        key(KeyEvent.KEYCODE_F6, true, time = 1_400)
        key(KeyEvent.KEYCODE_F6, false, time = 1_520)
        phone = "phone-two"

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertEquals(0, siriRequests)
    }

    @Test fun aLostOrVeryLateSecondReleaseCannotTriggerAFutureSinglePress() {
        siriSetUp()
        WheelZoomSettings.setSiriDoublePress(service, true)
        WheelZoomSettings.setSiriTriggerDelayMillis(service, 0)
        key(KeyEvent.KEYCODE_F6, true, time = 1_000)
        key(KeyEvent.KEYCODE_F6, false, time = 1_100)
        key(KeyEvent.KEYCODE_F6, true, time = 1_400) // confirmed second press; its release is lost

        key(KeyEvent.KEYCODE_F6, true, time = 3_000) // replaces the stale pending press
        key(KeyEvent.KEYCODE_F6, false, time = 3_100)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, siriRequests)

        key(KeyEvent.KEYCODE_F6, true, time = 4_000)
        key(KeyEvent.KEYCODE_F6, false, time = 4_100)
        key(KeyEvent.KEYCODE_F6, true, time = 4_400)
        key(KeyEvent.KEYCODE_F6, false, time = 6_001) // longer than the second-press release guard
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, siriRequests)
    }

    @Test fun carAssistantCommunicationModeAfterTheFirstPressDoesNotBreakThePair() {
        siriSetUp()
        WheelZoomSettings.setSiriDoublePress(service, true)
        WheelZoomSettings.setSiriTriggerDelayMillis(service, 300)
        key(KeyEvent.KEYCODE_F6, true, time = 1_000)
        key(KeyEvent.KEYCODE_F6, false, time = 1_100)
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        key(KeyEvent.KEYCODE_F6, true, time = 1_400)
        key(KeyEvent.KEYCODE_F6, false, time = 1_500)
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertEquals(1, siriRequests)
    }

    @Test fun aPairStartedDuringACallCannotOpenSiriAfterTheCallEnds() {
        siriSetUp()
        WheelZoomSettings.setSiriDoublePress(service, true)
        WheelZoomSettings.setSiriTriggerDelayMillis(service, 300)
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        key(KeyEvent.KEYCODE_F6, true, time = 1_000)
        key(KeyEvent.KEYCODE_F6, false, time = 1_100)
        key(KeyEvent.KEYCODE_F6, true, time = 1_400)
        key(KeyEvent.KEYCODE_F6, false, time = 1_500)
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertEquals(0, siriRequests)
    }

    @Test fun siriKeyKeepsTheCarsActionWithoutCarPlayInACallOrWhenOff() {
        siriSetUp()
        phone = null
        assertFalse(key(KeyEvent.KEYCODE_F6, true))
        assertFalse(key(KeyEvent.KEYCODE_F6, false))
        phone = "phone-one"
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        assertFalse(key(KeyEvent.KEYCODE_F6, true))
        assertFalse(key(KeyEvent.KEYCODE_F6, false))
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        WheelZoomSettings.setSiriKey(service, false)
        assertFalse(key(KeyEvent.KEYCODE_F6, true))
        assertFalse(key(KeyEvent.KEYCODE_F6, false))
        WheelZoomSettings.setEnabled(service, true) // the stored Siri key must not come back through the zoom roles
        assertFalse(key(KeyEvent.KEYCODE_F6, true))
        assertFalse(key(KeyEvent.KEYCODE_F6, false))
        assertEquals(0, siriRequests)
    }

    @Test fun theSiriKeyCanBeLearnedWithOnlyTheSiriSettingOn() {
        siriSetUp()
        assertTrue(WheelKeyService.learn(WheelZoomSettings.Role.SIRI) { _, _ -> learned++ })
        assertTrue(key(KeyEvent.KEYCODE_F7, true))
        assertTrue(key(KeyEvent.KEYCODE_F7, false))
        assertEquals(1, learned)
        assertEquals(KeyEvent.KEYCODE_F7, WheelZoomSettings.key(service, WheelZoomSettings.Role.SIRI)?.code)
    }

    @Test fun theSiriKeyMatchesOnlyWhileTheSettingIsOn() {
        siriSetUp()
        val assigned = WheelKey(KeyEvent.KEYCODE_F6, 0, "?")
        assertTrue(WheelZoomSettings.isSiriKey(service, assigned))
        assertFalse(WheelZoomSettings.isSiriKey(service, WheelKey(KeyEvent.KEYCODE_F6, 1, "?")))
        WheelZoomSettings.setSiriKey(service, false)
        assertFalse(WheelZoomSettings.isSiriKey(service, assigned))
    }

    @Test fun theSiriKeyRefusesAKeyThatAnActiveRoleUses() {
        WheelZoomSettings.setSiriKey(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.SIRI, WheelKey(KeyEvent.KEYCODE_F6, 0, "?"))
        var taken: WheelZoomSettings.Role? = null
        assertTrue(WheelKeyService.learn(WheelZoomSettings.Role.SIRI, refused = { taken = it }) { _, _ -> learned++ })
        assertTrue(key(KeyEvent.KEYCODE_F1, true))
        assertTrue(key(KeyEvent.KEYCODE_F1, false))
        assertEquals(WheelZoomSettings.Role.MODE, taken)
        assertEquals(0, learned)
        assertEquals(KeyEvent.KEYCODE_F6, WheelZoomSettings.key(service, WheelZoomSettings.Role.SIRI)?.code)
    }

    @Test fun aZoomKeyRefusesTheSiriKey() {
        WheelZoomSettings.setSiriKey(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.SIRI, WheelKey(KeyEvent.KEYCODE_F6, 0, "?"))
        var taken: WheelZoomSettings.Role? = null
        assertTrue(WheelKeyService.learn(WheelZoomSettings.Role.MODE, refused = { taken = it }) { _, _ -> learned++ })
        assertTrue(key(KeyEvent.KEYCODE_F6, true))
        assertTrue(key(KeyEvent.KEYCODE_F6, false))
        assertEquals(WheelZoomSettings.Role.SIRI, taken)
        assertEquals(KeyEvent.KEYCODE_F1, WheelZoomSettings.key(service, WheelZoomSettings.Role.MODE)?.code)
    }

    @Test fun onlyConflictsWithAnActiveSiriKeyAreRefused() {
        val mode = WheelKey(KeyEvent.KEYCODE_F1, 0, "?")
        // Zoom and joystick keep their own rules; the Siri setting is off.
        assertNull(WheelZoomSettings.conflict(service, WheelZoomSettings.Role.ZOOM_IN, mode))
        WheelZoomSettings.setSiriKey(service, true)
        assertEquals(WheelZoomSettings.Role.MODE, WheelZoomSettings.conflict(service, WheelZoomSettings.Role.SIRI, mode))
        WheelZoomSettings.setEnabled(service, false)
        assertNull(WheelZoomSettings.conflict(service, WheelZoomSettings.Role.SIRI, mode))
    }

    @Test fun mediaPlayPauseKeyIsOffByDefaultAndWorksWithOnlyItsOwnSettingEnabled() {
        assertFalse(WheelZoomSettings.mediaPlayPause(service))
        WheelZoomSettings.setEnabled(service, false)
        WheelZoomSettings.setSiriKey(service, false)
        WheelZoomSettings.setMediaPlayPause(service, true)
        assertTrue(WheelZoomSettings.anyEnabled(service))
        val selected = WheelKey(KeyEvent.KEYCODE_F9, 0, "?")
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE, selected)
        service.session = { "live-iphone" }
        val actions = mutableListOf<Int>()
        service.sendMedia = { index -> actions += index; true }

        assertTrue(key(KeyEvent.KEYCODE_F9, true))
        assertTrue(key(KeyEvent.KEYCODE_F9, true, repeat = 1))
        assertTrue(key(KeyEvent.KEYCODE_F9, false))
        assertEquals(listOf(com.shilapi.xcertplay.airplay.CarPlayMediaButton.PLAY_PAUSE), actions)
        assertEquals(1, actions.size)
        assertEquals(false to false, press(KeyEvent.KEYCODE_F8))
        assertEquals(1, actions.size)
    }

    @Test fun remappedMediaKeyPreservesVehicleBehaviorDuringCallsWithoutPhoneOrWhenDisabled() {
        WheelZoomSettings.setEnabled(service, false)
        WheelZoomSettings.setMediaPlayPause(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
            WheelKey(KeyEvent.KEYCODE_F9, 0, "?"))
        var count = 0
        service.sendMedia = { count++; true }
        service.session = { null }
        assertEquals(false to false, press(KeyEvent.KEYCODE_F9))
        service.session = { "iphone" }
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        assertEquals(false to false, press(KeyEvent.KEYCODE_F9))
        service.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        assertTrue(key(KeyEvent.KEYCODE_F9, true))
        WheelZoomSettings.setMediaPlayPause(service, false)
        assertTrue(key(KeyEvent.KEYCODE_F9, false))
        assertEquals(false to false, press(KeyEvent.KEYCODE_F9))
        assertEquals(1, count)
    }

    @Test fun learningPlayPauseRejectsExistingSiriAndZoomKeys() {
        WheelZoomSettings.setMediaPlayPause(service, true)
        WheelZoomSettings.setSiriKey(service, true)
        val siri = WheelKey(KeyEvent.KEYCODE_F6, 0, "?")
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.SIRI, siri)
        var refused: WheelZoomSettings.Role? = null
        assertTrue(WheelKeyService.learn(WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
            refused = { refused = it }) { _, _ -> learned++ })
        assertEquals(true to true, press(KeyEvent.KEYCODE_F6))
        assertEquals(WheelZoomSettings.Role.SIRI, refused)
        assertEquals(0, learned)
        assertNull(WheelZoomSettings.key(service, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE))
        assertTrue(WheelKeyService.learn(WheelZoomSettings.Role.MEDIA_PLAY_PAUSE,
            refused = { refused = it }) { _, _ -> learned++ })
        assertEquals(true to true, press(KeyEvent.KEYCODE_F1))
        assertEquals(WheelZoomSettings.Role.MODE, refused)
        assertEquals(0, learned)
        // An unused key can be learnt with no other wheel features configured.
        WheelZoomSettings.setSiriKey(service, false)
        WheelZoomSettings.setEnabled(service, false)
        assertTrue(WheelKeyService.learn(WheelZoomSettings.Role.MEDIA_PLAY_PAUSE) { _, _ -> learned++ })
        assertEquals(true to true, press(KeyEvent.KEYCODE_F9))
        assertEquals(1, learned)
        assertEquals(KeyEvent.KEYCODE_F9, WheelZoomSettings.key(service,
            WheelZoomSettings.Role.MEDIA_PLAY_PAUSE)?.code)
    }

    @Test fun remoteNavigationKeyCannotBeLearnedForMediaAndRemainsAvailableToTv() {
        WheelZoomSettings.setEnabled(service, false)
        WheelZoomSettings.setMediaPlayPause(service, true)
        var learned = 0
        assertTrue(WheelKeyService.learn(WheelZoomSettings.Role.MEDIA_PLAY_PAUSE) { _, _ -> learned++ })
        assertEquals(true to true, press(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(0, learned)
        assertNull(WheelZoomSettings.key(service, WheelZoomSettings.Role.MEDIA_PLAY_PAUSE))
        assertTrue(WheelKeyService.isLearning())
        assertEquals(true to true, press(KeyEvent.KEYCODE_F9))
        assertEquals(1, learned)
    }

    @Test fun theSiriKeyWinsOverAnotherRoleWithTheSameKey() {
        siriSetUp()
        // MODE keeps F1 while zoom is off, so learning allowed the same key for Siri.
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.SIRI, WheelKey(KeyEvent.KEYCODE_F1, 0, "?"))
        assertTrue(key(KeyEvent.KEYCODE_F1, true, time = 0))
        assertTrue(key(KeyEvent.KEYCODE_F1, false, time = 0))
        assertEquals(1, siriRequests)
        // Zoom turned on later still leaves the key to Siri.
        WheelZoomSettings.setEnabled(service, true)
        assertTrue(key(KeyEvent.KEYCODE_F1, true, time = 1_000))
        assertTrue(key(KeyEvent.KEYCODE_F1, false, time = 1_000))
        assertEquals(2, siriRequests)
    }

    @Test fun aHeldSiriCallKeyCannotEndTheCallThatArrivesBeforeItsRelease() {
        siriSetUp()
        val controller = mock(CarPlayController::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 1, 1,
            this, mock(CarPlaySessionDisplay::class.java)) { it() }
        BydOutputSettings.setCarPlayCallControls(service, true)
        WheelZoomSettings.assign(service, WheelZoomSettings.Role.SIRI, WheelKey(KeyEvent.KEYCODE_ENDCALL, 0, "?"))
        assertTrue(key(KeyEvent.KEYCODE_ENDCALL, true))
        assertEquals(1, siriRequests)
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "new-call")
        })
        assertTrue(key(KeyEvent.KEYCODE_ENDCALL, false))
        verify(controller, never()).endCall()
        assertTrue(key(KeyEvent.KEYCODE_ENDCALL, true))
        assertTrue(key(KeyEvent.KEYCODE_ENDCALL, false))
        verify(controller, times(1)).endCall()
        assertEquals(1, siriRequests)
    }
    @Test
    @Config(sdk = [25, 29])
    fun guidanceChangesOnlyNavigationAndStopsNewCapturesAfterGuidance() {
        WheelZoomSettings.setEnabled(service, false)
        service.session = { "phone" }
        var playback = com.shilapi.xcertplay.media.NavigationPlaybackSnapshot(true, AudioManager.STREAM_NOTIFICATION)
        service.navigationPlayback = { playback }
        val audio = service.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 2, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val prefs = service.getSharedPreferences("diplay_navigation_wheel", Context.MODE_PRIVATE)
        // The disabled default must pass the initial wheel press through.
        fun event(action: Int, time: Long, repeat: Int = 0) = KeyEvent(time, time, action,
            KeyEvent.KEYCODE_VOLUME_UP, repeat)
        fun dispatch(event: KeyEvent): Boolean = service.javaClass
            .getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
            .invoke(service, event) as Boolean
        assertFalse(dispatch(event(KeyEvent.ACTION_DOWN, 1)))
        prefs.edit().putBoolean("enabled", true).commit()
        assertTrue(dispatch(event(KeyEvent.ACTION_DOWN, 2)))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        playback = com.shilapi.xcertplay.media.NavigationPlaybackSnapshot(false, null)
        assertTrue(dispatch(event(KeyEvent.ACTION_DOWN, 2, 1)))
        assertTrue(dispatch(event(KeyEvent.ACTION_UP, 2)))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        assertFalse(dispatch(event(KeyEvent.ACTION_DOWN, 3)))
        playback = com.shilapi.xcertplay.media.NavigationPlaybackSnapshot(true, AudioManager.STREAM_MUSIC)
        assertFalse(dispatch(event(KeyEvent.ACTION_DOWN, 4)))
        playback = com.shilapi.xcertplay.media.NavigationPlaybackSnapshot(true, null)
        assertFalse(dispatch(event(KeyEvent.ACTION_DOWN, 5)))
        playback = com.shilapi.xcertplay.media.NavigationPlaybackSnapshot(true, AudioManager.STREAM_NOTIFICATION, true)
        assertFalse(dispatch(event(KeyEvent.ACTION_DOWN, 6)))
        assertEquals(3, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

}
