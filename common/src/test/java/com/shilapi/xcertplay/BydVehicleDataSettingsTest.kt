package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.hud.BydAdbAccess
import com.shilapi.xcertplay.hud.BydFieldProbeResult
import com.shilapi.xcertplay.hud.BydFieldSource
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.BydReadAddress
import com.shilapi.xcertplay.hud.BydVehicleCapabilities
import com.shilapi.xcertplay.hud.BydVehicleField
import com.shilapi.xcertplay.hud.BydVehicleFieldStore
import com.shilapi.xcertplay.hud.BydVehicleProbeOutcome
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class BydVehicleDataSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val activity get() = requireNotNull(controller).get()
    private lateinit var backend: FakeVehicleSettingsBackend
    private var reconnects = 0

    @Before fun setUp() {
        backend = FakeVehicleSettingsBackend()
        BydVehicleSettingsBackendProvider.current = backend
        shadowOf(context.packageManager).removePackage("com.byd.amapservice")
        context.getSharedPreferences("diplay_byd_outputs", 0).edit().clear().commit()
        context.getSharedPreferences("diplay_byd_vehicle_fields", 0).edit().clear().commit()
        BydVehicleFieldStore.clearMemoryForTests()
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        controller?.pause()?.stop()?.destroy()
        BydVehicleSettingsBackendProvider.reset()
        BydVehicleFieldStore.clearMemoryForTests()
        shadowOf(context.packageManager).removePackage("com.byd.amapservice")
    }

    @Test fun defaultModeShowsTheDiLink5SettingsBehindTheAdvancedButton() {
        openSettings()

        assertFalse(BydOutputSettings.navigationAvailable(activity))
        assertTrue(texts().any { it.text == activity.getString(R.string.advanced_vehicle_data) })
        assertFalse(switches().any { it.contentDescription == activity.getString(R.string.car_battery_for_the_iphone) })
        assertFalse(switches().any { it.contentDescription == activity.getString(R.string.wheel_speed_for_tunnels) })
        assertFalse(switches().any { it.contentDescription == activity.getString(R.string.cluster_song) })
        assertFalse(switches().any { it.contentDescription == activity.getString(R.string.navigation_on_hud_and_instrument_cluster) })

        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        assertTrue(texts().any { it.text == activity.getString(R.string.advanced_vehicle_data_description) })
        assertTrue(texts().any { it.text.toString().contains(activity.getString(R.string.vehicle_data_mode_default)) })
        assertTrue(texts().any { it.text == activity.getString(R.string.check_adb_access) })
        assertTrue(switches().any { it.contentDescription == activity.getString(R.string.car_battery_for_the_iphone) })
        assertTrue(switches().any { it.contentDescription == activity.getString(R.string.wheel_speed_for_tunnels) })
        assertTrue(switches().any { it.contentDescription == activity.getString(R.string.video_while_parked) })
    }

    @Test fun successfulLiveProbeRevealsOnlySupportedSettings() {
        BydOutputSettings.setLegacyVehicleProbe(context, true)
        openSettings()
        ReflectionHelpers.setField(activity, "bydVehicleAdvancedExpanded", true)
        ReflectionHelpers.setField(activity, "adbAccessState", BydAdbAccess.State.READY)
        ReflectionHelpers.setField(activity, "vehicleProbeOutcome",
            BydVehicleProbeOutcome(BydAdbAccess.State.READY, supportedCapabilities()))
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")

        assertTrue(switches().any { it.contentDescription == activity.getString(R.string.car_battery_for_the_iphone) })
        assertTrue(switches().any { it.contentDescription == activity.getString(R.string.wheel_speed_for_tunnels) })
        assertTrue(switches().any { it.contentDescription == activity.getString(R.string.video_while_parked) })
    }

    @Test fun savedProbeAndEnabledSwitchSurviveAFreshActivityWithoutReprobing() {
        BydVehicleFieldStore.save(context, supportedCapabilities(BydVehicleFieldStore.firmwareKey()))
        BydOutputSettings.setWheelSpeedToIphone(context, true)
        backend.checkResult = readableStatus()
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        assertTrue(vehicleSwitch(R.string.wheel_speed_for_tunnels).isChecked)
        requireNotNull(controller).pause().stop().destroy()
        controller = null
        BydVehicleFieldStore.clearMemoryForTests()
        // Work the first activity left queued would be ignored; count only the fresh one's.
        backend.tasks.clear()
        openSettings()
        shadowOf(Looper.getMainLooper()).idle()
        backend.runAll("diplay-byd13-auto-validate")
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        assertTrue(texts().any { it.text == activity.getString(R.string.hide_advanced_vehicle_data) })
        assertTrue(vehicleSwitch(R.string.wheel_speed_for_tunnels).isChecked)
        // The fresh activity validated the saved fields by reading them, not by probing again.
        assertEquals(1, backend.checkCalls)
        assertEquals(0, backend.probeCalls)
    }

    @Test fun theHotspotCardCannotExposeFieldsRejectedByTheLegacyProbe() {
        val saved = supportedCapabilities(BydVehicleFieldStore.firmwareKey(), without = setOf(BydVehicleField.GEAR))
        BydVehicleFieldStore.save(context, saved)
        BydOutputSettings.setLegacyVehicleProbe(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()
        val hotspot = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderBydAdbControls",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, hotspot),
            ReflectionHelpers.ClassParameter.from(LocalAdb.Access::class.java, LocalAdb.Access.READY))
        val page = descendants(activity.window.decorView).filterIsInstance<ScrollView>().first()
            .getChildAt(0) as LinearLayout
        page.addView(hotspot)

        assertEquals(1, switches().count { it.contentDescription == activity.getString(R.string.car_battery_for_the_iphone) })
        assertEquals(0, switches().count { it.contentDescription == activity.getString(R.string.wheel_speed_for_tunnels) })
        assertEquals(0, switches().count { it.contentDescription == activity.getString(R.string.video_while_parked) })
        assertTrue(BydOutputSettings.legacyVehicleProbe(context))
        assertEquals(saved, BydVehicleFieldStore.load(context))
    }

    @Test fun automaticValidationWaitsForHotspotAuthorizationThenResumes() {
        val saved = supportedCapabilities(BydVehicleFieldStore.firmwareKey())
        BydVehicleFieldStore.save(context, saved)
        BydOutputSettings.setLegacyVehicleProbe(context, true)
        backend.checkResult = readableStatus()
        controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        )
        // setup() can drain the validation posted by onCreate; establish authorization first.
        ReflectionHelpers.setField(activity, "adbSwitchChangePending", true)
        requireNotNull(controller).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(backend.tasks.none { it.first == "diplay-byd13-auto-validate" })
        assertEquals(0, backend.checkCalls)
        assertEquals(0, backend.probeCalls)
        assertTrue(ReflectionHelpers.getField<Boolean>(activity, "automaticVehicleValidationPending"))

        ReflectionHelpers.setField(activity, "adbSwitchChangePending", false)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "runPendingAutomaticVehicleValidation")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, backend.tasks.count { it.first == "diplay-byd13-auto-validate" })
        backend.runAll("diplay-byd13-auto-validate")
        assertEquals(1, backend.checkCalls)
        assertEquals(0, backend.probeCalls)
        assertEquals(saved, BydVehicleFieldStore.load(context))
    }

    @Test fun temporaryAdbFailureDoesNotHideSavedFunctionsOrClearSwitches() {
        BydVehicleFieldStore.save(context, supportedCapabilities(BydVehicleFieldStore.firmwareKey()))
        BydOutputSettings.setVideoWhileParked(context, true)
        backend.checkResult = BydAdbAccess.Status(BydAdbAccess.State.ADB_OFF)
        openSettings()
        shadowOf(Looper.getMainLooper()).idle()
        backend.runAll("diplay-byd13-auto-validate")
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        assertTrue(vehicleSwitch(R.string.video_while_parked).isChecked)
        assertTrue(texts().any { it.text == activity.getString(R.string.probe_vehicle_data_again) })
    }

    @Test fun clusterSongSwitchStaysInTheBydNavigationSectionOnly() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.byd.amapservice" })
        openSettings()
        openCategory(R.string.settings_navigation)

        assertTrue(texts().any { it.text == activity.getString(R.string.byd_navigation) })
        assertEquals(1, switches().count { it.contentDescription == activity.getString(R.string.cluster_song) })
        openCategory(R.string.settings_advanced)
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()
        assertEquals(0, switches().count { it.contentDescription == activity.getString(R.string.cluster_song) })
    }

    @Test fun withoutBydNavigationTheClusterSongSwitchIsUnderAdvancedVehicleData() {
        openSettings()
        assertFalse(texts().any { it.text == activity.getString(R.string.byd_navigation) })
        assertFalse(switches().any { it.contentDescription == activity.getString(R.string.cluster_song) })

        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()
        vehicleSwitch(R.string.cluster_song).performClick()

        assertTrue(BydOutputSettings.clusterSong(context))
    }

    @Test fun scheduledValidationCannotLeaveAUserProbeStuck() {
        BydVehicleFieldStore.save(context, supportedCapabilities(BydVehicleFieldStore.firmwareKey()))
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(BydVehicleFieldStore.firmwareKey()),
        )
        backend.checkResult = readableStatus()
        openSettings()

        invokeProbe()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "scheduleAutomaticVehicleValidation")
        shadowOf(Looper.getMainLooper()).idle()
        backend.run("diplay-byd13-probe")
        backend.runAll("diplay-byd13-auto-validate")

        assertFalse(ReflectionHelpers.getField(activity, "vehicleProbeAuthorizationInProgress"))
        assertFalse(ReflectionHelpers.getField(activity, "vehicleProbeInProgress"))
        assertFalse(ReflectionHelpers.getField(activity, "automaticVehicleValidationInProgress"))
    }

    @Test fun scheduledValidationCannotLeaveAnAdbCheckStuck() {
        BydVehicleFieldStore.save(context, supportedCapabilities(BydVehicleFieldStore.firmwareKey()))
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.checkResult = readableStatus()
        openSettings()

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "checkAdbState",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        shadowOf(Looper.getMainLooper()).idle()
        backend.run("diplay-adb-state")
        backend.runAll("diplay-byd13-auto-validate")

        assertFalse(ReflectionHelpers.getField(activity, "adbCheckInProgress"))
        assertFalse(ReflectionHelpers.getField(activity, "automaticVehicleValidationInProgress"))
    }

    @Test fun aNewIntentDoesNotCancelAUserProbe() {
        BydVehicleFieldStore.save(context, supportedCapabilities(BydVehicleFieldStore.firmwareKey()))
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(BydVehicleFieldStore.firmwareKey()),
        )
        backend.checkResult = readableStatus()
        openSettings()

        invokeProbe()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter.from(
                Intent::class.java,
                Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
            ))
        shadowOf(Looper.getMainLooper()).idle()
        backend.run("diplay-byd13-probe")
        backend.runAll("diplay-byd13-auto-validate")

        assertFalse(ReflectionHelpers.getField(activity, "vehicleProbeAuthorizationInProgress"))
        assertFalse(ReflectionHelpers.getField(activity, "vehicleProbeInProgress"))
    }

    @Test fun lostFieldsNeedExplicitReplacement() {
        val currentKey = BydVehicleFieldStore.firmwareKey()
        BydVehicleFieldStore.save(context, supportedCapabilities(currentKey))
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(currentKey, without = setOf(BydVehicleField.GEAR)),
        )
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        invokeProbe()
        backend.run("diplay-byd13-probe")

        assertTrue(BydVehicleFieldStore.load(context)!!.gearSupported)
        assertTrue(texts().any { it.text.toString().contains(activity.getString(R.string.vehicle_field_gear)) })
        texts().single { it.text == activity.getString(R.string.replace_saved_vehicle_data_anyway) }.performClick()
        assertFalse(BydVehicleFieldStore.load(context)!!.gearSupported)
    }

    @Test fun aDestroyedUserProbeCannotSaveOrPublishItsLateCandidate() {
        val saved = preparePublishingProbe()
        backend.onProbe = {
            requireNotNull(controller).pause().stop().destroy()
            controller = null
        }

        invokeProbe()
        backend.run("diplay-byd13-probe")

        assertEquals(1, backend.probeCalls)
        assertEquals(saved, BydVehicleFieldStore.load(context))
        assertEquals(25.0, publishedBatteryPercent(), 0.0)
    }

    @Test fun aModeChangeBeforeTheUserProbeReturnsKeepsThePreviousSnapshot() {
        val saved = preparePublishingProbe()
        backend.onProbe = { BydOutputSettings.setLegacyVehicleProbe(context, false) }

        invokeProbe()
        backend.run("diplay-byd13-probe")

        assertEquals(1, backend.probeCalls)
        assertFalse(BydOutputSettings.legacyVehicleProbe(context))
        assertEquals(saved, BydVehicleFieldStore.load(context))
        assertEquals(25.0, publishedBatteryPercent(), 0.0)
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "vehicleProbeInProgress"))
    }

    @Test fun aPausedUserProbeStillSavesAndPublishesItsAcceptedCandidate() {
        preparePublishingProbe()
        backend.onProbe = { requireNotNull(controller).pause().stop() }

        invokeProbe()
        backend.run("diplay-byd13-probe")

        assertEquals(backend.probeResult.capabilities, BydVehicleFieldStore.load(context))
        assertEquals(90.0, publishedBatteryPercent(), 0.0)
        requireNotNull(controller).destroy()
        controller = null
    }

    @Test fun selectingDefaultAgainCannotBeOverriddenByALateLegacySelection() {
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(BydVehicleFieldStore.firmwareKey()),
        )
        openSettings()
        backend.onProbe = { selectLegacyMode(false) }

        selectLegacyMode(true)
        backend.run("diplay-byd13-probe")

        assertFalse(BydOutputSettings.legacyVehicleProbe(context))
        assertEquals(null, BydVehicleFieldStore.load(context))
        assertFalse(ReflectionHelpers.getField<Boolean>(activity, "vehicleProbeInProgress"))
    }

    private fun preparePublishingProbe(): BydVehicleCapabilities {
        val capabilities = supportedCapabilities(BydVehicleFieldStore.firmwareKey())
        val saved = capabilities.copy(fields = capabilities.fields + (BydVehicleField.SOC to
            capabilities.result(BydVehicleField.SOC).copy(value = 25.0)))
        BydOutputSettings.setLegacyVehicleProbe(context, true)
        BydVehicleFieldStore.save(context, saved)
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            saved.copy(fields = saved.fields + (BydVehicleField.SOC to
                saved.result(BydVehicleField.SOC).copy(value = 90.0))),
        )
        openSettings()
        return saved
    }

    private fun publishedBatteryPercent(): Double {
        // Read the provider without starting its real ADB polling executor.
        val provider = Class.forName("com.shilapi.xcertplay.hud.BydBatteryStatus")
            .getField("INSTANCE").get(null) as VehicleStatusProvider
        return requireNotNull(provider.snapshot()).batteryPercent
    }

    @Test fun aPassingValidationKeepsTheOfferToReplaceSavedData() {
        val currentKey = BydVehicleFieldStore.firmwareKey()
        BydVehicleFieldStore.save(context, supportedCapabilities(currentKey))
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(currentKey, without = setOf(BydVehicleField.GEAR)),
        )
        backend.checkResult = readableStatus()
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()
        invokeProbe()
        backend.run("diplay-byd13-probe")

        // With no switch on, the check after a return from CarPlay passes without reading the lost gear.
        returnFromCarPlay()
        backend.runAll("diplay-byd13-auto-validate")

        assertTrue(texts().any { it.text == activity.getString(R.string.replace_saved_vehicle_data_anyway) })
    }

    @Test fun replaceAnywayKeepsTheAdbStateAndASnapshotSavedSince() {
        val currentKey = BydVehicleFieldStore.firmwareKey()
        BydVehicleFieldStore.save(context, supportedCapabilities(currentKey))
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(currentKey, without = setOf(BydVehicleField.GEAR)),
        )
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()
        invokeProbe()
        backend.run("diplay-byd13-probe")

        // Meanwhile ADB was turned off, and another probe saved its result.
        backend.checkResult = BydAdbAccess.Status(BydAdbAccess.State.ADB_OFF)
        returnFromCarPlay()
        backend.runAll("diplay-byd13-auto-validate")
        val savedSince = supportedCapabilities(currentKey).copy(detectedAtMillis = 42)
        BydVehicleFieldStore.save(context, savedSince)
        texts().single { it.text == activity.getString(R.string.replace_saved_vehicle_data_anyway) }.performClick()

        assertEquals(savedSince, BydVehicleFieldStore.load(context))
        assertTrue(texts().any { it.text == activity.getString(R.string.adb_off) })
        assertTrue(texts().any {
            it.text == activity.getString(R.string.vehicle_probe_failed,
                activity.getString(R.string.vehicle_probe_snapshot_changed))
        })
    }

    @Test fun aFailedReplacementIsShownInsteadOfCrashing() {
        val currentKey = BydVehicleFieldStore.firmwareKey()
        BydVehicleFieldStore.save(context, supportedCapabilities(currentKey))
        backend.checkStateResult = BydAdbAccess.State.READY
        // The store refuses to write another firmware's probe, standing in for a failed write.
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities("another firmware", without = setOf(BydVehicleField.GEAR)),
        )
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()
        invokeProbe()
        backend.run("diplay-byd13-probe")

        texts().single { it.text == activity.getString(R.string.replace_saved_vehicle_data_anyway) }.performClick()

        assertTrue(BydVehicleFieldStore.load(context)!!.gearSupported)
        assertTrue(texts().any {
            it.text == activity.getString(R.string.vehicle_probe_failed, "probe belongs to another firmware")
        })
    }

    @Test fun anAdbCheckAloneDoesNotStartAVehicleValidation() {
        BydVehicleFieldStore.save(context, supportedCapabilities(BydVehicleFieldStore.firmwareKey()))
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.checkResult = readableStatus()
        openSettings()
        shadowOf(Looper.getMainLooper()).idle()
        backend.runAll("diplay-byd13-auto-validate")

        // As when Dashboard song or Dashboard map is turned on.
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "checkAdbState",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
        backend.run("diplay-adb-state")

        assertEquals(1, backend.checkCalls)
        assertFalse(backend.tasks.any { it.first == "diplay-byd13-auto-validate" })
    }

    @Test fun aSwitchTurnedOnDuringAValidationIsValidatedAfterIt() {
        BydVehicleFieldStore.save(context, supportedCapabilities(BydVehicleFieldStore.firmwareKey()))
        backend.checkResult = readableStatus()
        openSettings()
        shadowOf(Looper.getMainLooper()).idle()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        // The first validation has not finished reading when wheel speed is turned on.
        vehicleSwitch(R.string.wheel_speed_for_tunnels).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        backend.runAll("diplay-byd13-auto-validate")

        assertEquals(2, backend.checkCalls)
    }

    @Test fun aValidationCancelledBeforeItsSecondCheckReadsNothingMore() {
        BydVehicleFieldStore.save(context, supportedCapabilities(BydVehicleFieldStore.firmwareKey()))
        BydOutputSettings.setWheelSpeedToIphone(context, true)
        backend.checkResult = BydAdbAccess.Status(BydAdbAccess.State.READY)
        openSettings()
        shadowOf(Looper.getMainLooper()).idle()
        // A user operation cancels the validation once its first check finds the fields unreadable.
        backend.onCheck = {
            backend.onCheck = null
            ReflectionHelpers.callInstanceMethod<Any?>(activity, "cancelAutomaticVehicleValidationForUserOperation",
                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
        }
        backend.run("diplay-byd13-auto-validate")

        assertEquals(1, backend.checkCalls)
        assertEquals(0, backend.probeCalls)
    }

    @Test fun oneTimeAdbApprovalExplainsAlwaysAllow() {
        BydOutputSettings.setLegacyVehicleProbe(context, true)
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(BydAdbAccess.State.NOT_APPROVED)
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        invokeProbe()
        assertTrue(texts().any { it.text == activity.getString(R.string.adb_checking_may_ask) })
        backend.run("diplay-byd13-probe")

        assertEquals(2, backend.probeCalls)
        assertTrue(texts().any { it.text.toString().contains(activity.getString(R.string.vehicle_probe_allowed_once)) })
    }

    @Test fun aProbeRefusedRightAfterApprovalIsRetriedOnce() {
        BydOutputSettings.setLegacyVehicleProbe(context, true)
        backend.checkStateResult = BydAdbAccess.State.READY
        // adbd saves an "Always allow" key just after approving, so the probe's first connection can be early.
        backend.queuedProbeResults += BydVehicleProbeOutcome(BydAdbAccess.State.NOT_APPROVED)
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(BydVehicleFieldStore.firmwareKey()),
        )
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        invokeProbe()
        backend.run("diplay-byd13-probe")

        assertEquals(2, backend.probeCalls)
        assertTrue(BydVehicleFieldStore.load(context)!!.motionSupported)
        assertFalse(texts().any { it.text.toString().contains(activity.getString(R.string.vehicle_probe_allowed_once)) })
    }

    @Test fun failedLegacySelectionKeepsTheDefaultMode() {
        backend.checkStateResult = BydAdbAccess.State.ADB_OFF
        openSettings()
        ReflectionHelpers.setField(activity, "bydVehicleAdvancedExpanded", true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")

        selectLegacyMode(true)
        backend.run("diplay-byd13-probe")

        assertFalse(BydOutputSettings.legacyVehicleProbe(context))
        assertEquals(null, BydVehicleFieldStore.load(context))
        assertTrue(texts().any { it.text.toString().contains(activity.getString(R.string.vehicle_data_mode_default)) })
    }

    @Test fun successfulLegacySelectionPersistsAndSwitchingBackKeepsTheProbe() {
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(BydVehicleFieldStore.firmwareKey()),
        )
        openSettings()
        ReflectionHelpers.setField(activity, "bydVehicleAdvancedExpanded", true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")

        selectLegacyMode(true)
        backend.run("diplay-byd13-probe")

        assertTrue(BydOutputSettings.legacyVehicleProbe(context))
        assertTrue(BydVehicleFieldStore.load(context)!!.motionSupported)

        selectLegacyMode(false)

        assertFalse(BydOutputSettings.legacyVehicleProbe(context))
        assertTrue(BydVehicleFieldStore.load(context)!!.motionSupported)
    }

    @Test fun defaultModeShowsUnreadableDataAndReconnectsOnlyOnceItIsReadable() {
        backend.checkResult = BydAdbAccess.Status(BydAdbAccess.State.READY)
        openSettings()
        connectCarPlay()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        vehicleSwitch(R.string.car_battery_for_the_iphone).performClick()
        backend.run("diplay-adb-state")

        assertTrue(BydOutputSettings.batteryToIphone(context))
        assertTrue(texts().any { it.text == activity.getString(R.string.adb_battery_unreadable) })
        assertEquals(0, reconnects)

        // Approval or a fix on the car later: the next check applies the waiting switch once.
        backend.checkResult = readableStatus()
        texts().single { it.text == activity.getString(R.string.check_adb_access) }.performClick()
        backend.run("diplay-adb-state")

        assertTrue(texts().any { it.text == activity.getString(R.string.adb_battery_reading, 75, 450) })
        assertEquals(1, reconnects)

        texts().single { it.text == activity.getString(R.string.check_adb_access) }.performClick()
        backend.run("diplay-adb-state")
        assertEquals(1, reconnects)
    }

    @Test fun switchingToLegacyReconnectsOnlyWhenAVehicleDataSwitchIsOn() {
        backend.checkStateResult = BydAdbAccess.State.READY
        backend.probeResult = BydVehicleProbeOutcome(
            BydAdbAccess.State.READY,
            supportedCapabilities(BydVehicleFieldStore.firmwareKey()),
        )
        openSettings()
        connectCarPlay()

        selectLegacyMode(true)
        backend.run("diplay-byd13-probe")
        assertTrue(BydOutputSettings.legacyVehicleProbe(context))
        assertEquals(0, reconnects)

        selectLegacyMode(false)
        BydOutputSettings.setWheelSpeedToIphone(context, true)
        selectLegacyMode(true)
        assertEquals(1, reconnects)
    }

    @Test fun modeChoiceIsDisabledWhileAnAdbCheckRuns() {
        backend.checkResult = readableStatus()
        openSettings()
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        texts().single { it.text == activity.getString(R.string.check_adb_access) }.performClick()
        assertFalse(modeChoice().isEnabled)

        backend.run("diplay-adb-state")
        assertTrue(modeChoice().isEnabled)
    }

    @Test fun passingAutomaticValidationClearsAnOldProbeError() {
        val currentKey = BydVehicleFieldStore.firmwareKey()
        BydVehicleFieldStore.save(context, supportedCapabilities(currentKey))
        BydOutputSettings.setVideoWhileParked(context, true)
        backend.checkResult = readableStatus()
        openSettings()
        ReflectionHelpers.setField(
            activity,
            "vehicleProbeOutcome",
            BydVehicleProbeOutcome(BydAdbAccess.State.READY, error = "old error"),
        )
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        shadowOf(Looper.getMainLooper()).idle()
        backend.run("diplay-byd13-auto-validate")

        assertEquals(null, ReflectionHelpers.getField<BydVehicleProbeOutcome?>(activity, "vehicleProbeOutcome"))
        assertFalse(texts().any { it.text.toString().contains("old error") })
    }

    @Test fun anOlderProbeOutcomeCannotHideANewerSavedSnapshot() {
        val currentKey = BydVehicleFieldStore.firmwareKey()
        val saved = supportedCapabilities(currentKey).copy(detectedAtMillis = 200)
        val older = supportedCapabilities(
            currentKey,
            without = setOf(BydVehicleField.GEAR),
        ).copy(detectedAtMillis = 100)
        BydVehicleFieldStore.save(context, saved)
        openSettings()
        ReflectionHelpers.setField(
            activity,
            "vehicleProbeOutcome",
            BydVehicleProbeOutcome(BydAdbAccess.State.READY, older),
        )
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        assertTrue(switches().any { it.contentDescription == activity.getString(R.string.video_while_parked) })
    }

    @Test fun theSavedSnapshotIsShownWhateverTheClockSays() {
        val currentKey = BydVehicleFieldStore.firmwareKey()
        BydVehicleFieldStore.save(context, supportedCapabilities(currentKey).copy(detectedAtMillis = 100))
        openSettings()
        // An older result stamped later, as after the clock was set back.
        ReflectionHelpers.setField(
            activity,
            "vehicleProbeOutcome",
            BydVehicleProbeOutcome(
                BydAdbAccess.State.READY,
                supportedCapabilities(currentKey, without = setOf(BydVehicleField.GEAR)).copy(detectedAtMillis = 200),
            ),
        )
        texts().single { it.text == activity.getString(R.string.advanced_vehicle_data) }.performClick()

        assertTrue(switches().any { it.contentDescription == activity.getString(R.string.video_while_parked) })
    }

    @Test fun samePageRenderKeepsScrollAndPageChangeStartsAtTop() {
        openSettings()
        val first = layoutRoot()
        first.scrollTo(0, 400)
        val savedY = first.scrollY
        assertTrue(savedY > 0)

        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        val second = layoutRoot()
        assertEquals(savedY, second.scrollY)

        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ABOUT)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        val third = layoutRoot()
        assertEquals(0, third.scrollY)
    }

    @Test fun rendersBeforeTheNextLayoutKeepTheScroll() {
        openSettings()
        val first = layoutRoot()
        first.scrollTo(0, 400)
        val savedY = first.scrollY
        assertTrue(savedY > 0)

        // A result arriving behind CarPlay: a stopped window dispatches pre-draw but skips layout.
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
        // Back from CarPlay: onNewIntent and onResume both render before the next frame.
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")

        assertEquals(savedY, layoutRoot().scrollY)
    }

    private fun openSettings() {
        controller = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup()
        openCategory(R.string.settings_advanced)
    }

    private fun openCategory(title: Int) {
        val description = activity.getString(R.string.settings_open_category, activity.getString(title))
        var target = descendants(activity.window.decorView).firstOrNull { it.contentDescription == description }
        if (target == null) {
            activity.onBackPressedDispatcher.onBackPressed()
            target = descendants(activity.window.decorView).first { it.contentDescription == description }
        }
        target.performClick()
    }

    private fun switches(): Sequence<Switch> = descendants(activity.window.decorView).filterIsInstance<Switch>()

    private fun texts(): Sequence<TextView> = descendants(activity.window.decorView).filterIsInstance<TextView>()

    private fun vehicleSwitch(title: Int): Switch = switches()
        .single { it.contentDescription == activity.getString(title) }

    private fun invokeProbe() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "probeVehicleData",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, true))
    }

    /** Three-finger gesture or Back to DiPlay: the settings page comes back through a new intent. */
    private fun returnFromCarPlay() {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "onNewIntent",
            ReflectionHelpers.ClassParameter.from(
                Intent::class.java,
                Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
            ))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun selectLegacyMode(enabled: Boolean) {
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "selectVehicleDataMode",
            ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, enabled))
    }

    private fun modeChoice(): TextView = texts()
        .single { it.text.startsWith(activity.getString(R.string.vehicle_data_mode) + " · ") }

    /** As in LocationReportingSettingsTest: a connected host without transports; counts reconnects. */
    private fun connectCarPlay() {
        AirPlayPersistence.saveWirelessEnabled(context, false)
        ReflectionHelpers.setField(activity, "setupError", null)
        val stop: ((() -> Unit) -> Unit) = { completion ->
            reconnects++
            completion()
        }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
    }

    private fun readableStatus() = BydAdbAccess.Status(
        state = BydAdbAccess.State.READY,
        batteryPercent = 75.0,
        rangeKm = 450,
        speedKmh = 0.0,
        gear = VehicleGear.PARK,
    )

    private fun layoutRoot(): ScrollView {
        val decor = activity.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1080, 600)
        return descendants(decor).filterIsInstance<ScrollView>().first()
    }

    private fun supportedCapabilities(
        firmwareKey: String = "test",
        without: Set<BydVehicleField> = emptySet(),
    ) = BydVehicleCapabilities(
        fields = BydVehicleField.entries.associateWith { field ->
            val value = when (field) {
                BydVehicleField.SPEED -> 0.0
                BydVehicleField.GEAR -> 1.0
                BydVehicleField.SOC -> 75.0
                BydVehicleField.RANGE -> 450.0
                BydVehicleField.REMAINING_KWH -> 60.0
                BydVehicleField.BMS_STATE -> 0.0
            }
            if (field in without) BydFieldProbeResult(field, supported = false, address = null)
            else BydFieldProbeResult(
                    field,
                    supported = true,
                    address = BydReadAddress(1000, field.ordinal + 1,
                        if (field in setOf(BydVehicleField.SPEED, BydVehicleField.SOC, BydVehicleField.REMAINING_KWH)) 7 else 5,
                        BydFieldSource.FIRMWARE),
                    value = value,
                )
        },
        catalogAvailable = true,
        firmwareKey = firmwareKey,
    )

    private class FakeVehicleSettingsBackend : BydVehicleSettingsBackend {
        var checkResult = BydAdbAccess.Status(BydAdbAccess.State.ADB_OFF)
        var checkStateResult = BydAdbAccess.State.ADB_OFF
        var probeResult = BydVehicleProbeOutcome(BydAdbAccess.State.ADB_OFF)
        /** Returned by the next probes before [probeResult]. */
        val queuedProbeResults = ArrayDeque<BydVehicleProbeOutcome>()
        var onCheck: (() -> Unit)? = null
        var onProbe: (() -> Unit)? = null
        var checkCalls = 0
        var probeCalls = 0
        val tasks = mutableListOf<Pair<String, () -> Unit>>()

        override fun check(context: android.content.Context, mayAsk: Boolean): BydAdbAccess.Status {
            checkCalls++
            onCheck?.invoke()
            return checkResult
        }

        override fun checkState(context: android.content.Context, mayAsk: Boolean) = checkStateResult

        override fun probe(context: android.content.Context, persist: Boolean): BydVehicleProbeOutcome {
            probeCalls++
            onProbe?.invoke()
            return queuedProbeResults.removeFirstOrNull() ?: probeResult
        }

        override fun execute(name: String, block: () -> Unit) {
            tasks += name to block
        }

        fun run(name: String) {
            val index = tasks.indexOfFirst { it.first == name }
            check(index >= 0) { "No queued task named $name; queued=${tasks.map { it.first }}" }
            tasks.removeAt(index).second.invoke()
            shadowOf(Looper.getMainLooper()).idle()
        }

        fun runAll(name: String) {
            while (tasks.any { it.first == name }) run(name)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
