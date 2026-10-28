package com.shilapi.xcertplay

import android.app.AlertDialog
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en", manifest = Config.NONE)
class FrameRateSettingTest {
    private val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()

    @Test fun aRateFromTheCarPlayMenuIsShownAndThirtyCanBeChosenFromIt() {
        AirPlayPersistence.saveFps(activity, 45)
        val row = row()
        assertEquals("${activity.getString(R.string.frame_rate)} · 45 fps", row.text.toString())
        assertEquals(listOf(activity.getString(R.string.s_30_fps_lighter_load), "45 fps",
            activity.getString(R.string.s_60_fps_smoother_motion)), choose(row, 0))
        assertEquals(30, AirPlayPersistence.loadFps(activity))
        assertEquals("${activity.getString(R.string.frame_rate)} · ${activity.getString(R.string.s_30_fps_lighter_load)}",
            row.text.toString())
    }

    @Test fun thirtyAndSixtyStayTheOnlyChoicesOtherwise() {
        AirPlayPersistence.saveFps(activity, 60)
        val row = row()
        assertEquals("${activity.getString(R.string.frame_rate)} · ${activity.getString(R.string.s_60_fps_smoother_motion)}",
            row.text.toString())
        assertEquals(listOf(activity.getString(R.string.s_30_fps_lighter_load),
            activity.getString(R.string.s_60_fps_smoother_motion)), choose(row, 0))
        assertEquals(30, AirPlayPersistence.loadFps(activity))
    }

    private fun row(): Button {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("frameRateControl", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        return parent.getChildAt(0) as Button
    }

    /** Opens the row's dialog, picks [index] and saves; returns the listed choices. */
    private fun choose(row: Button, index: Int): List<String> {
        row.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val list = dialog.listView
        val labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        shadowOf(dialog).clickOnItem(index)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return labels
    }
}
