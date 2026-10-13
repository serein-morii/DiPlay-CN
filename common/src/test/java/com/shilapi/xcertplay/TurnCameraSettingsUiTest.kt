package com.shilapi.xcertplay

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.turncamera.TurnCameraCapabilities
import com.shilapi.xcertplay.turncamera.TurnCameraSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric.buildActivity
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

/**
 * Phase A settings surface: the Advanced card stays honest (blocked switch + status) and
 * the Diagnostics entry runs the injected probe with late-result and cancellation gating.
 * Every activity is driven through a full controller lifecycle and every render pass is
 * followed by control-bounds checks, so the screenshots are backed by assertions rather
 * than eyeballing (Rework R9). Simulated renders only — never photos of a car (plan §6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TurnCameraSettingsUiTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("diplay", 0).edit()
            .remove("turn_camera_last_probe_at")
            .remove("turn_camera_last_probe_summary")
            .commit()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun openSettingsController() = buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup()

    private fun openCategory(screen: DiPlayActivity, categoryTitle: Int) {
        // Expanded layouts show the category link in both the rail and the grid.
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(categoryTitle)) }
            .performClick()
    }

    /** The live probe button, whatever text it currently shows (idle or running). */
    private fun probeButton(screen: DiPlayActivity): TextView {
        val field = DiPlayActivity::class.java.getDeclaredField("turnCameraProbeButton")
        field.isAccessible = true
        return field.get(screen) as TextView
    }

    /** A probe that blocks inside the shell item until [release] counts down. */
    private class BlockingProbeFactory {
        val release = CountDownLatch(1)
        var created: TurnCameraCapabilities? = null
        val factory: (android.content.Context) -> TurnCameraCapabilities = {
            TurnCameraCapabilities(
                shellRunner = {
                    release.await()
                    TurnCameraCapabilities.ShellOutcome(
                        exitCode = 0,
                        stdout = "Found 1 services:\n1\tcamera: [android.hardware.ICameraService]\n",
                        stderr = "",
                        timedOut = false,
                    )
                },
                systemProperty = { null },
                wallClock = { 1_728_000_000_000L },
                monotonicClock = { 0L },
            ).also { created = it }
        }

        fun awaitStarted() {
            repeat(200) {
                if (created != null) return
                Thread.sleep(10)
            }
        }
    }

    @Test fun advancedCardShowsBlockedSwitchAndUnprobedStatus() {
        val controller = openSettingsController()
        val screen = controller.get()
        openCategory(screen, R.string.settings_advanced)
        val enableSwitch = descendants(screen.window.decorView)
            .filterIsInstance<Switch>()
            .first { it.contentDescription == screen.getString(R.string.settings_turn_camera_enable) }
        assertFalse("phase A must not offer a working switch", enableSwitch.isEnabled)
        assertFalse(enableSwitch.isChecked)
        assertTrue(
            descendants(screen.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().contains(screen.getString(R.string.settings_turn_camera_status_unprobed)) },
        )
        controller.pause().stop().destroy()
    }

    @Test fun storedProbeSummaryShowsInTheAdvancedCard() {
        TurnCameraSettings.saveLastProbe(
            context,
            TurnCameraSettings.LastProbe(1_720_000_000_000L, "hints=6 missing=2 error=0 skipped=0"),
        )
        val controller = openSettingsController()
        val screen = controller.get()
        openCategory(screen, R.string.settings_advanced)
        val status = descendants(screen.window.decorView).filterIsInstance<TextView>()
            .first { it.text.toString().contains(screen.getString(R.string.settings_turn_camera_status_hints)) }
        assertTrue(status.text.toString().contains("hints=6"))
        controller.pause().stop().destroy()
    }

    @Test fun diagnosticsProbeButtonRunsProbeShowsReportAndStoresSummary() {
        val controller = openSettingsController()
        val screen = controller.get()
        val field = DiPlayActivity::class.java.getDeclaredField("turnCameraProbeFactory")
        field.isAccessible = true
        field.set(screen, BlockingProbeFactory().apply { release.countDown() }.factory)
        openCategory(screen, R.string.diagnostics)
        probeButton(screen).performClick()
        val latest = run {
            var dialog: android.app.AlertDialog? = null
            repeat(100) {
                shadowOf(Looper.getMainLooper()).idle()
                dialog = ShadowAlertDialog.getLatestAlertDialog()
                if (dialog != null) return@run dialog
                Thread.sleep(20)
            }
            dialog
        }
        assertNotNull("probe report dialog must appear", latest)
        assertEquals(screen.getString(R.string.settings_turn_camera_probe_title), shadowOf(latest!!).title)
        val stored = TurnCameraSettings.lastProbe(context)
        assertNotNull(stored)
        assertTrue(stored!!.summary.startsWith("hints="))
        assertTrue(
            descendants(latest.window!!.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().contains("turn_signal_source: UNCONFIRMED") },
        )
        latest.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun destroyCancelsTheProbeAndDiscardsItsLateResult() {
        val blocking = BlockingProbeFactory()
        val controller = openSettingsController()
        val screen = controller.get()
        val field = DiPlayActivity::class.java.getDeclaredField("turnCameraProbeFactory")
        field.isAccessible = true
        field.set(screen, blocking.factory)
        openCategory(screen, R.string.diagnostics)
        probeButton(screen).performClick()
        blocking.awaitStarted()
        controller.pause().stop().destroy()
        // onDestroy cancels the in-flight probe; the run token also invalidates its result.
        assertTrue("onDestroy must cancel the probe", blocking.created!!.isCancelled())
        blocking.release.countDown()
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("a late result must not be stored", TurnCameraSettings.lastProbe(context))
    }

    @Test fun cancelButtonCancelsARunningProbeWithoutSavingAnything() {
        val blocking = BlockingProbeFactory()
        val controller = openSettingsController()
        val screen = controller.get()
        val field = DiPlayActivity::class.java.getDeclaredField("turnCameraProbeFactory")
        field.isAccessible = true
        field.set(screen, blocking.factory)
        openCategory(screen, R.string.diagnostics)
        val runButton = probeButton(screen)
        assertEquals(screen.getString(R.string.settings_turn_camera_probe), runButton.text)
        runButton.performClick()
        blocking.awaitStarted()
        shadowOf(Looper.getMainLooper()).idle()
        // While running, the same button becomes an enabled cancel control (Rework S4).
        assertEquals(screen.getString(R.string.settings_turn_camera_probe_cancel), probeButton(screen).text)
        assertTrue(probeButton(screen).isEnabled)
        probeButton(screen).performClick()
        assertTrue("the second click must cancel the probe", blocking.created!!.isCancelled())
        blocking.release.countDown()
        run waiting@{
            repeat(150) {
                shadowOf(Looper.getMainLooper()).idle()
                if (probeButton(screen).text == screen.getString(R.string.settings_turn_camera_probe)) return@waiting
                Thread.sleep(20)
            }
        }
        assertEquals("button returns to idle after cancellation", screen.getString(R.string.settings_turn_camera_probe), probeButton(screen).text)
        assertTrue(probeButton(screen).isEnabled)
        assertNull("a cancelled run must not save a summary", TurnCameraSettings.lastProbe(context))
        assertNull("a cancelled run must not open a report dialog", ShadowAlertDialog.getLatestAlertDialog())
        controller.pause().stop().destroy()
    }

    @Test fun advancedAndDiagnosticsRenderAcrossSizesDensitiesAndRtlWithBoundsChecks() {
        data class Case(val qualifier: String, val widthDp: Int, val expanded: Boolean, val suffix: String)

        // Capture the environment BEFORE switching, so the finally restores exactly it (S6).
        val previousQualifiers = RuntimeEnvironment.getQualifiers()
        val incomingConfiguration = Configuration(context.resources.configuration)
        val incomingFlags = context.applicationInfo.flags
        try {
            for (case in listOf(
                Case("es-w600dp-h960dp", 600, false, "es-600"),
                Case("es-w1280dp-h960dp", 1280, true, "es-1280"),
                Case("ar-w600dp-h960dp", 600, false, "ar-600"),
                Case("es-w600dp-h960dp-xhdpi", 600, false, "es-600-xhdpi"),
            )) {
                RuntimeEnvironment.setQualifiers(case.qualifier)
                // Font scale rides on the post-switch configuration so the qualifier's
                // screen size survives; the incoming snapshot is only for the final restore.
                val caseConfiguration = Configuration(context.resources.configuration)
                context.resources.updateConfiguration(
                    Configuration(caseConfiguration).apply { fontScale = 1.5f },
                    context.resources.displayMetrics,
                )
                // The layout policy must agree with what we actually render.
                assertEquals(
                    "rail layout for ${case.suffix}",
                    case.expanded,
                    SettingsLayoutPolicy.isExpanded(case.widthDp, 960, 1.5f),
                )
                // Advanced: card close-up with bounds checks on every new control.
                run {
                    val controller = openSettingsController()
                    try {
                        val screen = controller.get()
                        screen.applicationInfo.flags = screen.applicationInfo.flags or
                            android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
                        openCategory(screen, R.string.settings_advanced)
                        layoutRoot(screen)
                        assertRenderedRailMatches(screen, case.expanded, case.suffix)
                        val enableSwitch = descendants(screen.window.decorView).filterIsInstance<Switch>()
                            .first { it.contentDescription == screen.getString(R.string.settings_turn_camera_enable) }
                        var card: View = enableSwitch
                        while (card.parent is ViewGroup && card.parent.parent is ViewGroup &&
                            card.parent.parent !is ScrollView
                        ) card = card.parent as View
                        assertControlsFitWithin(card as ViewGroup, case.suffix)
                        write(card, "turn-camera-card-${case.suffix}")
                        write(screen.window.decorView, "turn-camera-advanced-${case.suffix}")
                    } finally {
                        controller.pause().stop().destroy()
                    }
                }
                // Diagnostics: scroll the probe button fully into view before capturing.
                run {
                    val controller = openSettingsController()
                    try {
                        val screen = controller.get()
                        screen.applicationInfo.flags = screen.applicationInfo.flags or
                            android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
                        openCategory(screen, R.string.diagnostics)
                        layoutRoot(screen)
                        assertRenderedRailMatches(screen, case.expanded, case.suffix)
                        val button = probeButton(screen)
                        var scroller: View? = button
                        while (scroller != null && scroller !is ScrollView) {
                            scroller = scroller.parent as? View
                        }
                        val scrollView = scroller as? ScrollView
                        assertNotNull("diagnostics page must be scrollable", scrollView)
                        // Same-domain coordinates: the button's rect (with real size!) inside
                        // this ScrollView, plus a margin so "visible" is not flush with the edge.
                        val content = android.graphics.Rect()
                        button.getDrawingRect(content)
                        scrollView!!.offsetDescendantRectToMyCoords(button, content)
                        val marginPx = Math.round(8 * screen.resources.displayMetrics.density)
                        val maxScroll = (scrollView.getChildAt(0).height - scrollView.height).coerceAtLeast(0)
                        val target = (content.bottom + marginPx - scrollView.height + scrollView.paddingBottom)
                            .coerceIn(0, maxScroll)
                        // The ScrollView's window rect must be computed BEFORE scrolling:
                        // offsetDescendantRectToMyCoords embeds the ancestor chain's scroll
                        // state, so computing it after scrollTo would shift the paste.
                        val root = rootAt(screen)
                        val scrollRect = android.graphics.Rect(0, 0, scrollView.width, scrollView.height)
                        root.offsetDescendantRectToMyCoords(scrollView, scrollRect)
                        val contentColumn = scrollView.getChildAt(0)
                        scrollView.scrollTo(0, target)
                        shadowOf(Looper.getMainLooper()).idle()
                        assertEquals("the scroll must actually be applied for ${case.suffix}", target, scrollView.scrollY)
                        // Arithmetic on laid-out views (not shadow rect APIs): after scrolling,
                        // the whole button must sit inside the viewport with margin to spare.
                        val visibleTop = content.top - scrollView.scrollY
                        val visibleBottom = content.bottom - scrollView.scrollY
                        val viewportBottom = scrollView.height - scrollView.paddingBottom
                        assertTrue(
                            "probe button top must be scrolled into view for ${case.suffix} " +
                                "(top=$visibleTop bottom=$visibleBottom viewport=$viewportBottom)",
                            visibleTop >= 0 && visibleBottom <= viewportBottom - marginPx + 1,
                        )
                        assertControlsFitWithin(button.parent as ViewGroup, case.suffix)
                        // Robolectric's decorView.draw() does not paint ScrollView offsets
                        // (verified by pixel experiment), and re-measuring the root to full
                        // height changes the layout. True viewport composition instead
                        // (Rework T3): keep the original screen layout, draw the chrome
                        // layer from the decor, draw the ScrollView's content column
                        // standalone (parent clipping never applies to a direct child
                        // draw), then paste it back into the ScrollView's window area at
                        // the applied scrollY — fixed chrome stays put, only the content
                        // moves, exactly like a real scrolled window.
                        val m = screen.resources.displayMetrics
                        val viewport = Bitmap.createBitmap(m.widthPixels, m.heightPixels, Bitmap.Config.ARGB_8888)
                        val contentFull = Bitmap.createBitmap(contentColumn.width, contentColumn.height, Bitmap.Config.ARGB_8888)
                        try {
                            // Paint the page background first: a compact root can leave
                            // transparent strips that would render as black bands, and the
                            // base layer's UNSCROLLED copy of the ScrollView area must be
                            // erased before the scrolled content is pasted — otherwise the
                            // header that belongs to the scrolled content in compact
                            // layouts is drawn twice, offset by the scroll (Rework U2).
                            val bgColour = (scrollView.background as? android.graphics.drawable.ColorDrawable)
                                ?.color ?: android.graphics.Color.rgb(12, 17, 27)
                            viewport.eraseColor(bgColour)
                            root.draw(Canvas(viewport))
                            contentColumn.draw(Canvas(contentFull))
                            // Extra artifact: the full content column alone (no chrome),
                            // explicitly labelled as content, not as a viewport.
                            val directory = File("build/reports/turn-camera-settings").apply { mkdirs() }
                            File(directory, "turn-camera-diagnostics-content-${case.suffix}.png").outputStream().use {
                                assertTrue(contentFull.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                            val visibleTop = scrollView.scrollY
                            val srcBottom = (visibleTop + scrollView.height).coerceAtMost(contentColumn.height)
                            val canvas = Canvas(viewport)
                            canvas.drawRect(
                                android.graphics.RectF(scrollRect),
                                android.graphics.Paint().apply { color = bgColour },
                            )
                            canvas.drawBitmap(
                                contentFull,
                                android.graphics.Rect(0, visibleTop, contentColumn.width, srcBottom),
                                android.graphics.RectF(
                                    scrollRect.left.toFloat(), scrollRect.top.toFloat(),
                                    (scrollRect.left + contentColumn.width).toFloat(),
                                    (scrollRect.top + (srcBottom - visibleTop)).toFloat(),
                                ),
                                null,
                            )
                            File(directory, "turn-camera-diagnostics-${case.suffix}.png").outputStream().use {
                                assertTrue(viewport.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                            // Pixel self-check on the actual artifact (Rework T3): with the
                            // button's viewport position known from the composite geometry,
                            // require bright text inside the button, no bright text below
                            // it, and its bottom edge clear of the image edge.
                            val buttonTop = scrollRect.top + content.top - scrollView.scrollY
                            val buttonBottom = scrollRect.top + content.bottom - scrollView.scrollY
                            assertTrue(
                                "probe button must end above the image edge for ${case.suffix} " +
                                    "(bottom=$buttonBottom height=${viewport.height})",
                                buttonBottom in 1 until viewport.height,
                            )
                            assertTrue(
                                "probe button must contain its label text for ${case.suffix}",
                                regionHasBrightText(viewport, content.left, content.right, buttonTop, buttonBottom),
                            )
                            // The label must end strictly inside the pill: no bright text
                            // touching the button's bottom edge or crossing it (the next
                            // content block may legitimately appear further below).
                            assertTrue(
                                "button text must not touch or cross the bottom edge for ${case.suffix}",
                                !regionHasBrightText(
                                    viewport, content.left, content.right,
                                    buttonBottom - 3,
                                    (buttonBottom + marginPx).coerceAtMost(viewport.height - 1),
                                ),
                            )
                            assertTrue(
                                "viewport must keep the fixed header for ${case.suffix}",
                                topRowsHaveContent(viewport),
                            )
                        } finally {
                            viewport.recycle()
                            contentFull.recycle()
                        }
                    } finally {
                        controller.pause().stop().destroy()
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(previousQualifiers)
            context.resources.updateConfiguration(incomingConfiguration, context.resources.displayMetrics)
            context.applicationInfo.flags = incomingFlags
        }
    }

    /** The rendered UI must actually have (or lack) the settings rail, not just the policy say so (S6). */
    private fun assertRenderedRailMatches(screen: DiPlayActivity, expanded: Boolean, label: String) {
        val railField = DiPlayActivity::class.java.getDeclaredField("settingsRailScroll")
        railField.isAccessible = true
        val rail = railField.get(screen) as? ScrollView
        if (expanded) {
            assertNotNull("$label: expanded layout must render the category rail", rail)
            assertTrue("$label: rail must be attached", rail!!.parent != null)
        } else {
            assertTrue(
                "$label: compact layout must not render an attached rail",
                rail?.parent == null,
            )
        }
    }

    /** Rework R8: the new controls must keep using the shared named gap/padding constants. */
    @Test fun newControlsUseNamedGapConstantsNotLiterals() {
        val source = File("src/main/java/com/shilapi/xcertplay/DiPlayActivity.kt").readText()
        assertTrue(
            "the probe button row must use the shared button gap constant",
            source.contains("matchButton(SETTINGS_BUTTON_GAP_DP, 60)"),
        )
        assertTrue(
            "both report dialogs must share the named dialog padding constants",
            2 == Regex("setPadding\\(dp\\(SETTINGS_DIALOG_PADDING_DP\\), dp\\(SETTINGS_DIALOG_GAP_DP\\)").findAll(source).count(),
        )
        assertTrue(
            "the turn camera status note must use the named note gap constant",
            source.contains("setPadding(0, dp(NOTE_LINE_GAP_DP), 0, 0)"),
        )
    }

    /** Every control inside [root] must stay within its parent's bounds (1px tolerance). */
    private fun assertControlsFitWithin(root: ViewGroup, label: String) {
        fun walk(view: View, parent: ViewGroup) {
            assertTrue(
                "$label: ${view.javaClass.simpleName} overflows its parent horizontally " +
                    "(left=${view.left} right=${view.right} parent=${parent.width})",
                view.left >= -1 && view.right <= parent.width + 1,
            )
            assertTrue(
                "$label: ${view.javaClass.simpleName} overflows its parent vertically " +
                    "(top=${view.top} bottom=${view.bottom} parent=${parent.height})",
                view.top >= -1 && view.bottom <= parent.height + 1,
            )
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), view)
        }
        for (i in 0 until root.childCount) walk(root.getChildAt(i), root)
    }

    private fun rootAt(screen: DiPlayActivity): ViewGroup {
        val parent = screen.findViewById<ViewGroup>(android.R.id.content)
        return parent.getChildAt(0) as ViewGroup
    }

    /** True when any row in the region holds bright text pixels (label colour ≈241,245,252). */
    private fun regionHasBrightText(bitmap: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        val left = x0.coerceIn(0, bitmap.width - 1)
        val right = x1.coerceIn(left + 1, bitmap.width)
        val top = y0.coerceIn(0, bitmap.height - 1)
        val bottom = y1.coerceIn(top, bitmap.height - 1)
        for (y in top..bottom) {
            for (x in left until right) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 200 && g > 200 && b > 200) return true
            }
        }
        return false
    }

    private fun topRowsHaveContent(bitmap: Bitmap): Boolean {
        // Expanded layouts start with up to 24dp of shell padding before the header.
        var contentRows = 0
        for (y in 0 until 40) {
            var min = 255
            var max = 0
            var x = 0
            while (x < bitmap.width) {
                val c = bitmap.getPixel(x, y)
                val l = ((c shr 16) and 0xFF)
                if (l < min) min = l
                if (l > max) max = l
                x += 8
            }
            if (max - min > 10) contentRows++
        }
        return contentRows >= 4
    }

    private fun layoutRoot(screen: DiPlayActivity) {
        val root = rootAt(screen)
        val metrics = screen.resources.displayMetrics
        root.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(root.width > 0 && root.height > 0)
    }

    private fun write(view: View, name: String) {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/turn-camera-settings").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

}
