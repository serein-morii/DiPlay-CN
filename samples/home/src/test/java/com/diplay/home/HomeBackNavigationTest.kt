package com.diplay.home

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 36])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeBackNavigationTest {
    @Test fun backDismissesAppsBeforeLeavingWidgetEditMode() {
        verifyBackNavigation()
    }

    @Test @Config(qualifiers = "zh-rTW")
    fun traditionalChineseHomeKeepsEditingAndBackNavigationWorking() {
        assertEquals("編輯", RuntimeEnvironment.getApplication().getString(R.string.home_edit))
        verifyBackNavigation()
    }

    private fun verifyBackNavigation() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup().visible()
        val activity = controller.get()
        try {
            text(activity.window.decorView, activity.getString(R.string.home_edit)).performClick()
            text(activity.window.decorView, activity.getString(R.string.home_apps)).performClick()
            val close = text(activity.window.decorView, activity.getString(R.string.home_close))
            assertTrue(close.isShown)
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(close.isShown)
            assertTrue(text(activity.window.decorView, activity.getString(R.string.home_done)).isShown)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(text(activity.window.decorView, activity.getString(R.string.home_edit)).isShown)
            assertFalse(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun backKeepsTheRootHomeScreenOpen() {
        val controller = Robolectric.buildActivity(HomeActivity::class.java).setup().visible()
        try {
            val activity = controller.get()
            activity.onBackPressedDispatcher.onBackPressed()
            assertFalse(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun text(root: View, value: String): TextView =
        descendants(root).filterIsInstance<TextView>().first { it.text.toString() == value }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
    }
}
