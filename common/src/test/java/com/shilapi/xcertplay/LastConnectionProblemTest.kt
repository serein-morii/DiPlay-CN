package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class LastConnectionProblemTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("diplay_last_problem", Context.MODE_PRIVATE)
    private val hosts = mutableListOf<CarPlayHostActivity>()

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        prefs.edit().clear().commit()
    }

    @After fun tearDown() {
        for (host in hosts) {
            ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor"))
                ReflectionHelpers.getField<ExecutorService>(host, name).shutdownNow()
        }
        CarPlayBackgroundSession.clear()
        prefs.edit().clear().commit()
    }

    private fun host(): CarPlayHostActivity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().also {
        it.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", it)
        ReflectionHelpers.setField(it, "stageStatusView", TextView(it))
        ReflectionHelpers.setField(it, "connectionHelpView", TextView(it))
        hosts += it
    }

    private fun CarPlayHostActivity.report(status: CarPlayStatus) =
        ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(this, "createStatusReporter",
            from(Int::class.javaPrimitiveType, 0))(status)

    private fun CarPlayHostActivity.shown(view: String) = ReflectionHelpers.getField<TextView>(this, view).text.toString()

    private fun remembered(): String? = LastConnectionProblem.load(context)?.problem?.line()?.let(context::getString)

    private val hotspotOff = CarPlayStatus.Failed("The car hotspot is off. Turn it on in the car settings and connect again.",
        startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION, knownCause = ConnectionFailureCause.CAR_HOTSPOT_OFF)
    private val notPaired = CarPlayStatus.Failed("The selected iPhone is no longer paired. Choose it again in DiPlay.",
        knownCause = ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED)

    @Test fun eachProblemTheScreenNamesIsRememberedInItsWords() {
        val stageLines = listOf(
            hotspotOff,
            notPaired,
            CarPlayStatus.Failed("timeout", startupFailure = WirelessStartupFailure.FIRST_TCP_TIMEOUT),
            CarPlayStatus.Failed("Hotspot network is not ready", startupFailure = WirelessStartupFailure.HOTSPOT_NOT_READY),
            CarPlayStatus.Failed("Manual hotspot SSID does not match the active local AP configuration: 'Other'",
                startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION),
            CarPlayStatus.Failed("Android was not allowed to turn the car hotspot off for Wi-Fi Direct",
                startupFailure = WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT),
            CarPlayStatus.IphoneNotListed(nonHubDeviceListed = false),
            CarPlayStatus.IphoneNotListed(nonHubDeviceListed = true),
        )
        for (status in stageLines) {
            val host = host()
            host.report(status)
            assertEquals(status.toString(), host.shown("stageStatusView").lines().first(), remembered())
        }
    }

    @Test fun bluetoothThatReachesNoPhoneIsRememberedByItsHelpLine() {
        val host = host()
        host.report(CarPlayStatus.Failed("Android Bluetooth on this head unit reports connections that are not real",
            startupFailure = WirelessStartupFailure.BLUETOOTH_UNUSABLE))
        assertEquals(context.getString(R.string.connection_help_bluetooth_unusable), host.shown("connectionHelpView"))
        assertEquals(host.shown("connectionHelpView"), remembered())
    }

    @Test fun theRecordCarriesTheTimeOfTheFailure() {
        val before = System.currentTimeMillis()
        host().report(notPaired)
        val at = LastConnectionProblem.load(context)!!.atMillis
        assertTrue("$before <= $at", at in before..System.currentTimeMillis())
    }

    @Test fun progressKeepsTheProblemAndAFailureThatNamesNoneForgetsIt() {
        val host = host()
        host.report(notPaired)
        host.report(CarPlayStatus.ConnectingBluetooth)
        assertEquals(context.getString(R.string.chosen_iphone_not_paired), remembered())
        host.report(CarPlayStatus.Failed("Invalid argument"))
        assertEquals(context.getString(R.string.connection_interrupted_retrying), host.shown("stageStatusView"))
        assertNull(remembered())
    }

    @Test fun aLineGuessedFromTheFailureTextIsNotRemembered() {
        val host = host()
        host.report(notPaired)
        host.report(CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(context.getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth),
            host.shown("stageStatusView"))
        assertNull(remembered())
    }

    @Test fun aCleanupFailureAfterTheRetriesStoppedKeepsTheProblem() {
        val host = host()
        host.report(hotspotOff)
        assertTrue(ReflectionHelpers.getField(host, "startupRetryStopped"))
        host.report(CarPlayStatus.Failed("Bluetooth socket closed"))
        assertEquals(context.getString(R.string.car_hotspot_is_off), host.shown("stageStatusView").lines().first())
        assertEquals(context.getString(R.string.car_hotspot_is_off), remembered())
    }

    @Test fun aSessionThatStartsForgetsTheProblem() {
        val host = host()
        host.report(notPaired)
        ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(host, "createSessionListener",
            from(Int::class.javaPrimitiveType, 0)).onSessionActive(mock(AirPlaySession::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(CarPlayBackgroundSession.active)
        assertNull(remembered())
    }

    @Test fun settingsBackupsLeaveItOut() {
        LastConnectionProblem.follow(context, notPaired)
        assertEquals(context.getString(R.string.chosen_iphone_not_paired), remembered())
        assertFalse(String(SettingsBackupCodec.export(context, "test")).contains("diplay_last_problem"))
    }

    @Test fun aValueThisVersionCannotReadReadsAsNoneAndIsRemoved() {
        for (token in listOf("cause:A_CAUSE_FROM_A_LATER_VERSION", "startup:", "usb:maybe", "wifi:off", "")) {
            prefs.edit().putString("problem", token).putLong("at", 1_000L).commit()
            assertNull(token, LastConnectionProblem.load(context))
            assertFalse(token, prefs.contains("problem"))
        }
    }
}
