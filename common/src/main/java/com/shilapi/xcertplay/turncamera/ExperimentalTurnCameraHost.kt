package com.shilapi.xcertplay.turncamera

import android.content.Context
import android.content.SharedPreferences
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.FrameLayout
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Opt-in experimental runtime, hosted ONLY inside an existing, confirmed DiPlay
 * cluster route. Default-off; no screen routing, permission grants, OEM launch, or car writes.
 * A worker stuck in a vendor getter/open/close retains the process-wide host admission. */
internal class ExperimentalTurnCameraHost private constructor(
    private val context: Context,
    root: FrameLayout,
    viewport: () -> RectF,
    private val hostVisible: () -> Boolean,
    private val profile: TurnCameraExperimentSettings.Profile,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(true)
    val isRunning: Boolean get() = running.get()
    private val permit = AtomicBoolean(false)
    private val readingAt = AtomicLong(Long.MIN_VALUE)
    private val pollDone = AtomicBoolean(false)
    private val vendorDone = AtomicBoolean(false)
    private val admissionReleased = AtomicBoolean(false)
    private val layer = ClusterTurnCameraLayer(root, viewport, profile.safeArea) {
        faulted.set(true)
        TurnCameraExperimentSettings.setEnabled(context, false)
        close()
    }
    private val worker = object : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        { task -> Thread(task, "turn-camera-vendor").apply { isDaemon = true } }, AbortPolicy()) {
        override fun terminated() { vendorDone.set(true); releaseAdmission() }
    }
    private val backend = QueuedSideCameraBackend<Surface>(profile.routes, layer, worker,
        post = { main.post(it) }, allowed = ::allowed, elapsed = SystemClock::elapsedRealtime,
        nanos = System::nanoTime, openVendor = { route, surface, guard ->
            VendorAvmCameraDriver().open(route.id, surface, route.channel, guard)
        })
    private val coordinator = TurnCameraCoordinator(backend, layer, { main.post(it) },
        generations.incrementAndGet(), SystemClock::elapsedRealtime)
    private val prefs = context.getSharedPreferences(TurnCameraExperimentSettings.PREFS, 0)
    private val calibrationPrefs = context.getSharedPreferences("diplay_turn_camera_calibration", 0)
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> main.post { close() } }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (!hostVisible() || !layer.isAttachedToWindow) { close(); return }
            if (!layer.clearOfPriorityOverlays() || !allowed()) coordinator.blocked()
            coordinator.tick()
            main.postDelayed(this, 50)
        }
    }
    private val poll = Thread({
        try {
            val source = BydExperimentalTurnSignalSource(context, profile, SystemClock::elapsedRealtime)
            try {
                val stamp = TurnCameraSettings.firmwareKey()
                while (running.get()) {
                    if (stamp == null || stamp != TurnCameraSettings.firmwareKey()) break
                    val reading = source.read()
                    val now = SystemClock.elapsedRealtime()
                    val good = reading.native == NativeCameraState.IDLE &&
                        reading.signal.state in listOf(TurnSignalState.LEFT, TurnSignalState.RIGHT) &&
                        now - reading.signal.atMillis in 0..500
                    readingAt.set(reading.signal.atMillis)
                    permit.set(good)
                    main.post {
                        if (running.get() && hostVisible() && layer.clearOfPriorityOverlays()) {
                            if (now - reading.signal.atMillis in 0..500 &&
                                SystemClock.elapsedRealtime() - reading.signal.atMillis in 0..500) {
                                coordinator.reading(reading.signal, reading.native)
                            } else coordinator.blocked()
                        }
                    }
                    Thread.sleep(100)
                }
            } finally { source.close() }
        } catch (_: Exception) {
            Log.w("DiPlay-TurnCamera", "experimental read-only source unavailable; hiding")
        } finally {
            permit.set(false)
            pollDone.set(true)
            main.post { close() }
            releaseAdmission()
        }
    }, "turn-camera-signals").apply { isDaemon = true }

    init {
        try {
        val overlayIndex = (0 until root.childCount).firstOrNull {
            root.getChildAt(it) is com.shilapi.xcertplay.ClusterTurnCardView ||
                root.getChildAt(it) is com.shilapi.xcertplay.ClusterSpeedometerView ||
                root.getChildAt(it) is com.shilapi.xcertplay.ClusterMusicView
        } ?: root.childCount
        root.addView(layer, overlayIndex, FrameLayout.LayoutParams(-1, -1))
        coordinator.start()
        prefs.registerOnSharedPreferenceChangeListener(changed)
        calibrationPrefs.registerOnSharedPreferenceChangeListener(changed)
        main.post(watchdog)
        poll.start()
        } catch (failure: Throwable) {
            running.set(false); permit.set(false)
            main.removeCallbacks(watchdog)
            prefs.unregisterOnSharedPreferenceChangeListener(changed)
            calibrationPrefs.unregisterOnSharedPreferenceChangeListener(changed)
            root.removeView(layer)
            worker.shutdown()
            throw failure
        }
    }
    private fun allowed(): Boolean = running.get() && !faulted.get() && permit.get() &&
        SystemClock.elapsedRealtime() - readingAt.get() in 0..500

    override fun close() {
        if (!running.getAndSet(false)) return
        permit.set(false)
        main.removeCallbacks(watchdog)
        prefs.unregisterOnSharedPreferenceChangeListener(changed)
        calibrationPrefs.unregisterOnSharedPreferenceChangeListener(changed)
        try { coordinator.close() } finally {
            (layer.parent as? FrameLayout)?.removeView(layer)
            poll.interrupt()
            worker.shutdown() // NEVER discard the queued native close task.
        }
    }
    private fun releaseAdmission() {
        if (pollDone.get() && vendorDone.get() && admissionReleased.compareAndSet(false, true)) admitted.set(false)
    }
    companion object {
        private val admitted = AtomicBoolean(false)
        private val faulted = AtomicBoolean(false)
        private val generations = AtomicLong()
        fun attach(context: Context, root: FrameLayout, viewport: () -> RectF,
                   hostVisible: () -> Boolean): ExperimentalTurnCameraHost? {
            if (faulted.get() || !hostVisible()) return null
            val profile = TurnCameraExperimentSettings.profile(context) ?: return null
            if (!admitted.compareAndSet(false, true)) return null
            return try { ExperimentalTurnCameraHost(context.applicationContext, root, viewport, hostVisible, profile) }
            catch (_: Exception) { admitted.set(false); null }
        }
    }
}

/** Re-arms only an explicitly enabled profile after a foreground/route transition.
 * Retries admission without allocating surfaces while a previous worker is closing. */
internal class ExperimentalTurnCameraAttachment(
    private val context: Context, private val root: FrameLayout,
    private val viewport: () -> RectF, private val visible: () -> Boolean,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var runtime: ExperimentalTurnCameraHost? = null
    private val tick = object : Runnable {
        override fun run() {
            if (closed) return
            if (runtime?.isRunning == false) runtime = null
            if (!visible() || !TurnCameraExperimentSettings.enabled(context)) {
                runtime?.close(); runtime = null
            } else if (runtime == null) {
                runtime = ExperimentalTurnCameraHost.attach(context, root, viewport, visible)
            }
            main.postDelayed(this, 250)
        }
    }
    init { main.post(tick) }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacks(tick)
        runtime?.close(); runtime = null
    }
}
