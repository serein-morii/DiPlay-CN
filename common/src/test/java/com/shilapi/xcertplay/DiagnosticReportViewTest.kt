package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class DiagnosticReportViewTest {
    private var activity: DiPlayActivity? = null

    @After fun tearDown() { activity?.finish() }

    // Same shape as the exported report: a header, then 8 logs capped at SessionLogFile.MAX_BYTES each.
    private fun maxSizeReport(): String = buildString {
        appendLine("DiPlay 1.0 · private beta diagnostic report")
        for (name in SessionLogFile.REPORT_NAMES) {
            appendLine("--- $name ---")
            var bytes = 0L
            var index = 0
            while (bytes < SessionLogFile.MAX_BYTES) {
                val line = "12:00:00.000  $name line=${index++} Video: video stats rx=29.8fps shown=29.8fps maxGap=150ms kbps=4000"
                appendLine(line)
                bytes += line.length + 1
            }
        }
    }

    private fun showReport(report: String): Pair<String, Long> {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get().also { activity = it }
        val started = System.nanoTime()
        DiPlayActivity::class.java.getDeclaredMethod("showDiagnosticReport", String::class.java)
            .apply { isAccessible = true }.invoke(screen, report)
        val millis = (System.nanoTime() - started) / 1_000_000
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val views = generateSequence(listOf(dialog.window!!.decorView)) { level ->
            level.flatMap { v -> if (v is ViewGroup) (0 until v.childCount).map { v.getChildAt(it) } else emptyList() }
                .takeIf { it.isNotEmpty() }
        }.flatten()
        val reportView = views.filterIsInstance<TextView>().single { it.isTextSelectable }
        return reportView.text.toString() to millis
    }

    @Test fun viewShowsOnlyTheNewestPartOfALargeReport() {
        val report = maxSizeReport()
        val (shown, millis) = showReport(report)
        println("REPORT_VIEW reportChars=${report.length} viewChars=${shown.length} showMs=$millis")
        assertTrue("view holds ${shown.length} of ${report.length} chars", shown.length <= 250_000)
        assertTrue(shown.endsWith(report.takeLast(1_000)))
    }

    @Test fun smallReportIsShownInFull() {
        val report = "DiPlay 1.0\n--- diplay.log ---\n12:00:00.000  CarPlay connected\n"
        assertEquals(report, showReport(report).first)
    }

    private val max = 200_000

    private fun tail(report: String): String {
        val screen = activity ?: Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get().also { activity = it }
        return DiPlayActivity::class.java.getDeclaredMethod("reportTail", String::class.java)
            .apply { isAccessible = true }.invoke(screen, report) as String
    }

    @Test fun reportOfExactlyTheLimitIsUnchangedAndOneMoreIsCut() {
        val exact = "a".repeat(max)
        assertEquals(exact, tail(exact))
        assertEquals("…\n" + "a".repeat(max), tail("a".repeat(max + 1)))
    }

    @Test fun newlineJustBeforeTheCutStartsTheTailThere() {
        val report = "x".repeat(max - 1) + "\n" + "y".repeat(max)
        assertEquals("…\n" + "y".repeat(max), tail(report))
    }

    @Test fun newlineAtTheCutStartsTheTailAfterIt() {
        val report = "x".repeat(max) + "\n" + "y".repeat(max - 1)
        assertEquals("…\n" + "y".repeat(max - 1), tail(report))
    }

    @Test fun tailWithoutAnyNewlineIsCutAtTheLimit() {
        val report = "x".repeat(50_000) + "y".repeat(max + 100)
        assertEquals("…\n" + "y".repeat(max), tail(report))
    }

    @Test fun onlyTrailingNewlineStillShowsTheEndOfTheReport() {
        val report = "a".repeat(399_999) + "\n"
        val shown = tail(report)
        assertEquals("…\n" + report.takeLast(max), shown)
    }

    @Test fun crlfReportStartsOnALineBoundary() {
        val report = "12:00:00.000  line\r\n".repeat(30_000)
        val body = tail(report).removePrefix("…\n")
        assertTrue(report.endsWith(body))
        assertTrue(body.startsWith("12:00:00.000"))
        assertTrue(body.length <= max)
        assertEquals('\n', report[report.length - body.length - 1])
    }

    @Test fun surrogatePairAcrossTheCutIsNotSplit() {
        val report = "x".repeat(max - 1) + "\uD83D\uDE00" + "y".repeat(max - 1)
        assertEquals("…\n" + "y".repeat(max - 1), tail(report))
    }

    @Test fun largeReportViewStartsAtALineBoundaryAndKeepsTheEnd() {
        val report = maxSizeReport()
        val shown = showReport(report).first
        assertEquals(tail(report), shown)
        assertTrue(shown.contains("\n…\n"))
        val body = shown.substringAfter("\n…\n")
        assertEquals('\n', report[report.length - body.length - 1])
        assertTrue(shown.endsWith(report.takeLast(1_000)))
    }

    private fun headLines(count: Int) = (1 until count + 1).joinToString("") { "header line $it\n" }

    @Test fun theHeaderAboveTheFirstLogIsKeptWhenTheMiddleIsCut() {
        val header = headLines(100)
        val firstLogLine = "12:00:00.000  first log line\n"
        val report = header + "--- previous-7.log ---\n" + firstLogLine + "12:00:01.000  filler\n".repeat(30_000)
        val shown = tail(report)
        assertTrue(shown.startsWith(header + "…\n"))
        assertTrue(!shown.contains(firstLogLine))
        assertTrue(shown.endsWith(report.takeLast(1_000)))
        assertTrue(shown.length <= max + 20_000 + 2)
    }

    @Test fun aHeaderOverTheCapIsCutAtALineBoundary() {
        val report = headLines(5_000) + "--- diplay.log ---\n" + "12:00:01.000  filler\n".repeat(30_000)
        val shown = tail(report)
        val head = shown.substringBefore("…\n")
        assertTrue("head is ${head.length} chars", head.length in 19_000..20_000)
        assertTrue(head.endsWith("\n"))
        assertTrue(report.startsWith(head))
    }

    @Test fun aLargeReportWithoutAMarkerKeepsOnlyTheTail() {
        val report = headLines(100) + "12:00:01.000  filler\n".repeat(30_000)
        assertTrue(tail(report).startsWith("…\n"))
    }

    @Test fun aHeaderThatTheTailAlreadyReachesIsNotRepeated() {
        // About 205,000 chars: the tail starts near 5,000, inside the 16,000-char header.
        val report = headLines(1_000) + "--- diplay.log ---\n" + "12:00:01.000  filler\n".repeat(9_000)
        assertTrue(report.length > max)
        assertEquals(report, tail(report))
    }

    @Test fun aSurrogatePairAtTheHeaderCapIsNotSplit() {
        val report = "x".repeat(19_999) + "\uD83D\uDE00" + "\n--- diplay.log ---\n" + "12:00:01.000  filler\n".repeat(30_000)
        val head = tail(report).substringBefore("…\n")
        assertTrue("head ends in a lone high surrogate", head.isEmpty() || !Character.isHighSurrogate(head.last()))
    }
}
