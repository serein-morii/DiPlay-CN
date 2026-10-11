package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SystemBarSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        controller?.pause()?.stop()?.destroy()
    }

    @Test fun settingsExposeTwoIndependentSwitchesWithTheExistingDefaults() {
        openSettings()

        assertTrue(statusBarSwitch().isChecked)
        assertTrue(navigationBarSwitch().isChecked)
        assertFalse(descendants(activity.window.decorView).filterIsInstance<Switch>()
            .any { it.contentDescription == activity.getString(R.string.full_screen) })
    }

    @Test fun settingsReadAllFourExistingCombinationsWithoutChangingThem() {
        openSettings()
        for (hideTop in listOf(false, true)) {
            for (hideBottom in listOf(false, true)) {
                AirPlayPersistence.saveHideTopBar(context, hideTop)
                AirPlayPersistence.saveHideBottomBar(context, hideBottom)

                requireNotNull(controller).recreate()

                assertEquals(hideTop, statusBarSwitch().isChecked)
                assertEquals(hideBottom, navigationBarSwitch().isChecked)
                assertEquals(hideTop, AirPlayPersistence.loadHideTopBar(context))
                assertEquals(hideBottom, AirPlayPersistence.loadHideBottomBar(context))
            }
        }
    }

    @Test fun togglingEitherBarPreservesTheOtherPreferenceAndSwitch() {
        openSettings()

        statusBarSwitch().performClick()
        assertBars(false, true)
        navigationBarSwitch().performClick()
        assertBars(false, false)
        statusBarSwitch().performClick()
        assertBars(true, false)
        navigationBarSwitch().performClick()
        assertBars(true, true)
    }

    @Test fun hidingOnlyTheStatusBarSurvivesRecreation() {
        openSettings()
        navigationBarSwitch().performClick()

        requireNotNull(controller).recreate()

        assertBars(true, false)
    }

    @Test fun returningToSettingsReloadsChangesFromTheOtherEntryPoint() {
        openSettings()
        requireNotNull(controller).pause()
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, true)

        requireNotNull(controller).resume()

        assertBars(false, true)
    }

    @Test fun harmanFullscreenIsOptInAndInAdvancedSettings() {
        AirPlayPersistence.saveHarmanFullscreen(context, false)
        openSettings()
        activity.onBackPressedDispatcher.onBackPressed()
        descendants(activity.window.decorView)
            .single { it.contentDescription == activity.getString(
                R.string.settings_open_category,
                activity.getString(R.string.settings_advanced),
            ) }
            .performClick()
        val harmanSwitch = descendants(activity.window.decorView)
            .filterIsInstance<Switch>()
            .single { it.contentDescription == activity.getString(R.string.settings_harman_fullscreen_experimental) }

        assertFalse(harmanSwitch.isChecked)
        harmanSwitch.performClick()

        assertTrue(harmanSwitch.isChecked)
        assertTrue(AirPlayPersistence.loadHarmanFullscreen(context))
    }

    private fun openSettings() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        descendants(activity.window.decorView)
            .single { it.contentDescription == activity.getString(
                R.string.settings_open_category,
                activity.getString(R.string.settings_display),
            ) }
            .performClick()
    }

    private fun assertBars(hideTop: Boolean, hideBottom: Boolean) {
        assertEquals(hideTop, AirPlayPersistence.loadHideTopBar(context))
        assertEquals(hideBottom, AirPlayPersistence.loadHideBottomBar(context))
        assertEquals(hideTop, statusBarSwitch().isChecked)
        assertEquals(hideBottom, navigationBarSwitch().isChecked)
    }

    private fun statusBarSwitch(): Switch = barSwitch(R.string.hide_the_status_bar)

    private fun navigationBarSwitch(): Switch = barSwitch(R.string.hide_the_navigation_bar)

    private fun barSwitch(label: Int): Switch = descendants(activity.window.decorView)
        .filterIsInstance<Switch>().single { it.contentDescription == activity.getString(label) }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
