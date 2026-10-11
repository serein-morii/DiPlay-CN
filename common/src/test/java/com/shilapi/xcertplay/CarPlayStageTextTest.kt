package com.shilapi.xcertplay

import android.os.Handler
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** The stage line must not fall back to "Getting CarPlay ready" just because the UI is not English. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayStageTextTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "stageStatusView", TextView(activity))
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test @Config(qualifiers = "en") fun englishStages() = stagesFollowTheConnection()

    @Test @Config(qualifiers = "zh-rCN") fun chineseStages() {
        assertNotEquals("Connection interrupted. Retrying…", activity.getString(R.string.connection_interrupted_retrying))
        stagesFollowTheConnection()
    }

    private fun stagesFollowTheConnection() {
        // A wired Android 7.1 failure (#557) is a retry, not a stage that never ends.
        assertStage(R.string.connection_interrupted_retrying, CarPlayStatus.Failed("Invalid argument"))
        assertStage(R.string.iphone_usb_mode_rejected,
            CarPlayStatus.Failed("CarPlay configuration request transferred -1 of 1 bytes"))
        assertStage(R.string.connect_your_iphone_with_a_usb_cable, CarPlayStatus.WaitingForIphone)
        assertStage(R.string.allow_the_connection_permission_to_continue, CarPlayStatus.RequestingIphonePermission)
        assertStage(R.string.looking_for_your_paired_iphone, CarPlayStatus.WaitingForPairedIphone)
        assertStage(R.string.connecting_to_your_iphone, CarPlayStatus.ConnectingBluetooth)
        assertStage(R.string.opening_carplay, CarPlayStatus.RunningControl)
        assertStage(R.string.reconnecting_to_your_iphone, CarPlayStatus.ControlEnded)
        assertStage(R.string.waiting_for_mfi_coprocessor, CarPlayStatus.WaitingForMfi)
        assertStage(R.string.getting_carplay_ready, CarPlayStatus.OpeningDataPaths)
    }

    private fun assertStage(expected: Int, status: CarPlayStatus) {
        val reporter = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(
            activity, "createStatusReporter",
            ClassParameter.from(Int::class.javaPrimitiveType, ReflectionHelpers.getField<Int>(activity, "restartGeneration")),
        )
        reporter(status)
        val shown = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertEquals(status.toString(), activity.getString(expected), shown)
    }
}
