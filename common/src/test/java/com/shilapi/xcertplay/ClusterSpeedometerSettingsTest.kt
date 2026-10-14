package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterSpeedometerSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun defaultsPersistAndAttachedViewUpdatesWithoutReconnection() {
        assertFalse(AirPlayPersistence.loadClusterSpeedometerEnabled(context))
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var changed: ((Int?) -> Unit)? = null
        var stopped = 0
        val view = ClusterSpeedometerView(controller.get()) { listener ->
            changed = listener
            { stopped++ }
        }
        controller.get().setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, view.visibility)
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
        AirPlayPersistence.saveClusterSpeedometerX(context, 30)
        AirPlayPersistence.saveClusterSpeedometerY(context, 55)
        AirPlayPersistence.saveClusterSpeedometerSize(context, 65)
        AirPlayPersistence.saveClusterSpeedometerOpacity(context, 40)
        shadowOf(Looper.getMainLooper()).idle()
        changed!!(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(0, ReflectionHelpers.getField<Int>(view, "speedKmh"))
        assertEquals(30, ReflectionHelpers.getField<Int>(view, "xPercent"))
        assertEquals(55, ReflectionHelpers.getField<Int>(view, "yPercent"))
        assertEquals(65, ReflectionHelpers.getField<Int>(view, "sizePercent"))
        assertEquals(40, ReflectionHelpers.getField<Int>(view, "opacityPercent"))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        assertEquals(View.GONE, view.visibility)
        changed!!(43)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(43, ReflectionHelpers.getField<Int>(view, "speedKmh"))
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, view.visibility)
        assertEquals(1, stopped)
        val oldCallback = changed!!
        val replacement = ClusterSpeedometerView(controller.get()) { listener ->
            changed = listener
            { stopped++ }
        }
        controller.get().setContentView(replacement)
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        oldCallback(99)
        changed!!(18)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, replacement.visibility)
        assertEquals(65, ReflectionHelpers.getField<Int>(replacement, "sizePercent"))
        assertEquals(View.GONE, view.visibility)
        controller.pause().stop().destroy()
    }

    @Test fun speedometerControlsOnlyAppearWithClusterMapEnabled() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "settingsCategory", SettingsCategory.NAVIGATION)
        fun hasToggle(): Boolean {
            fun texts(view: View): List<String> = if (view is android.view.ViewGroup)
                (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                else if (view is android.widget.TextView) listOf(view.text.toString()) else emptyList()
            return texts(activity.window.decorView).contains(activity.getString(com.shilapi.xcertplay.host.R.string.settings_cluster_speedometer))
        }
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        assertFalse(hasToggle())
        AirPlayPersistence.saveClusterMapEnabled(context, true)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        assertTrue(hasToggle())
        AirPlayPersistence.saveClusterMapEnabled(context, false)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "render")
        assertFalse(hasToggle())
        controller.pause().stop().destroy()
    }

    @Test fun speedCardRendersCenteredAtDifferentSizes() {
        for (size in listOf(50, 75)) {
            val view = ClusterSpeedometerView(context) { { } }
            view.setLayout(82, 30, size)
            view.setSpeedKmh(72)
            view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1920, 720)
            val bitmap = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/cluster-speedometer-ui").apply { mkdirs() }
            File(directory, "card-$size.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

    @Test fun controlsRenderAtCompactAndFullWidthsWithLargeFontsAndRtl() {
        for ((locale, width) in listOf("es" to 600, "es" to 1280, "ar" to 600)) {
            RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h960dp")
            val old = Configuration(context.resources.configuration)
            context.resources.updateConfiguration(Configuration(old).apply { fontScale = 1.5f }, context.resources.displayMetrics)
            AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
            val activity = controller.get()
            activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(20, 27, 38))
                layoutDirection = if (locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "clusterSpeedometerControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, card))
            val px = (width * context.resources.displayMetrics.density).toInt()
            card.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
            assertEquals(5, card.childCount)
            for (i in 0 until card.childCount) assertTrue(card.getChildAt(i).height > 0)
            val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
            card.draw(Canvas(bitmap))
            val directory = File("build/reports/cluster-speedometer-ui").apply { mkdirs() }
            File(directory, "$locale-$width.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
            controller.pause().stop().destroy()
            context.resources.updateConfiguration(old, context.resources.displayMetrics)
        }
        RuntimeEnvironment.setQualifiers("en")
    }
}
