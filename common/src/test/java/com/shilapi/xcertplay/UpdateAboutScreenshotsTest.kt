package com.shilapi.xcertplay

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Large-font screenshots for the updater row in compact and full Settings layouts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateAboutScreenshotsTest {
    @Test fun compactAndFullAboutPagesKeepTheUpdateChannelVisible() {
        capture("en-w600dp-h720dp-port-mdpi", 600, 720, full = false, name = "compact-large-font.png")
        capture("en-w1280dp-h800dp-land-mdpi", 1280, 800, full = true, name = "full-large-font.png")
    }

    private fun capture(qualifiers: String, width: Int, height: Int, full: Boolean, name: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val application = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        application.resources.updateConfiguration(
            Configuration(application.resources.configuration).apply { fontScale = 1.5f },
            application.resources.displayMetrics,
        )
        SettingsLayoutPreferences.saveForceFull(application, full)
        val activity = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(application, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get()
        try {
            ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.ABOUT)
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
            val channel = descendants(root).filterIsInstance<TextView>().firstOrNull {
                it.text.toString().startsWith(activity.getString(R.string.update_channel) + " · ")
            }
            assertTrue(channel != null && channel.width > 0 && channel.height > 0)
            val scroll = descendants(root).filterIsInstance<ScrollView>().first {
                descendants(it).any { descendant -> descendant === channel }
            }
            var top = 0
            var current: View = channel!!
            while (current !== scroll) {
                top += current.top
                current = current.parent as View
            }
            scroll.scrollTo(0, (top - scroll.height / 3).coerceAtLeast(0))
            assertTrue(channel.getGlobalVisibleRect(Rect()))
            shadowOf(Looper.getMainLooper()).idle()
            descendants(root).forEach(View::invalidate)

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val decor = activity.window.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, width, height)
                decor.draw(Canvas(bitmap))
                val background = bitmap.getPixel(0, 0)
                assertTrue("Screenshot must contain rendered UI", (0 until width step 8).any { x ->
                    (0 until height step 8).any { y -> bitmap.getPixel(x, y) != background }
                })
                val output = File("build/reports/update-about-ui/$name")
                check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
        } finally {
            activity.finish()
            SettingsLayoutPreferences.saveForceFull(application, false)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
