package com.shilapi.xcertplay

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** What the CarPlay screen says once the permissions a wireless start needs are refused (#290). */
@RunWith(RobolectricTestRunner::class)
class WirelessPermissionRefusalTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<CarPlayHostActivity>? = null
    private val wireless = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    )

    @Before fun setUp() {
        // A session another test left behind would be adopted instead of asking for permissions.
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        AirPlayPersistence.saveWirelessEnabled(context, true)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        // Not the built-in identity, which needs the private assets of a real build.
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.USB_CH341)
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(context).denyPermissions(*wireless.toTypedArray())
    }

    @After fun tearDown() {
        controller?.let { host ->
            runCatching { host.pause().stop().destroy() }
            for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(host.get(), field).shutdownNow()
            }
        }
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    private fun open(): CarPlayHostActivity =
        Robolectric.buildActivity(CarPlayHostActivity::class.java).setup().also { controller = it }.get()

    /** Answers the open wireless permission request as the driver (or Android) did, granting only [granted]. */
    private fun answer(activity: CarPlayHostActivity, granted: Set<String> = emptySet()): List<String> {
        val request = shadowOf(activity).lastRequestedPermission
        val asked = request.requestedPermissions.toList()
        assertTrue(asked.toString(), asked.any { it in wireless })
        shadowOf(context).grantPermissions(*granted.toTypedArray())
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            IntArray(asked.size) { if (asked[it] in granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED })
        shadowOf(Looper.getMainLooper()).idle()
        return asked
    }

    private fun stage(activity: CarPlayHostActivity): String =
        ReflectionHelpers.getField<TextView>(activity, "stageStatusView").text.toString()

    // Android 7.1 to 11 ask for Location only.
    @Config(sdk = [29])
    @Test fun refusedLocationIsNamedInsteadOfWaitingForTheIphone() {
        val activity = open()
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            answer(activity))
        assertEquals(activity.getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p), stage(activity))
        // Back on the screen without the access, it still says so.
        requireNotNull(controller).pause().resume()
        assertEquals(activity.getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p), stage(activity))
    }

    @Config(sdk = [33])
    @Test fun refusedNearbyDevicesIsNamedOnAndroid13() {
        val activity = open()
        answer(activity)
        assertEquals(activity.getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per), stage(activity))
    }

    // Android 17's local network access alone gets the sentence that sends the driver to the app permissions,
    // shown as it is (WirelessPermissionsTest covers which refusal gets which sentence).
    @Config(sdk = [33])
    @Test fun theCheckAppPermissionsSentenceIsShownAsItIs() {
        val activity = open()
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "setConnectionStage",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,
                WirelessPermissions.refusalMessage(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK))))
        assertEquals(activity.getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per), stage(activity))
    }

    // Android 12 needs both: once Location is allowed in the app permissions, the screen names the other one.
    @Config(sdk = [32])
    @Test fun theScreenNamesWhatIsStillMissingAfterTheDriverAllowsPartOfIt() {
        val activity = open()
        answer(activity)
        assertEquals(activity.getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p), stage(activity))
        requireNotNull(controller).pause()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        requireNotNull(controller).resume()
        assertEquals(activity.getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per), stage(activity))
    }
}
