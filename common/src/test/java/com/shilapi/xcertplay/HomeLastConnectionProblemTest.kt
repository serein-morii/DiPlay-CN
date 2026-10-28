package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp-land", shadows = [CarHotspotOffConnectTest.HotspotState::class])
class HomeLastConnectionProblemTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val opened = mutableListOf<DiPlayActivity>()

    @Before fun setUp() {
        clearSettings()
        CarPlayBackgroundSession.clear()
        CarHotspotOffConnectTest.HotspotState.on = true
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        AirPlayPersistence.saveManualHotspotSsid(context, "Car 5G")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "12345678")
    }

    @After fun tearDown() {
        opened.forEach { runCatching { it.finish() } }
        AppAppearanceRuntime.resetForTest()
        CarPlayBackgroundSession.clear()
        CarHotspotOffConnectTest.HotspotState.on = true
        clearSettings()
    }

    private fun clearSettings() {
        for (name in listOf("diplay", "xcertplay_airplay", "diplay_car_hotspot", "diplay_last_problem")) {
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    private fun fail(cause: ConnectionFailureCause) = LastConnectionProblem.follow(context,
        CarPlayStatus.Failed("failed", startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION, knownCause = cause))

    private fun openHome(): DiPlayActivity = Robolectric.buildActivity(DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "home")).setup().get().also { opened += it }

    private fun shown(screen: DiPlayActivity): List<String> =
        descendants(screen.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }.toList()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun refresh(home: DiPlayActivity) = ReflectionHelpers.callInstanceMethod<Unit>(home, "refreshStatus")

    private fun reportLines(): List<String> {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        return (ReflectionHelpers.callInstanceMethod<String>(activity, "diagnosticReportText",
            ReflectionHelpers.ClassParameter.from(Context::class.java, context))).lines()
    }

    private val time = "[^()（）]*\\d{1,2}:\\d{2}[^()（）]*"

    @Test fun homeSaysWhatTheLastAttemptRanInto() {
        fail(ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED)
        val shown = shown(openHome())
        val line = Regex("Last connection attempt \\($time\\): The iPhone chosen in DiPlay is no longer paired with the car")
        assertEquals(shown.toString(), 1, shown.count(line::matches))
    }

    @Test @Config(qualifiers = "zh-rTW-w1000dp-h700dp-land") fun homeSaysItInTraditionalChinese() {
        fail(ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED)
        val shown = shown(openHome())
        val line = Regex("上次連線嘗試（$time）：在 DiPlay 選擇的 iPhone 已不再與車機配對")
        assertEquals(shown.toString(), 1, shown.count(line::matches))
    }

    @Test fun nothingRememberedAddsNothing() {
        val shown = shown(openHome())
        assertTrue(shown.toString(), shown.none { it.startsWith("Last connection attempt") })
    }

    @Test fun itHidesWhileCarPlayIsConnectedAndOnceTheProblemIsForgotten() {
        fail(ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED)
        val home = openHome()
        CarPlayBackgroundSession.active = true
        refresh(home)
        assertTrue(shown(home).toString(), shown(home).none { it.startsWith("Last connection attempt") })
        CarPlayBackgroundSession.active = false
        refresh(home)
        assertEquals(shown(home).toString(), 1, shown(home).count { it.startsWith("Last connection attempt") })
        LastConnectionProblem.clear(context)
        refresh(home)
        assertTrue(shown(home).toString(), shown(home).none { it.startsWith("Last connection attempt") })
    }

    @Test fun withTheCarHotspotOffHomeKeepsItsOneHotspotWarning() {
        CarHotspotOffConnectTest.HotspotState.on = false
        fail(ConnectionFailureCause.CAR_HOTSPOT_OFF)
        val home = openHome()
        val shown = shown(home)
        assertTrue(shown.toString(), home.getString(R.string.msg_car_hotspot_off, "Car 5G") in shown)
        assertTrue(shown.toString(), shown.none { it.startsWith("Last connection attempt") })
    }

    @Test @Config(qualifiers = "zh-rTW") fun theReportSaysTheLastProblemInEnglishOnItsSecondLine() {
        fail(ConnectionFailureCause.CAR_HOTSPOT_OFF)
        val lines = reportLines()
        assertTrue(lines[0], lines[0].startsWith("DiPlay ") && lines[0].endsWith(" · private beta diagnostic report"))
        assertTrue(lines[1], lines[1].matches(Regex(
            "Last problem: Car hotspot is off \\(cause:CAR_HOTSPOT_OFF\\) at \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}")))
        assertTrue(lines[2], lines[2].startsWith("Android "))
        LastConnectionProblem.clear(context)
        assertEquals("Last problem: none recorded", reportLines()[1])
    }
}
