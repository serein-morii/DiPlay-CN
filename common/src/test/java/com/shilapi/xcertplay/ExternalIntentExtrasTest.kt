package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** DiPlayActivity is exported, so any app can send it extras. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ExternalIntentExtrasTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val phone = "12:34:56:78:9A:BC"
    private val controllers = mutableListOf<ActivityController<DiPlayActivity>>()

    @Before fun reset() {
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_phone_wake", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
    }

    @After fun cleanup() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        CarPlayBackgroundSession.clear()
    }

    private fun open(intent: Intent): ActivityController<DiPlayActivity> {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent).create()
        controllers += controller
        // Source-only test builds have no authentication assets.
        ReflectionHelpers.setField(controller.get(), "setupError", null)
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    private fun page(activity: DiPlayActivity) = ReflectionHelpers.getField<String>(activity, "page")

    private fun backupServer(activity: DiPlayActivity) =
        ReflectionHelpers.getField<Any?>(activity, "backupServer")

    private fun external(vararg extras: Pair<String, Any>) = Intent(app, DiPlayActivity::class.java).apply {
        for ((key, value) in extras) when (value) {
            is Boolean -> putExtra(key, value)
            else -> putExtra(key, value.toString())
        }
    }

    @Test fun externalIntentCannotOpenTheBackupPage() {
        val activity = open(external("page" to "backup")).get()
        assertEquals("home", page(activity))
        assertNull(backupServer(activity))
    }

    @Test fun externalNewIntentCannotOpenTheBackupPage() {
        val controller = open(Intent(app, DiPlayActivity::class.java))
        controller.newIntent(external("page" to "backup"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("home", page(controller.get()))
        assertNull(backupServer(controller.get()))
    }

    @Test fun pagesDiPlayOpensItselfStillOpen() {
        for (name in listOf("home", "settings")) {
            assertEquals(name, page(open(external("page" to name)).get()))
        }
        val controller = open(Intent(app, DiPlayActivity::class.java))
        controller.newIntent(external("page" to "settings"))
        assertEquals("settings", page(controller.get()))
    }

    @Test fun launcherStartShowsHome() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app, DiPlayActivity::class.java.name)
        app.getSharedPreferences("diplay", 0).edit().putBoolean("setup_seen", true).commit()
        assertEquals("home", page(open(launcher).get()))
    }

    private fun enableBluetoothWake() {
        DiPlayPreferences.saveConnectOnPhoneBluetooth(app, true)
        DiPlayPreferences.savePhone(app, phone, "iPhone")
        AirPlayPersistence.saveWirelessHotspotMode(app, WirelessHotspotMode.WIFI_P2P)
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
    }

    private fun startedCarPlay(activity: DiPlayActivity): Boolean {
        shadowOf(Looper.getMainLooper()).idle()
        return generateSequence { shadowOf(activity).nextStartedActivity }
            .any { it.component?.className == CarPlayHostActivity::class.java.name }
    }

    @Test fun externalIntentCannotTriggerThePhoneBluetoothConnect() {
        enableBluetoothWake()
        val controller = open(Intent(app, DiPlayActivity::class.java))
        controller.newIntent(external(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT to true))
        assertFalse("an app other than DiPlay started a connection", startedCarPlay(controller.get()))
    }

    @Test fun receiverLaunchStillConnects() {
        enableBluetoothWake()
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(phone)
        PhoneBluetoothReceiver().onReceive(context,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
        val launch = launches.single()
        val controller = open(Intent(app, DiPlayActivity::class.java))
        controller.newIntent(launch)
        assertTrue("the receiver's own launch no longer connects", startedCarPlay(controller.get()))
    }

    private fun receiverLaunch(): Intent {
        val launches = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launches += intent }
        }
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(phone)
        PhoneBluetoothReceiver().onReceive(context,
            Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device))
        return launches.single()
    }

    /** A cold start runs onCreate with the launch intent, the usual case for the real wake. */
    private fun coldStart(intent: Intent): DiPlayActivity {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java, intent)
        controllers += controller
        // Source-only test builds have no authentication assets; a remote target skips loading them.
        AirPlayPersistence.saveMfiTarget(app, MfiTarget.REMOTE)
        controller.create()
        return controller.get()
    }

    @Test fun coldStartFromTheReceiverConnects() {
        enableBluetoothWake()
        val activity = coldStart(receiverLaunch())
        assertTrue("the receiver's cold start no longer connects", startedCarPlay(activity))
    }

    @Test fun coldStartWithAnotherProcessesTokenDoesNotConnect() {
        enableBluetoothWake()
        val stale = receiverLaunch().putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, "00000000-0000-0000-0000-000000000000")
        assertFalse(startedCarPlay(coldStart(stale)))
    }

    @Test fun newIntentWithAnotherProcessesTokenDoesNotConnect() {
        enableBluetoothWake()
        val stale = receiverLaunch().putExtra(PhoneBluetoothReceiver.EXTRA_AUTO_CONNECT, "00000000-0000-0000-0000-000000000000")
        val controller = open(Intent(app, DiPlayActivity::class.java))
        controller.newIntent(stale)
        assertFalse(startedCarPlay(controller.get()))
    }
}
