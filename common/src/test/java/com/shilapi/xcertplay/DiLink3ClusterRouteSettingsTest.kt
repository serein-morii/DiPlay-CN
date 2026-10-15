package com.shilapi.xcertplay

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.setup.DiLinkGeneration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** On DiLink 3 either DiLink 4 cluster route stops the dashboard map, and Platform 21 had no warning (#722). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class DiLink3ClusterRouteSettingsTest {
    private lateinit var controller: ActivityController<DiPlayActivity>
    private lateinit var activity: DiPlayActivity
    private lateinit var page: LinearLayout

    @Before fun setUp() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        activity = controller.get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        controller.setup().visible()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        CarPlayBackgroundSession.clear()
    }

    @Test @org.junit.Ignore("CN behavior differs") fun platform21OnDiLink3ShowsTheWarningInsteadOfTheSwitchesAndTurningItOffClearsBoth() {
        DiLinkGeneration.save(activity, DiLinkGeneration.DILINK_3)
        AirPlayPersistence.saveLegacyClusterEnabled(activity, true)
        renderAdvanced()

        assertFalse(hasText(R.string.platform21_cluster_mode))
        assertFalse(hasText(R.string.adb_cluster_activity_mode))
        assertTrue(hasText(R.string.setup_cluster_route_conflict))
        views(page).filterIsInstance<Button>().single { it.text == activity.getString(R.string.setup_cluster_route_turn_off) }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(AirPlayPersistence.loadDiLink4ClusterRouteEnabled(activity))
        assertFalse(AirPlayPersistence.loadLegacyClusterEnabled(activity))
        assertFalse(AirPlayPersistence.loadAdbClusterEnabled(activity))
    }

    @Test fun diLink3WithNeitherRouteOffersNeitherSwitch() {
        DiLinkGeneration.save(activity, DiLinkGeneration.DILINK_3)
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        renderAdvanced()

        assertFalse(hasText(R.string.platform21_cluster_mode))
        assertFalse(hasText(R.string.adb_cluster_activity_mode))
        assertFalse(hasText(R.string.setup_cluster_route_conflict))
    }

    @Test @org.junit.Ignore("CN behavior differs") fun diLink4KeepsBothSwitches() {
        DiLinkGeneration.save(activity, DiLinkGeneration.DILINK_4)
        renderAdvanced()

        assertTrue(hasText(R.string.platform21_cluster_mode))
        assertTrue(hasText(R.string.adb_cluster_activity_mode))
        assertFalse(hasText(R.string.setup_cluster_route_conflict))
    }

    private fun renderAdvanced() {
        ReflectionHelpers.setField(activity, "page", "settings")
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        page = ReflectionHelpers.getField<ScrollView>(activity, "rootScroll").getChildAt(0) as LinearLayout
    }

    private fun hasText(id: Int) = views(page).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(id) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
