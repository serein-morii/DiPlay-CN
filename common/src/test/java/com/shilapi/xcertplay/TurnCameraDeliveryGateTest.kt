package com.shilapi.xcertplay

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.turncamera.TurnCameraCapabilities
import com.shilapi.xcertplay.turncamera.TurnCameraSettings
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

/**
 * Delivery-time cancellation gate regressions (Rework V1), deterministic under the PAUSED
 * looper: the background run is proven finished (thread joined) and its delivery proven
 * queued (looper not idle) BEFORE the cancel click, so no timing is guessed with sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class TurnCameraDeliveryGateTest {
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

    private fun probeButton(screen: DiPlayActivity): TextView {
        val field = DiPlayActivity::class.java.getDeclaredField("turnCameraProbeButton")
        field.isAccessible = true
        return field.get(screen) as TextView
    }

    private fun openCategory(screen: DiPlayActivity, categoryTitle: Int) {
        // No looper idle here: under PAUSED, clicks and re-renders are synchronous, and
        // idling mid-navigation could run a queued probe delivery prematurely.
        descendants(screen.window.decorView)
            .first { it.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(categoryTitle)) }
            .performClick()
    }

    private fun injectFactory(screen: DiPlayActivity, probe: TurnCameraCapabilities) {
        val field = DiPlayActivity::class.java.getDeclaredField("turnCameraProbeFactory")
        field.isAccessible = true
        field.set(screen, { _: android.content.Context -> probe })
    }

    /**
     * A probe that records its run thread through the monotonic clock hook and can be made
     * to fail after the first clock read, so run() itself throws and delivery is onFailure.
     */
    private fun hookedProbe(runThread: AtomicReference<Thread?>, failAfterStart: Boolean = false) =
        TurnCameraCapabilities(
            shellRunner = {
                TurnCameraCapabilities.ShellOutcome(0, "Found 0 services:\n", "", timedOut = false)
            },
            systemProperty = { null },
            monotonicClock = {
                runThread.compareAndSet(null, Thread.currentThread())
                if (failAfterStart) throw IllegalStateException("injected failure") else System.nanoTime()
            },
        )

    /** Starts a run and waits until its outer thread finished and delivery is queued. */
    private fun startRunAndAwaitQueuedDelivery(screen: DiPlayActivity, runThread: AtomicReference<Thread?>) {
        probeButton(screen).performClick()
        val deadline = System.nanoTime() + 10_000_000_000L
        while (runThread.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        assertNotNull(runThread.get())
        runThread.get()!!.join(10_000)
        assertFalse("outer run thread must finish and enqueue delivery", runThread.get()!!.isAlive)
        assertFalse("worker result must have been queued for delivery", shadowOf(Looper.getMainLooper()).isIdle)
    }

    @Test fun cancelAfterRunReturnsBeforeUiDeliveryMustDiscardResult() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val screen = controller.get()
            val runThread = AtomicReference<Thread?>()
            val hooked = hookedProbe(runThread)
            injectFactory(screen, hooked)
            openCategory(screen, R.string.diagnostics)
            val button = probeButton(screen)
            startRunAndAwaitQueuedDelivery(screen, runThread)
            assertEquals(screen.getString(R.string.settings_turn_camera_probe_cancel), button.text)
            assertFalse(hooked.isCancelled())
            button.performClick()
            assertTrue(hooked.isCancelled())
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("UI must discard a result cancelled after run returned", TurnCameraSettings.lastProbe(context))
            assertNull("late-cancelled result must not display a report", ShadowAlertDialog.getLatestAlertDialog())
            assertEquals("button returns to idle after the discarded delivery",
                screen.getString(R.string.settings_turn_camera_probe), probeButton(screen).text)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun lateCancelledFailureDeliversNoErrorToast() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val screen = controller.get()
            val runThread = AtomicReference<Thread?>()
            val hooked = hookedProbe(runThread, failAfterStart = true)
            injectFactory(screen, hooked)
            openCategory(screen, R.string.diagnostics)
            startRunAndAwaitQueuedDelivery(screen, runThread)
            probeButton(screen).performClick() // cancel the finished run before delivery
            assertTrue(hooked.isCancelled())
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("no failure toast after a late cancellation", ShadowToast.getLatestToast())
            assertNull(TurnCameraSettings.lastProbe(context))
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun successfulRunStillDeliversAndTheNextRunWorksAfterALateCancel() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val screen = controller.get()
            // First run: late-cancelled, discarded.
            val firstRunThread = AtomicReference<Thread?>()
            val cancelled = hookedProbe(firstRunThread)
            injectFactory(screen, cancelled)
            openCategory(screen, R.string.diagnostics)
            startRunAndAwaitQueuedDelivery(screen, firstRunThread)
            probeButton(screen).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(TurnCameraSettings.lastProbe(context))
            // Second run: a fresh probe with no cancellation must deliver normally.
            val nextRunThread = AtomicReference<Thread?>()
            val healthy = hookedProbe(nextRunThread)
            injectFactory(screen, healthy)
            assertEquals(screen.getString(R.string.settings_turn_camera_probe), probeButton(screen).text)
            probeButton(screen).performClick()
            val deadline = System.nanoTime() + 10_000_000_000L
            while (nextRunThread.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
            nextRunThread.get()!!.join(10_000)
            shadowOf(Looper.getMainLooper()).idle()
            assertNotNull("a clean run must save its summary", TurnCameraSettings.lastProbe(context))
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull("a clean run must show its report", dialog)
            assertEquals(screen.getString(R.string.settings_turn_camera_probe_title), shadowOf(dialog).title)
            dialog!!.dismiss()
            assertEquals(screen.getString(R.string.settings_turn_camera_probe), probeButton(screen).text)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun lateCancelAcrossACategoryReRenderStillDiscards() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
        try {
            val screen = controller.get()
            val runThread = AtomicReference<Thread?>()
            val hooked = hookedProbe(runThread)
            injectFactory(screen, hooked)
            openCategory(screen, R.string.diagnostics)
            probeButton(screen).performClick()
            // Navigate to another category (full re-render) and back while the probe runs:
            // a category page only offers Back, so return to the overview first.
            screen.onBackPressedDispatcher.onBackPressed()
            openCategory(screen, R.string.settings_advanced)
            screen.onBackPressedDispatcher.onBackPressed()
            openCategory(screen, R.string.diagnostics)
            val rebuiltButton = probeButton(screen)
            assertEquals(screen.getString(R.string.settings_turn_camera_probe_cancel), rebuiltButton.text)
            val deadline = System.nanoTime() + 10_000_000_000L
            while (runThread.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
            runThread.get()!!.join(10_000)
            assertFalse(shadowOf(Looper.getMainLooper()).isIdle)
            rebuiltButton.performClick()
            assertTrue(hooked.isCancelled())
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(TurnCameraSettings.lastProbe(context))
            assertNull(ShadowAlertDialog.getLatestAlertDialog())
            assertEquals(screen.getString(R.string.settings_turn_camera_probe), probeButton(screen).text)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
