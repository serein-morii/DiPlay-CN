package com.shilapi.xcertplay

import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class PreparationLayoutTest {
    @Test fun shortAndRegularViewportsFitWithoutScrolling() {
        // Attach without onCreate: avoid starting sensors, networking or CarPlay sessions.
        val activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val method = CarPlayHostActivity::class.java.getDeclaredMethod("buildContentView")
        method.isAccessible = true
        val root = method.invoke(activity) as android.widget.FrameLayout
        // The viewport is named; a video-only overlay can be inserted before it.
        val viewport = CarPlayHostActivity::class.java.getDeclaredField("connectionPanel")
            .apply { isAccessible = true }.get(activity) as FrameLayout
        val panel = viewport.getChildAt(0) as LinearLayout
        val density = activity.resources.displayMetrics.density
        fun layout(heightDp: Int) {
            val width = (640 * density).toInt()
            val height = (heightDp * density).toInt()
            repeat(3) {
                // This detached view has no ViewRoot to schedule the next traversal.
                fun invalidateMeasurement(view: View) {
                    view.forceLayout()
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) invalidateMeasurement(view.getChildAt(index))
                    }
                }
                invalidateMeasurement(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            val last = panel.getChildAt(panel.childCount - 1)
            assertTrue("height=$heightDp bottom=${last.bottom} padding=${panel.paddingBottom} panel=${panel.height}", last.bottom + panel.paddingBottom <= panel.height)
            assertEquals(0, viewport.scrollY)
            val top = panel.top + panel.height * (1f - panel.scaleY) / 2f
            assertTrue("top=$top height=$heightDp", top >= -1f)
            assertTrue("bottom height=$heightDp", top + panel.height * panel.scaleY <= viewport.height + 1f)
        }
        for (height in listOf(200, 240, 320, 400, 479, 480, 720)) layout(height)
        assertEquals(34f, (panel.getChildAt(1) as TextView).textSize / activity.resources.displayMetrics.scaledDensity, 0.01f)
    }
}
