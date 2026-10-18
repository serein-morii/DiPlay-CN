package com.shilapi.xcertplay

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.graphics.Bitmap
import android.graphics.Canvas
import com.shilapi.xcertplay.host.R
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
@Config(sdk = [29], qualifiers = "en", manifest = Config.NONE)
class BydCallPopupSettingsTest {
    @Before fun installBydSettingsPackage() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }

    @Test
    @Config(sdk = [29, 32])
    fun popupOptInDefaultsOffAndChangesOnlyItsPreferenceWithoutReconnect() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val prefs = activity.getSharedPreferences("xcertplay_airplay", 0)
        prefs.edit().clear().commit()
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.ADVANCED_MEDIA))
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        val popup = switches(content).singleOrNull { it.contentDescription == "Hide BYD call popup (experimental)" }
        assertNotNull("Android 10 and later must expose the opt-in popup control", popup)
        assertFalse(popup!!.isChecked)
        val before = prefs.all.toMap()
        popup.isChecked = true
        assertEquals(before + ("hide_byd_call_popup" to true), prefs.all)
        popup.isChecked = false
        assertEquals(before + ("hide_byd_call_popup" to false), prefs.all)
        assertFalse(PendingReconnect.isPending(null))
    }

    @Test
    @Config(sdk = [28])
    fun popupControlIsHiddenBelowAndroid10() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.ADVANCED_MEDIA))
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        assertTrue(switches(content).none {
            it.contentDescription == activity.getString(R.string.settings_hide_byd_call_popup)
        })
    }

    @Test
    @Config(sdk = [29, 32])
    fun popupControlIsHiddenOnNonBydHeadUnits() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        shadowOf(activity.packageManager).removePackage("com.byd.carsettings")
        assertFalse(CarHotspotSetup.isBydHeadUnit(activity))
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.ADVANCED_MEDIA))
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        assertTrue(switches(content).none {
            it.contentDescription == activity.getString(R.string.settings_hide_byd_call_popup)
        })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "en-w320dp-h800dp-xhdpi")
    fun compactLargeFontPopupRowRendersWithoutClipping() = renderPopupRow("compact", 320)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-ldrtl-w600dp-h800dp-xhdpi")
    fun fullWidthArabicLargeFontPopupRowRendersWithoutClipping() = renderPopupRow("full-ar", 600)

    private fun renderPopupRow(name: String, widthDp: Int) {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 1.6f }
        @Suppress("DEPRECATION")
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        ReflectionHelpers.setField(activity, "settingsSectionFilter", setOf(SettingsSection.ADVANCED_MEDIA))
        val content = LinearLayout(activity)
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "allSettingsSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content))
        val popup = switches(content).single { it.contentDescription == activity.getString(R.string.settings_hide_byd_call_popup) }
        val row = popup.parent as ViewGroup
        row.layoutDirection = configuration.layoutDirection
        val width = (widthDp * activity.resources.displayMetrics.density).toInt()
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        row.layout(0, 0, width, row.measuredHeight)
        assertTrue(popup.right <= width && popup.left >= 0)
        assertTrue(popup.bottom <= row.height && row.height > 0)
        val bitmap = Bitmap.createBitmap(width, row.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.rgb(28, 32, 42))
        row.draw(canvas)
        val directory = File("build/call-popup-screenshots").apply { mkdirs() }
        val output = File(directory, "$name.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun switches(view: View): List<Switch> = when (view) {
        is Switch -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { switches(view.getChildAt(it)) }
        else -> emptyList()
    }
}
