package com.shilapi.xcertplay

import android.os.Handler
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test @Config(qualifiers = "zh-rTW") fun aWiredWaitWithoutAnIphoneSwapsTheCableTipForAdviceAboutTheCarPort() {
        ReflectionHelpers.callInstanceMethod<Any>(activity, "buildContentView")
        val stage = ReflectionHelpers.getField<TextView>(activity, "stageStatusView")
        val instructions = ReflectionHelpers.getField<TextView>(activity, "connectionInstructionsView")
        val cableTip = activity.getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
        val noDeviceTip = activity.getString(R.string.connection_help_no_usb_device)
        val notIphoneTip = activity.getString(R.string.connection_help_usb_device_not_iphone)
        fun assertInstructions(expected: String, status: CarPlayStatus) {
            report(status)
            assertEquals(status.toString(), expected, instructions.text.toString())
            assertFalse(status.toString(), stage.text.contains(expected))
        }
        assertInstructions(cableTip, CarPlayStatus.WaitingForIphone)
        assertInstructions(noDeviceTip, CarPlayStatus.IphoneNotListed(nonHubDeviceListed = false))
        assertInstructions(notIphoneTip, CarPlayStatus.IphoneNotListed(nonHubDeviceListed = true))
        assertInstructions(cableTip, CarPlayStatus.WaitingForIphone)
        assertInstructions(noDeviceTip, CarPlayStatus.IphoneNotListed(nonHubDeviceListed = false))
        assertInstructions(cableTip, CarPlayStatus.RequestingIphonePermission)
    }

    private fun stagesFollowTheConnection() {
        // A wired Android 7.1 failure (#557) is a retry, not a stage that never ends.
        assertStage(R.string.connection_interrupted_retrying, CarPlayStatus.Failed("Invalid argument"))
        assertStage(R.string.iphone_usb_mode_rejected,
            CarPlayStatus.Failed("CarPlay configuration request transferred -1 of 1 bytes"))
        assertStage(R.string.connect_your_iphone_with_a_usb_cable, CarPlayStatus.WaitingForIphone)
        assertStage(R.string.car_sees_no_usb_device, CarPlayStatus.IphoneNotListed(nonHubDeviceListed = false))
        assertStage(R.string.car_sees_usb_device_not_iphone, CarPlayStatus.IphoneNotListed(nonHubDeviceListed = true))
        assertStage(R.string.getting_carplay_ready, CarPlayStatus.WaitingForReenumeration)
        assertStage(R.string.allow_the_connection_permission_to_continue, CarPlayStatus.RequestingIphonePermission)
        assertStage(R.string.looking_for_your_paired_iphone, CarPlayStatus.WaitingForPairedIphone)
        assertStage(R.string.connecting_to_your_iphone, CarPlayStatus.ConnectingBluetooth)
        assertStage(R.string.opening_carplay, CarPlayStatus.RunningControl)
        assertStage(R.string.reconnecting_to_your_iphone, CarPlayStatus.ControlEnded)
        assertStage(R.string.waiting_for_mfi_coprocessor, CarPlayStatus.WaitingForMfi)
        assertStage(R.string.getting_carplay_ready, CarPlayStatus.OpeningDataPaths)

        val notPaired = "The selected iPhone is no longer paired. Choose it again in DiPlay."
        assertStage(R.string.chosen_iphone_not_paired,
            CarPlayStatus.Failed(notPaired, knownCause = ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED))
        assertStage(R.string.connection_interrupted_retrying, CarPlayStatus.Failed(notPaired))
        val ambiguous = "No unambiguous bonded iPhone found; pair one iPhone and retry"
        assertStage(R.string.pair_your_iphone, CarPlayStatus.Failed(ambiguous, knownCause = ConnectionFailureCause.NO_PAIRED_IPHONE))
        assertStage(R.string.choose_your_iphone, CarPlayStatus.Failed(ambiguous, knownCause = ConnectionFailureCause.IPHONE_NOT_CHOSEN))
        assertStage(R.string.wifi_direct_channel_unavailable, CarPlayStatus.Failed("Could not establish WIFI_P2P hotspot: " +
            "Wi-Fi Direct could not use channel 149. Choose Auto or another channel. Wi-Fi P2P createGroup failed: ERROR (code=0)",
            knownCause = ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE))

        val off = "The car hotspot is off. Turn it on in the car settings and connect again."
        assertStage(R.string.car_hotspot_is_off, CarPlayStatus.Failed(off,
            startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION, knownCause = ConnectionFailureCause.CAR_HOTSPOT_OFF))
        assertStage(R.string.connection_interrupted_retrying, CarPlayStatus.Failed(off))
        assertStage(R.string.car_hotspot_details_differ,
            CarPlayStatus.Failed("Manual hotspot SSID does not match the active local AP configuration: 'Other'",
                startupFailure = WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                knownCause = ConnectionFailureCause.CAR_HOTSPOT_DETAILS_DIFFER))
        assertStage(R.string.hotspot_interface_not_found,
            CarPlayStatus.Failed("Could not establish MANUAL hotspot: Hotspot network is not ready",
                startupFailure = WirelessStartupFailure.HOTSPOT_NOT_READY,
                knownCause = ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND))
        assertStage(R.string.hotspot_control_timeout,
            CarPlayStatus.Failed("Timed out waiting for the car hotspot. Open the car hotspot settings and connect again.",
                startupFailure = WirelessStartupFailure.HOTSPOT_NOT_READY,
                knownCause = ConnectionFailureCause.CAR_HOTSPOT_START_TIMED_OUT))
        assertStage(R.string.hotspot_control_failed,
            CarPlayStatus.Failed("The car could not start its hotspot. Open the car hotspot settings and connect again.",
                startupFailure = WirelessStartupFailure.HOTSPOT_NOT_READY,
                knownCause = ConnectionFailureCause.CAR_HOTSPOT_START_FAILED))
    }

    private fun assertStage(expected: Int, status: CarPlayStatus) {
        report(status)
        val shown = ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()
        assertEquals(status.toString(), activity.getString(expected), shown)
    }

    private fun report(status: CarPlayStatus) = ReflectionHelpers.callInstanceMethod<(CarPlayStatus) -> Unit>(
        activity, "createStatusReporter",
        ClassParameter.from(Int::class.javaPrimitiveType, ReflectionHelpers.getField<Int>(activity, "restartGeneration")),
    )(status)
}
