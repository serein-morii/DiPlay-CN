package com.shilapi.xcertplay.turncamera

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TurnCameraCalibrationPanelTest {
    @Before fun clear() {
        RuntimeEnvironment.getApplication().getSharedPreferences("diplay_turn_camera_calibration", 0).edit().clear().commit()
    }
    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }

    private fun panel(locale: String = "en", density: Int = 160, font: Float = 1.5f): TurnCameraCalibrationPanel {
        val base = RuntimeEnvironment.getApplication()
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale)); setLayoutDirection(Locale.forLanguageTag(locale))
            densityDpi = density; fontScale = font
        }
        val context = android.view.ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_Xcertplay)
        return TurnCameraCalibrationPanel(context, emptyMap(), 12, 60,
            label = { TextView(context).apply { text = it; textSize = 16f; setTextColor(android.graphics.Color.WHITE) } },
            button = { title, click -> Button(context).apply {
                text = title; textSize = 18f; isAllCaps = false
                minHeight = (60 * resources.displayMetrics.density).toInt()
                setOnClickListener { click() }
            } })
    }

    @Test fun editingDraftDoesNotPersistAndChangingSideKeepsTwoIndependentDrafts() {
        val p = panel()
        views(p).filterIsInstance<EditText>().single().setText("7")
        assertEquals("7", p.profiles.getValue(TurnCameraSide.LEFT).cameraId)
        assertEquals("", TurnCameraCalibrationStore.load(p.context, TurnCameraSide.LEFT).cameraId)
        val sideLabel = p.context.getString(R.string.settings_turn_camera_side)
        views(p).filterIsInstance<Button>().first { it.text.startsWith(sideLabel) }.performClick()
        ShadowAlertDialog.getLatestAlertDialog().listView.performItemClick(null, 1, 1)
        views(p).filterIsInstance<EditText>().single().setText("8")
        assertEquals("7", p.profiles.getValue(TurnCameraSide.LEFT).cameraId)
        assertEquals("8", p.profiles.getValue(TurnCameraSide.RIGHT).cameraId)
    }

    @Test fun accessibleSliderUpdatesRealPreviewAndInvalidIdsStayUnsaved() {
        val p = panel()
        val zoom = views(p).filterIsInstance<SeekBar>().first { it.contentDescription == p.context.getString(R.string.settings_turn_camera_zoom) }
        zoom.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 150f) })
        assertEquals(2f, p.profiles.getValue(TurnCameraSide.LEFT).zoom)
        assertEquals(2f, views(p).filterIsInstance<TurnCameraCalibrationPreview>().single().profile.zoom)
        views(p).filterIsInstance<EditText>().single().setText("2147483648")
        assertNotNull(views(p).filterIsInstance<EditText>().single().error)
    }

    @Test fun compactFullArabicAndHighDensityLargeFontPanelsFitAndRender() {
        val dir = File(System.getProperty("diplay.screenshot.dir")
            ?: File(requireNotNull(System.getProperty("java.io.tmpdir")), "diplay-camera-calibration-ui").path)
        dir.mkdirs()
        for ((name, widthDp, locale, density) in listOf(
            // Account for the dialog/window padding on a roughly 360dp compact screen.
            Render("compact-en", 280, "en", 160), Render("full-en", 800, "en", 160),
            Render("compact-ar", 280, "ar", 160), Render("dense-zh", 280, "zh-CN", 480))) {
            val p = panel(locale, density)
            val width = widthDp * density / 160
            p.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            p.layout(0, 0, width, p.measuredHeight)
            for (view in views(p)) {
                assertTrue("$name clipped a control horizontally", view.left >= 0 && view.right <= ((view.parent as? View)?.width ?: width))
                if (view is SeekBar) assertEquals(View.LAYOUT_DIRECTION_LTR, view.layoutDirection)
                if (view is TextView && view !is EditText) {
                    val layout = view.layout ?: continue
                    assertTrue("$name clipped text vertically", layout.height <= view.height - view.paddingTop - view.paddingBottom)
                }
            }
            val bitmap = Bitmap.createBitmap(width, p.height, Bitmap.Config.ARGB_8888)
            p.draw(Canvas(bitmap))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private data class Render(val name: String, val width: Int, val locale: String, val density: Int)
}
