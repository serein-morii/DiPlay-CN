package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en-w600dp-h700dp", manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterSettingsConsentTest {
    private lateinit var screen: DiPlayActivity
    private lateinit var activity: ActivityController<DiPlayActivity>
    private lateinit var session: CarPlayController
    private val routeOwner = Any()
    private val events = mutableListOf<String>()

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        // These existing sessions have already answered the optional notification prompt.
        app.getSharedPreferences("diplay", 0).edit().putBoolean("notification_asked", true).commit()
        PendingReconnect.clear()
        CarPlayBackgroundSession.clear()
        ClusterActivityOutput.stopForSettings()
        activity = Robolectric.buildActivity(DiPlayActivity::class.java)
        screen = activity.get()
        screen.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setup().visible()
        ReflectionHelpers.setField(screen, "setupError", null)
        ReflectionHelpers.setField(screen, "page", "settings")
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.NAVIGATION)
        AirPlayPersistence.saveWirelessEnabled(screen, false)
        session = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480,
            routeOwner, CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { done ->
            events += "stop-session"
            ClusterActivityOutput.stop(routeOwner)
            CarPlayBackgroundSession.clear(session)
            done()
        }
        CarPlayBackgroundSession.active = true
        ClusterActivityOutput.bind(routeOwner, 0) { events += "release-cluster-surface" }
        events.clear() // bind publishes the current surface once.
        render()
    }

    @After fun tearDown() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        CarPlayBackgroundSession.clear()
        ClusterActivityOutput.stop(routeOwner)
        PendingReconnect.clear()
        activity.pause().stop().destroy()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
    }

    @Test fun cancellingAdbRoutingKeepsTheSessionAndItsClusterLease() {
        setting(R.string.adb_cluster_activity_mode).performClick()
        assertCurrentSessionUnchanged()
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(screen))
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(screen))

        dialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(setting(R.string.adb_cluster_activity_mode).isChecked)
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(screen))
        assertCurrentSessionUnchanged()
    }

    @Test fun cancellingClusterMapByBackRestoresTheSavedSwitchWithoutReleasingOutput() {
        AirPlayPersistence.saveClusterMapEnabled(screen, true)
        render()
        setting(R.string.carplay_map_on_instrument_cluster_experimental).performClick()
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(screen))
        assertCurrentSessionUnchanged()

        dialog().cancel() // The same onCancel path used by Back and an outside tap.
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(setting(R.string.carplay_map_on_instrument_cluster_experimental).isChecked)
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(screen))
        assertCurrentSessionUnchanged()
    }

    @Test fun applyingAdbRoutingReleasesItsOldLeaseAndReconnectsOnlyAfterConsent() {
        setting(R.string.adb_cluster_activity_mode).performClick()
        assertCurrentSessionUnchanged()
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(screen))
        assertEquals(screen.getString(R.string.apply_and_reconnect),
            dialog().getButton(AlertDialog.BUTTON_POSITIVE).text.toString())

        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(AirPlayPersistence.loadAdbClusterEnabled(screen))
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(screen))
        assertEquals(listOf("release-cluster-surface", "stop-session"), events)
        assertNull(CarPlayBackgroundSession.snapshot())
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(screen).nextStartedActivity?.component?.className)
    }

    @Test fun applyingClusterMapOffReconnectsOnlyAfterConsent() {
        AirPlayPersistence.saveClusterMapEnabled(screen, true)
        render()
        setting(R.string.carplay_map_on_instrument_cluster_experimental).performClick()
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(screen))
        assertCurrentSessionUnchanged()

        dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(AirPlayPersistence.loadClusterMapEnabled(screen))
        assertEquals(listOf("stop-session", "release-cluster-surface"), events)
        assertNull(CarPlayBackgroundSession.snapshot())
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(screen).nextStartedActivity?.component?.className)
    }

    private fun assertCurrentSessionUnchanged() {
        assertTrue(events.isEmpty())
        assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        assertTrue(CarPlayBackgroundSession.active)
        assertFalse(PendingReconnect.isPending(session))
        assertNull(shadowOf(screen).nextStartedActivity)
    }

    private fun render() = ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

    private fun dialog() = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())

    private fun setting(title: Int) = views(screen.window.decorView).filterIsInstance<Switch>()
        .single { it.contentDescription == screen.getString(title) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
