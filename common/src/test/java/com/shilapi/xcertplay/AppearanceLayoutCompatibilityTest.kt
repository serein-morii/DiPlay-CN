package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], qualifiers = "en-w700dp-h400dp-land")
class AppearanceLayoutCompatibilityTest {
    @Test fun appearanceChangesKeepNavigationAndVisibleSystemBarsOnOlderAndroid() {
        val context = RuntimeEnvironment.getApplication() as Context
        AirPlayPersistence.saveHideTopBar(context, false)
        AirPlayPersistence.saveHideBottomBar(context, false)
        SettingsLayoutPreferences.saveForceFull(context, true)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = controller.get()
        try {
            for (appearance in AppAppearance.entries) {
                AirPlayPersistence.saveAppAppearance(activity, appearance)
                ReflectionHelpers.callInstanceMethod<Unit>(activity, "checkForAppearanceChange")
                shadowOf(Looper.getMainLooper()).idle()
                for (category in SettingsCategory.entries) {
                    ReflectionHelpers.setField(activity, "page", "settings")
                    ReflectionHelpers.callInstanceMethod<Unit>(activity, "openSettingsCategory",
                        ReflectionHelpers.ClassParameter(SettingsCategory::class.java, category))
                    assertEquals("settings", ReflectionHelpers.getField<String>(activity, "page"))
                    assertEquals(category, ReflectionHelpers.getField<SettingsCategory>(activity, "settingsCategory"))
                }
                assertTrue(SettingsLayoutPreferences.isActive(activity))
                assertFalse(AirPlayPersistence.loadHideTopBar(activity))
                assertFalse(AirPlayPersistence.loadHideBottomBar(activity))
                if (Build.VERSION.SDK_INT < 30) {
                    val hidden = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    assertEquals(0, activity.window.decorView.systemUiVisibility and hidden)
                }
                assertNull(shadowOf(activity).nextStartedActivity)
            }
        } finally {
            controller.pause().stop().destroy()
            AppAppearanceRuntime.resetForTest()
        }
    }
}
