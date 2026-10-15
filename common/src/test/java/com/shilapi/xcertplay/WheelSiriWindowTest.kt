package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
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
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WheelSiriWindowTest {
    private lateinit var host: CarPlayHostActivity
    private lateinit var settings: DiPlayActivity
    private lateinit var controller: CarPlayController

    @Before fun setUp() {
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        settings = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.getSharedPreferences("diplay_wheel_map_zoom", Context.MODE_PRIVATE).edit().clear().commit()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        WheelZoomSettings.setSiriKey(host, true)
        WheelZoomSettings.assign(host, WheelZoomSettings.Role.SIRI, WheelKey(KeyEvent.KEYCODE_F6, 0, "?"))
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        controller = mock(CarPlayController::class.java)
        `when`(controller.requestSiri()).thenReturn(true)
        field("controller", controller)
        field("activeAirPlaySession", mock(AirPlaySession::class.java))
    }

    @After fun tearDown() {
        for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (host.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(host) as ExecutorService).shutdownNow()
        }
        for ((activity, name) in listOf(host to "mainHandler", settings to "handler")) {
            (activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity) as Handler).removeCallbacksAndMessages(null)
        }
        BydCarPlayCall.end()
        CarPlayBackgroundSession.clear()
        host.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun heldAssignedKeyKeepsItsReleaseAfterCallSessionAndSettingChanges() {
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true)))
        WheelZoomSettings.setSiriKey(host, false)
        field("activeAirPlaySession", null)
        host.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true, repeat = 1)))
        assertTrue(host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun anAssignedStandardVoiceKeyDoesNotAlsoInvokeTheOldReleasePath() {
        val code = KeyEvent.KEYCODE_VOICE_ASSIST
        WheelZoomSettings.assign(host, WheelZoomSettings.Role.SIRI, WheelKey(code, 0, "?"))
        assertTrue(host.dispatchKeyEvent(key(code, true)))
        assertTrue(host.dispatchKeyEvent(key(code, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun unassignedExistingVoiceKeysKeepTheirReleaseBehavior() {
        WheelZoomSettings.setSiriKey(host, false)
        for (code in listOf(KeyEvent.KEYCODE_VOICE_ASSIST,
            CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE,
            CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG)) {
            assertTrue(host.dispatchKeyEvent(key(code, true)))
            assertTrue(host.dispatchKeyEvent(key(code, false)))
        }
        verify(controller, times(3)).requestSiri()
    }

    @Test @org.junit.Ignore("CN behavior differs") fun longPressOnlyMakesTheShortVoiceKeyDoNothingAndKeepsTheLongOne() {
        WheelZoomSettings.setSiriKey(host, false)
        WheelZoomSettings.setSiriLongPressOnly(host, true)
        val short = CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE
        assertTrue(host.dispatchKeyEvent(key(short, true)))
        assertTrue(host.dispatchKeyEvent(key(short, false)))
        verify(controller, never()).requestSiri()
        val long = CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG
        assertTrue(host.dispatchKeyEvent(key(long, true)))
        assertTrue(host.dispatchKeyEvent(key(long, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun assigningALegacyVoiceKeyBetweenDownAndUpKeepsTheOriginalAction() {
        val code = KeyEvent.KEYCODE_VOICE_ASSIST
        WheelZoomSettings.setSiriKey(host, false)
        assertTrue(host.dispatchKeyEvent(key(code, true)))
        WheelZoomSettings.assign(host, WheelZoomSettings.Role.SIRI, WheelKey(code, 0, "?"))
        WheelZoomSettings.setSiriKey(host, true)
        assertTrue(host.dispatchKeyEvent(key(code, false)))
        verify(controller, times(1)).requestSiri()
    }

    @Test fun carPlayCallStateBlocksAssignedSiriWhileAndroidModeIsNormal() {
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 4); string(4, "active-call")
        })
        assertEquals(AudioManager.MODE_NORMAL, host.getSystemService(AudioManager::class.java).mode)
        assertTrue(inCall(host))
        host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, true))
        BydCarPlayCall.end()
        host.dispatchKeyEvent(key(KeyEvent.KEYCODE_F6, false))
        verify(controller, never()).requestSiri()
    }

    @Test fun windowLearningConsumesRepeatsAndReleaseAfterAssignmentFinishes() {
        var learned = 0
        learn { learned++ }
        assertTrue(settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, true)))
        assertTrue(settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, true, repeat = 1)))
        assertTrue(settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, false)))
        assertEquals(1, learned)
        assertEquals(KeyEvent.KEYCODE_F7, WheelZoomSettings.key(settings, WheelZoomSettings.Role.SIRI)?.code)
    }

    @Test fun callCancelsWindowLearningWithoutSavingTheCallKey() {
        var learned = 0
        learn { learned++ }
        settings.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, true))
        settings.dispatchKeyEvent(key(KeyEvent.KEYCODE_F7, false))
        assertEquals(0, learned)
        assertEquals(KeyEvent.KEYCODE_F6, WheelZoomSettings.key(settings, WheelZoomSettings.Role.SIRI)?.code)
    }

    @Test fun siriZoomAndJoystickShowTheirSharedServiceSetupOnlyOnce() {
        settings.setTheme(android.R.style.Theme_Material_NoActionBar)
        WheelZoomSettings.setEnabled(settings, true)
        WheelZoomSettings.setJoystick(settings, true)
        WheelZoomSettings.setSiriKey(settings, true)
        // A recognized BYD unit offers its wheel controls even without an available map.
        org.robolectric.Shadows.shadowOf(settings.packageManager)
            .installPackage(android.content.pm.PackageInfo().apply { packageName = "com.byd.amapservice" })
        val controls = LinearLayout(settings)
        settings.javaClass.getDeclaredMethod("wheelKeysSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(settings, controls)
        fun labels(view: View): List<String> = when (view) {
            is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
            is TextView -> listOf(view.text.toString())
            else -> emptyList()
        }
        val texts = labels(controls)
        for (label in listOf(R.string.wheel_keys_enable_adb, R.string.wheel_keys_open_settings,
            R.string.wheel_keys_service_off)) {
            assertEquals(1, texts.count { it == settings.getString(label) })
        }
        // The setup sits above the features it serves.
        assertTrue(texts.indexOf(settings.getString(R.string.wheel_keys_service_off)) <
            texts.indexOf(settings.getString(R.string.wheel_siri_key)))
        assertTrue(texts.any { it == settings.getString(R.string.wheel_joystick) })
    }

    @Test fun nonBydUnitKeepsItsSavedJoystickControlsWithoutOfferingUnavailableMapZoom() {
        settings.setTheme(android.R.style.Theme_Material_NoActionBar)
        WheelZoomSettings.setSiriKey(settings, false)
        WheelZoomSettings.setJoystick(settings, true)
        val joystickKey = WheelKey(KeyEvent.KEYCODE_F8, 0, "external-wheel")
        WheelZoomSettings.assign(settings, WheelZoomSettings.Role.JOYSTICK, joystickKey)
        AirPlayPersistence.saveClusterMapEnabled(settings, false)
        assertFalse(CarHotspotSetup.isBydHeadUnit(settings))

        val controls = wheelControls()
        val joystick = views(controls).filterIsInstance<Switch>().single {
            it.contentDescription == settings.getString(R.string.wheel_joystick)
        }
        assertTrue(joystick.isChecked)
        assertSharedServiceSetupOnce(controls)
        assertFalse(views(controls).filterIsInstance<Switch>().any {
            it.contentDescription == settings.getString(R.string.wheel_map_zoom)
        })
        assertTrue(views(controls).filterIsInstance<TextView>().any {
            it.text.toString() == settings.getString(R.string.wheel_key_assign,
                settings.getString(R.string.wheel_key_role_joystick), joystickKey.toString())
        })

        joystick.performClick()

        assertFalse(WheelZoomSettings.joystick(settings))
        assertEquals(joystickKey, WheelZoomSettings.key(settings, WheelZoomSettings.Role.JOYSTICK))
    }

    @Test fun nonBydUnitWithThePreviouslySupportedClusterDisplayKeepsWheelControls() {
        settings.setTheme(android.R.style.Theme_Material_NoActionBar)
        WheelZoomSettings.setSiriKey(settings, false)
        AirPlayPersistence.saveClusterMapEnabled(settings, true)
        val displayId = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        shadowOf(settings.getSystemService(DisplayManager::class.java).getDisplay(displayId)).apply {
            setName(DiLink4ClusterDisplay.NAME)
            setFlags(Display.FLAG_PRESENTATION)
        }
        try {
            assertFalse(CarHotspotSetup.isBydHeadUnit(settings))
            assertEquals(displayId, ClusterMapPresentation.findDisplay(settings)?.displayId)
            val controls = wheelControls()
            val zoom = views(controls).filterIsInstance<Switch>().single {
                it.contentDescription == settings.getString(R.string.wheel_map_zoom)
            }
            assertFalse(zoom.isChecked)
            assertTrue(views(controls).filterIsInstance<Switch>().any {
                it.contentDescription == settings.getString(R.string.wheel_joystick)
            })

            zoom.performClick()

            assertTrue(WheelZoomSettings.enabled(settings))
            assertSharedServiceSetupOnce(wheelControls())
        } finally {
            ShadowDisplayManager.removeDisplay(displayId)
        }
    }

    private fun wheelControls() = LinearLayout(settings).also { controls ->
        settings.javaClass.getDeclaredMethod("wheelKeysSettings", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(settings, controls)
    }

    private fun assertSharedServiceSetupOnce(controls: View) {
        val labels = views(controls).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        for (id in listOf(R.string.wheel_keys_service_off, R.string.wheel_keys_enable_adb,
            R.string.wheel_keys_open_settings)) {
            assertEquals(settings.getString(id), 1, labels.count { it == settings.getString(id) })
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun learn(done: (WheelKey) -> Unit) {
        settings.javaClass.getDeclaredMethod("learnInWindow", WheelZoomSettings.Role::class.java,
            kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function1::class.java,
            kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
            .invoke(settings, WheelZoomSettings.Role.SIRI, {}, { _: WheelZoomSettings.Role -> }, done)
    }

    private fun field(name: String, value: Any?) = host.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(host, value)

    private fun key(code: Int, down: Boolean, repeat: Int = 0) =
        KeyEvent(1_000, 1_000, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
            code, repeat, 0, -1, 0)
}
