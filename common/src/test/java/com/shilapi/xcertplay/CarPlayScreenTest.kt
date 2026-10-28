package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.hardware.usb.UsbManager
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import com.shilapi.xcertplay.host.R
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import java.lang.ref.WeakReference

/**
 * "Keep CarPlay in the launcher card" (#463, #406, #1003): launchers that show DiPlay's task in a card or a
 * picture-in-picture window need CarPlay in that task, as up to 0.2.11, not in its own `.carplay` task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayScreenTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var settings: ActivityController<DiPlayActivity>? = null
    private val screens = mutableListOf<ActivityController<out CarPlayHostActivity>>()
    private var bootstrapReady = false

    @Before fun setUp() {
        // The CarPlay screen must start without the private MFi identity used by real cars.
        bootstrapReady = ReflectionHelpers.getField(DiPlayBootstrap, "ready")
        ReflectionHelpers.setField(DiPlayBootstrap, "ready", true)
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayScreen, "open", null)
        AirPlayPersistence.overlaySettingsListener = null
    }

    @After fun tearDown() {
        settings?.pause()?.stop()?.destroy()
        screens.forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayScreen, "open", null)
        AirPlayPersistence.overlaySettingsListener = null
        ReflectionHelpers.setField(DiPlayBootstrap, "ready", bootstrapReady)
    }

    @Test fun theSettingIsOffByDefaultAndCarPlayKeepsItsOwnTask() {
        assertFalse(AirPlayPersistence.loadCarPlaySameTask(app))
        assertEquals(CarPlayHostActivity::class.java, CarPlayScreen.target(app))
        assertEquals(CarPlayHostActivity::class.java.name, CarPlayScreen.intent(app).component?.className)
    }

    @Test fun onlyTheNewDeclarationJoinsDiPlaysTask() {
        val own = activityInfo(CarPlayHostActivity::class.java)
        val shared = activityInfo(SameTaskCarPlayHostActivity::class.java)

        assertEquals("${app.packageName}.carplay", own.taskAffinity)
        assertEquals(app.packageName, shared.taskAffinity)
        assertEquals(activityInfo(DiPlayActivity::class.java).taskAffinity, shared.taskAffinity)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, own.launchMode)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, shared.launchMode)
        // Only the original takes USB attachments and outside starts; those are forwarded when needed.
        assertTrue(own.exported)
        assertFalse(shared.exported)
        assertEquals(own.configChanges, shared.configChanges)
        assertEquals(own.screenOrientation, shared.screenOrientation)
        assertEquals(own.theme, shared.theme)
        assertEquals(own.softInputMode, shared.softInputMode)
    }

    @Test fun withTheSettingOnDiPlayOpensCarPlayInDiPlaysTask() {
        AirPlayPersistence.saveCarPlaySameTask(app, true)
        assertEquals(SameTaskCarPlayHostActivity::class.java, CarPlayScreen.target(app))

        // The launcher icon while a session runs opens CarPlay, the path a card takes after a restart.
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", { done: () -> Unit -> done() })
        settings = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)).create()
        val started = shadowOf(settings!!.get()).nextStartedActivity

        assertEquals(SameTaskCarPlayHostActivity::class.java.name, started.component?.className)
        assertFalse(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun theOpenScreenRegistersAndItsDeclarationWinsUntilItCloses() {
        AirPlayPersistence.saveCarPlaySameTask(app, true)
        val screen = open(SameTaskCarPlayHostActivity::class.java)

        assertSame(screen.get(), CarPlayScreen.current())
        AirPlayPersistence.saveCarPlaySameTask(app, false)
        assertEquals(SameTaskCarPlayHostActivity::class.java, CarPlayScreen.target(app))

        screen.pause().stop().destroy()

        assertNull(CarPlayScreen.current())
        assertEquals(CarPlayHostActivity::class.java, CarPlayScreen.target(app))
    }

    @Test fun aStartOfTheOtherDeclarationGoesToTheChosenOneWithItsExtras() {
        AirPlayPersistence.saveCarPlaySameTask(app, true)
        val usb = Intent(app, CarPlayHostActivity::class.java)
            .setAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            .putExtra("picture_controls", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val forwarder = Robolectric.buildActivity(CarPlayHostActivity::class.java, usb).create()
        screens += forwarder

        assertTrue(forwarder.get().isFinishing)
        assertNull(CarPlayScreen.current())
        val started = shadowOf(forwarder.get()).nextStartedActivity
        assertEquals(SameTaskCarPlayHostActivity::class.java.name, started.component?.className)
        assertEquals(UsbManager.ACTION_USB_DEVICE_ATTACHED, started.action)
        assertTrue(started.getBooleanExtra("picture_controls", false))
        assertEquals(0, started.flags)
    }

    @Test fun aForwardedScreenLeavesTheOpenScreenAlone() {
        val open = open(CarPlayHostActivity::class.java)
        val registered: () -> Unit = {}
        AirPlayPersistence.overlaySettingsListener = registered

        // The setting no longer matters while a screen is open: the start goes to that screen.
        AirPlayPersistence.saveCarPlaySameTask(app, true)
        val forwarder = Robolectric.buildActivity(SameTaskCarPlayHostActivity::class.java).create()
        assertTrue(forwarder.get().isFinishing)
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(forwarder.get()).nextStartedActivity.component?.className)
        forwarder.destroy()

        assertSame(registered, AirPlayPersistence.overlaySettingsListener)
        assertSame(open.get(), CarPlayScreen.current())
        assertFalse(open.get().isFinishing)
    }

    @Test fun changingTheSettingClosesTheScreenOfTheOtherDeclarationOnly() {
        val separate = open(CarPlayHostActivity::class.java)

        assertFalse(CarPlayScreen.closeIfElsewhere(app))
        assertFalse(separate.get().isFinishing)

        AirPlayPersistence.saveCarPlaySameTask(app, true)
        assertTrue(CarPlayScreen.closeIfElsewhere(app))
        assertTrue(separate.get().isFinishing)
        assertNull(CarPlayScreen.current())
        assertEquals(SameTaskCarPlayHostActivity::class.java, CarPlayScreen.target(app))
    }

    @Test fun theSwitchIsInAdvancedExperimentalDisplayAndClosesTheOpenScreen() {
        val unopened = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference<CarPlayHostActivity>(unopened))
        settings = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = settings!!.get()
        descendants(activity.window.decorView)
            .single { it.contentDescription == activity.getString(R.string.settings_open_category,
                activity.getString(R.string.settings_advanced)) }
            .performClick()
        val switch = descendants(activity.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == activity.getString(R.string.settings_carplay_same_task) }

        assertFalse(switch.isChecked)
        switch.performClick()

        assertTrue(switch.isChecked)
        assertTrue(AirPlayPersistence.loadCarPlaySameTask(app))
        assertTrue(unopened.isFinishing)
        assertTrue(SettingsInformationArchitecture.sectionsByCategory.getValue(SettingsCategory.ADVANCED)
            .contains(SettingsSection.EXPERIMENTAL_DISPLAY))
    }

    @Test fun theSettingIsBackedUpWithTheOtherLayoutSettings() {
        AirPlayPersistence.saveCarPlaySameTask(app, true)
        val document = JSONObject(String(SettingsBackupCodec.export(app, "test"), Charsets.UTF_8))

        assertTrue(document.getJSONObject("settings").getJSONObject("xcertplay_airplay").has("carplay_same_task"))
    }

    @Test fun theDiagnosticReportSaysWhereCarPlayRuns() {
        assertEquals("sameTask=false open=none", CarPlayScreen.report(app))
        AirPlayPersistence.saveCarPlaySameTask(app, true)
        val screen = open(SameTaskCarPlayHostActivity::class.java)

        val report = CarPlayScreen.report(app)

        assertTrue(report, report.startsWith("sameTask=true open=SameTaskCarPlayHostActivity task=${screen.get().taskId} "))
        assertTrue(report, report.contains(" multiWindow=false"))
    }

    private fun <T : CarPlayHostActivity> open(type: Class<T>): ActivityController<T> =
        Robolectric.buildActivity(type).setup().also { screens += it }

    private fun activityInfo(type: Class<*>): ActivityInfo =
        app.packageManager.getActivityInfo(ComponentName(app, type), 0)

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
