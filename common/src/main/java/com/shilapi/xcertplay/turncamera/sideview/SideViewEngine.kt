package com.shilapi.xcertplay.turncamera.sideview

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.roundToInt

/**
 * The engine of the turn side-view feature: one loop that reads the car, decides, and drives a
 * pair of overlay windows fed by the vendor AVM camera.
 *
 * Threading model (windows are not thread-safe, vendor calls block):
 *   - Main handler: the loop body, all window work, all engine state.
 *   - "sideview-read": the ADB round trip of a tick (blocking, tens of milliseconds).
 *   - "sideview-cam": vendor camera open/close (blocking, hundreds of milliseconds).
 * A tick posts its read out, the continuation comes back to Main; a camera open/close posts out
 * the same way. Nothing else ever touches engine state.
 *
 * Loop cadence is 150 ms while the car is in play (out of reverse, near the threshold) and
 * 1 s otherwise. The camera opens only when a blinker asks for it; once nothing wants it the
 * windows drop immediately and the camera itself closes after the ten-second warm window, so a
 * blinker re-pulled inside it is a single window move.
 *
 * Window hiding moves the window off screen instead of clearing it: the vendor then keeps filling
 * a live surface and showing it again is one updateViewLayout. (The window deliberately carries
 * no NOT_TOUCHABLE flag — stock Android clamps a non-touchable alert window's opacity, which
 * showed the picture bleeding through — so a SHOWN window does swallow touches inside its bounds.)
 */
internal class SideViewEngine(
    context: Context,
    private val prefs: SideViewPrefs = SideViewPrefs(context),
    private val telemetry: SideViewTelemetry = SideViewTelemetry(context),
    private val camera: SideViewCamera = SideViewCamera(),
    private val watch360: SideView360 = SideView360(context),
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var reads: ExecutorService? = null
    private var cam: ExecutorService? = null

    private val freshness = SideViewFreshness()
    private val retry = SideViewRetryGate()

    private var running = false
    private var readOut = false
    private var fast = false
    private var tearing = false
    private var found = false

    private var left: CameraWindow? = null    // left camera; projection panel if there is one
    private var right: CameraWindow? = null   // right camera; main screen
    private var leftOnMain = false
    private var appliedRight: Rect? = null
    private var appliedLeft: Rect? = null

    private var shown = SideViewSide.NONE
    private var shownAt = 0L
    private var attachedAt = 0L
    private var openedAt = 0L
    private var wantedLastTick = false
    private var warmSince = 0L

    /** Newest snapshot, for the status row; main thread only. */
    var lastSnapshot: SideViewTelemetry.Snapshot? = null
        private set

    fun start() {
        if (running) return
        running = true
        if (reads == null) reads = Executors.newSingleThreadExecutor { r ->
            Thread(r, "sideview-read").apply { isDaemon = true }
        }
        if (cam == null) cam = Executors.newSingleThreadExecutor { r ->
            Thread(r, "sideview-cam").apply { isDaemon = true }
        }
        watch360.start()
        main.post { schedule() }
        Log.i(TAG, "engine started")
    }

    fun stop() {
        running = false
        watch360.stop()
        main.post {
            tearDown("stopped") {
                reads?.shutdownNow(); cam?.shutdownNow()
                reads = null; cam = null
                telemetry.close()
            }
        }
    }

    // ── Loop ────────────────────────────────────────────────────────────────────

    private fun schedule() {
        if (!running) return
        main.postDelayed(::tick, if (fast) FAST_TICK_MS else IDLE_TICK_MS)
    }

    private fun tick() {
        if (!running || tearing || readOut) return
        if (!prefs.enabled) {
            tearDown("switched off") {}
            fast = false
            schedule()
            return
        }
        val pool = reads ?: return
        readOut = true
        try {
            pool.execute {
                val snapshot = telemetry.poll(SideViewTelemetry.VERIFIED, prefs.bsdWarn)
                main.post {
                    readOut = false
                    onRead(snapshot)
                }
            }
        } catch (_: RejectedExecutionException) {
            readOut = false
        }
    }

    private fun onRead(snapshot: SideViewTelemetry.Snapshot?) {
        if (!running) return
        lastSnapshot = snapshot
        val now = SystemClock.elapsedRealtime()

        freshness.accept(snapshot, now)
        if (freshness.channelGone) {
            if (anythingUp()) tearDown("signal channel gone") {}
            schedule()
            return
        }

        // In-play gate: out of reverse and within the warm band of the threshold.
        val speed = snapshot?.speedKmh
        val inPlay = speed != null &&
            speed >= prefs.thresholdKmh - WARM_BAND_KMH &&
            (snapshot.gear ?: freshness.lastGear()) != REVERSE_GEAR
        fast = inPlay

        val verdict = sideViewVerdict(
            SideViewFacts(
                blink = snapshot?.blink,
                speedKmh = speed,
                gear = snapshot?.gear ?: freshness.lastGear(),
                reverseGearCode = REVERSE_GEAR,
                thresholdKmh = prefs.thresholdKmh,
                telemetryAgeMs = freshness.ageMs,
                native360Foreground = watch360.foreground,
            )
        )
        retry.observed(verdict.show)

        // Reverse and the factory 360 own the screens outright; go fully down for either.
        if (verdict.show == SideViewSide.NONE && (isReverseNow(snapshot) || watch360.foreground)) {
            if (anythingUp()) tearDown(if (isReverseNow(snapshot)) "reverse" else "factory 360") {}
            schedule()
            return
        }

        if (verdict.warmCamera) {
            warmSince = 0L
            warmUp(now)
        } else {
            if (warmSince == 0L) warmSince = now
            if (now - warmSince >= COOL_DOWN_MS && anythingUp()) {
                tearDown("no signal for ${COOL_DOWN_MS / 1000}s") {}
            }
        }

        if (!found) {
            found = true
            onCam { camera.discover() }
        }

        applyWindowGeometry()
        applyShow(if (camera.warm) verdict.show else SideViewSide.NONE)
        if (prefs.bsdWarn && snapshot != null) {
            left?.warnBorder(shown == SideViewSide.LEFT && snapshot.bsdLeft == BSD_OBJECT)
            right?.warnBorder(shown == SideViewSide.RIGHT && snapshot.bsdRight == BSD_OBJECT)
        }
        schedule()
    }

    private fun isReverseNow(snapshot: SideViewTelemetry.Snapshot?): Boolean =
        (snapshot?.gear ?: freshness.lastGear()) == REVERSE_GEAR

    // ── Camera bring-up and health ──────────────────────────────────────────────

    private fun warmUp(now: Long) {
        if (retry.waiting(now)) return

        if (camera.cameraId < 0 && now - camera.lastDiscoverAt >= REDISCOVER_MS) {
            onCam { camera.discover() }
        }
        if (camera.cameraId < 0) {
            // Look the camera up again only after the full quiet interval, not per blinker.
            retry.failed(camera.lastDiscoverAt)
            return
        }

        if (left == null && right == null) {
            attachWindows()
            attachedAt = now
            if (left == null && right == null) cameraFailed("no window", now)
            return
        }

        if (!camera.warm) {
            val (leftChannel, rightChannel) = prefs.channels()
            val channels = buildMap {
                left?.surface?.let { put(leftChannel, it) }
                right?.surface?.let { put(rightChannel, it) }
            }
            if (channels.size < windowCount()) {
                if (now - attachedAt >= SURFACE_TIMEOUT_MS) cameraFailed("surface never arrived", now)
                return
            }
            onCam {
                val ok = camera.open(channels)
                main.post {
                    if (!running || tearing) return@post
                    if (!ok) {
                        cameraFailed("open refused", SystemClock.elapsedRealtime())
                    } else if (left == null && right == null) {
                        onCam { camera.close("windows gone during open") }
                    } else {
                        openedAt = SystemClock.elapsedRealtime()
                        Log.i(TAG, "camera warm on ${channels.keys.joinToString()}")
                    }
                }
            }
            return
        }

        // The vendor's first delivery after open is a stale buffer; the second is the first
        // honest frame. No frame at all inside the timeout, or a stream that goes quiet while
        // shown, both mean the pipeline is stuck: tear it down and let the next blinker rebuild.
        if (!(left?.hasFrames == true || right?.hasFrames == true)) {
            if (now - openedAt >= FIRST_FRAME_TIMEOUT_MS) cameraFailed("no frames", now)
            return
        }
        val stalled = (left != null && frameStalled(shown == SideViewSide.LEFT, left!!.hasFrames, left!!.lastFrameAt, shownAt, now)) ||
            (right != null && frameStalled(shown == SideViewSide.RIGHT, right!!.hasFrames, right!!.lastFrameAt, shownAt, now))
        if (stalled) cameraFailed("stream stalled", now)
    }

    private fun cameraFailed(why: String, now: Long) {
        Log.w(TAG, "camera failed: $why; retry in ${RETRY_DELAY_MS / 1000}s")
        tearDown(why) {}
        retry.failed(now)
    }

    private fun onCam(body: () -> Unit) {
        val pool = cam ?: return
        try {
            pool.execute(body)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun windowCount(): Int = (if (left != null) 1 else 0) + (if (right != null) 1 else 0)

    private fun anythingUp(): Boolean = left != null || right != null || camera.warm

    // ── Teardown ────────────────────────────────────────────────────────────────

    /** Camera closed first on the cam thread, then the windows removed on Main; [after] runs last. */
    private fun tearDown(why: String, after: () -> Unit) {
        if (tearing) {
            main.post(after)
            return
        }
        tearing = true
        val closeCamera = camera.warm
        onCam {
            if (closeCamera) camera.close(why)
            main.post {
                applyShow(SideViewSide.NONE)
                detachWithRetry(left); left = null; leftOnMain = false
                detachWithRetry(right); right = null
                appliedRight = null; appliedLeft = null
                shown = SideViewSide.NONE
                attachedAt = 0L; openedAt = 0L; warmSince = 0L
                wantedLastTick = false
                tearing = false
                after()
            }
        }
    }

    /** A removeView that threw for a real reason would leak the overlay; retry it. */
    private fun detachWithRetry(window: CameraWindow?) {
        window ?: return
        if (!window.detach()) {
            main.postDelayed({ window.detach() }, 500)
        }
    }

    // ── Windows ─────────────────────────────────────────────────────────────────

    /**
     * Left camera on the projection panel when the car has one (that is where the driver looks
     * before a left change) and the user did not opt for both-on-main; right camera on the main
     * screen. Without a panel both live on the main screen, mirrored corners.
     */
    private fun attachWindows() {
        val panel = projectionPanel()
        val usePanel = panel != null && !prefs.bothOnMain
        if (usePanel) {
            left = CameraWindow("left-panel", SideViewSide.LEFT).attachTo(panel!!, appContext)
        } else {
            leftOnMain = true
            val rect = SideViewPrefs.leftRect(
                screenW(), screenH(), prefs.windowWidthPct, prefs.rotate,
                prefs.leftX, prefs.leftY,
                SideViewPrefs.placedRect(screenW(), screenH(), prefs.windowWidthPct, prefs.rotate, prefs.rightX, prefs.rightY))
            left = CameraWindow("left-main", SideViewSide.LEFT).attachMain(appContext, rect)
            appliedLeft = rect
        }
        val rightRect = SideViewPrefs.placedRect(
            screenW(), screenH(), prefs.windowWidthPct, prefs.rotate, prefs.rightX, prefs.rightY)
        right = CameraWindow("right", SideViewSide.RIGHT).attachMain(appContext, rightRect)
        appliedRight = rightRect
        Log.i(TAG, "windows attached: left=${if (usePanel) "panel" else "main"} right=main")
    }

    private fun applyWindowGeometry() {
        if (right == null && !leftOnMain) return
        val rightRect = SideViewPrefs.placedRect(
            screenW(), screenH(), prefs.windowWidthPct, prefs.rotate, prefs.rightX, prefs.rightY)
        val leftRect = if (leftOnMain) SideViewPrefs.leftRect(
            screenW(), screenH(), prefs.windowWidthPct, prefs.rotate,
            prefs.leftX, prefs.leftY, rightRect) else null
        if (rightRect == appliedRight && leftRect == appliedLeft) return
        if (right?.move(rightRect) == false) return
        if (leftRect != null && left?.move(leftRect) == false) return
        appliedRight = rightRect
        appliedLeft = leftRect
    }

    /** Flip which window is on screen; state moves only when the move really landed. */
    private fun applyShow(side: SideViewSide) {
        if (side == shown) return
        if (left?.setVisible(side == SideViewSide.LEFT) == false) return
        if (right?.setVisible(side == SideViewSide.RIGHT) == false) return
        val previous = shown
        shown = side
        if (side != SideViewSide.NONE) shownAt = SystemClock.elapsedRealtime()
        if (side == SideViewSide.NONE) {
            left?.warnBorder(false)
            right?.warnBorder(false)
        }
        Log.i(TAG, "show $previous -> $side")
    }

    /** The cluster projection panel BYD exposes for overlays, preferring the "_1" variant. */
    private fun projectionPanel(): Display? {
        val manager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val candidates = manager.displays.filter { it.name.contains(PANEL_NAME, ignoreCase = true) }
        return candidates.firstOrNull { it.name.endsWith("_1") } ?: candidates.firstOrNull()
    }

    private fun screenW(): Int = screenMetrics().widthPixels
    private fun screenH(): Int = screenMetrics().heightPixels

    private fun screenMetrics(): DisplayMetrics {
        val manager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return appContext.resources.displayMetrics
        @Suppress("DEPRECATION")
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }

    // ── One overlay window ──────────────────────────────────────────────────────

    /**
     * A TextureView over a black frame, plus the BSD warning border. Hidden = parked off screen
     * (left of the edge plus a margin), keeping the vendor's surface live. Frame counting follows
     * the two-buffer rule: the first honest frame is the second delivery.
     */
    private inner class CameraWindow(
        private val name: String,
        private val side: SideViewSide,
    ) : TextureView.SurfaceTextureListener {
        private var host: WindowManager? = null
        private var params: WindowManager.LayoutParams? = null
        private var frame: View? = null
        private var texture: TextureView? = null
        private var border: View? = null
        private var breath: ValueAnimator? = null
        private var deliveries = 0

        @Volatile var lastFrameAt = 0L
            private set
        var surface: Surface? = null
            private set

        val hasFrames: Boolean get() = deliveries >= 2

        fun attachMain(context: Context, rect: Rect): CameraWindow? = attach(
            context,
            rect.width(), rect.height(), rect.left, rect.top,
        )

        fun attachTo(display: Display, context: Context): CameraWindow? {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also { display.getRealMetrics(it) }
            return attach(
                context.createDisplayContext(display),
                metrics.widthPixels, metrics.heightPixels, 0, 0,
            )
        }

        private fun attach(context: Context, width: Int, height: Int, x: Int, y: Int): CameraWindow? {
            shownX = x
            return try {
                val view = TextureView(context).apply {
                    surfaceTextureListener = this@CameraWindow
                    setOpaque(true)
                }
                val warn = View(context).apply {
                    background = GradientDrawable().apply {
                        setStroke((BORDER_STROKE_DP * resources.displayMetrics.density).roundToInt(), BORDER_COLOR)
                        setColor(Color.TRANSPARENT)
                    }
                    visibility = View.GONE
                }
                val holder = FrameLayout(context).apply {
                    setBackgroundColor(Color.BLACK)
                    addView(view, FrameLayout.LayoutParams(-1, -1))
                    addView(warn, FrameLayout.LayoutParams(-1, -1))
                }
                val layout = WindowManager.LayoutParams(
                    width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WINDOW_FLAGS,
                    PixelFormat.OPAQUE,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    this.x = offscreenX(width)
                    this.y = y
                }
                val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windows.addView(holder, layout)
                host = windows
                params = layout
                frame = holder
                texture = view
                border = warn
                visible = false
                Log.i(TAG, "$name attached ${width}x${height}")
                this
            } catch (e: Throwable) {
                Log.w(TAG, "$name attach failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }

        private var shownX = 0
        private var hiddenX = 0
        private var visible = false

        private fun offscreenX(width: Int) = -(width + HIDE_MARGIN_PX)

        /** Returns false when the window manager rejected the move (retried next tick). */
        fun setVisible(show: Boolean): Boolean = moveX(if (show) shownX else hiddenX, show)

        /** New rect; a hidden window keeps the new size while staying parked. */
        fun move(rect: Rect): Boolean {
            params ?: return true
            shownX = rect.left
            hiddenX = offscreenX(rect.width())
            return moveX(if (visible) shownX else hiddenX, visible, rect)
        }

        private fun moveX(x: Int, show: Boolean, rect: Rect? = null): Boolean {
            val view = frame ?: return true
            val layout = params ?: return true
            val undoX = layout.x
            val undoY = layout.y
            val undoW = layout.width
            val undoH = layout.height
            layout.x = x
            if (rect != null) {
                layout.y = rect.top
                layout.width = rect.width()
                layout.height = rect.height()
            }
            return runCatching { host?.updateViewLayout(view, layout) }
                .onSuccess { visible = show }
                .onFailure {
                    layout.x = undoX; layout.y = undoY; layout.width = undoW; layout.height = undoH
                    Log.w(TAG, "$name move rejected: ${it.javaClass.simpleName}")
                }
                .isSuccess
        }

        /** BSD warning: the border breathes while an object sits in this side's blind spot. */
        fun warnBorder(on: Boolean) {
            val edge = border ?: return
            if (on == (breath != null)) return
            if (!on) {
                breath?.cancel()
                breath = null
                edge.visibility = View.GONE
                return
            }
            edge.visibility = View.VISIBLE
            breath = ValueAnimator.ofFloat(1f, BORDER_MIN_ALPHA).apply {
                duration = BORDER_BREATH_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { edge.alpha = it.animatedValue as Float }
                start()
            }
        }

        fun detach(): Boolean {
            warnBorder(false)
            val view = frame ?: return true
            val error = runCatching { host?.removeView(view) }.exceptionOrNull()
            if (error != null && error !is IllegalArgumentException) {
                Log.w(TAG, "$name remove failed: ${error.javaClass.simpleName}")
                return false
            }
            host = null; params = null; frame = null; texture = null; border = null
            surface?.release(); surface = null
            deliveries = 0; lastFrameAt = 0L; visible = false
            return true
        }

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            surface?.release()
            surface = Surface(texture)
            deliveries = 0
            lastFrameAt = 0L
            crop(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            crop(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            // Ownership stays here: the buffer is released only after the camera closed.
            val doomed = texture
            main.post { tearDown("$name surface destroyed") { runCatching { doomed.release() } } }
            return false
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
            deliveries++
            lastFrameAt = SystemClock.elapsedRealtime()
        }

        /**
         * The AVM side buffer is 1920×1300; the view shows a centred, aspect-filling crop of it.
         * In portrait windows the picture stands a quarter-turn (side-dependent sign, verified
         * on the car) and can be flipped when a camera is mounted mirrored.
         */
        private fun crop(width: Int, height: Int) {
            val view = texture ?: return
            if (width <= 0 || height <= 0) return
            val upright = prefs.rotate
            val turn = if (upright) when (side) {
                SideViewSide.RIGHT -> 90f
                SideViewSide.LEFT -> -90f
                SideViewSide.NONE -> 0f
            } else 0f
            val flipX = when (side) {
                SideViewSide.LEFT -> prefs.mirrorLeft
                SideViewSide.RIGHT -> prefs.mirrorRight
                SideViewSide.NONE -> false
            }
            val flipY = prefs.flipVertical
            val turnedW = if (turn != 0f) SOURCE_H else SOURCE_W
            val turnedH = if (turn != 0f) SOURCE_W else SOURCE_H
            val scale = maxOf(width / turnedW, height / turnedH)
            val matrix = Matrix()
            matrix.postScale(SOURCE_W / width, SOURCE_H / height)
            if (turn != 0f) matrix.postRotate(turn, SOURCE_W / 2f, SOURCE_H / 2f)
            matrix.postTranslate(-SOURCE_W / 2f, -SOURCE_H / 2f)
            if (flipX) matrix.postScale(-1f, 1f)
            if (flipY) matrix.postScale(1f, -1f)
            matrix.postScale(scale, scale)
            matrix.postTranslate(width / 2f, height / 2f)
            view.setTransform(matrix)
        }
    }

    /** Compact status for the settings row. */
    fun statusLine(): String {
        val snapshot = lastSnapshot
        val (leftChannel, rightChannel) = prefs.channels()
        return buildString {
            append("speed=${snapshot?.speedKmh ?: "?"}")
            append(" blink=${snapshot?.blink ?: "?"}")
            append(" gear=${snapshot?.gear ?: "?"}")
            append(" 360=${watch360.foreground}")
            append(" camera=${if (!found) "untried" else if (camera.cameraId >= 0) "id ${camera.cameraId}" else "none"}")
            append(" ch L$leftChannel/R$rightChannel")
            if (!watch360.readable) append(" usageAppop=missing")
        }
    }

    fun cameraJournal(): List<String> = camera.journalTail()

    companion object {
        private const val TAG = "SideViewEngine"

        /** Gear code for R in the verified encoding (1=P 2=R 3=N 4=D). */
        const val REVERSE_GEAR = 2

        /** BSD radar word for "object present". */
        const val BSD_OBJECT = 2

        /** The loop speeds up this far below the threshold so slowing down does not flap it. */
        const val WARM_BAND_KMH = 5

        /** Projection panel name BYD's cluster displays carry. */
        private const val PANEL_NAME = "XDJAScreenProjection"

        /** AVM side-camera buffer (verified on the car). */
        private const val SOURCE_W = 1920f
        private const val SOURCE_H = 1300f

        private const val HIDE_MARGIN_PX = 100
        private const val WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        // BSD border: orange, a slow breath, a thick stroke.
        private const val BORDER_COLOR = 0xFFFF7A00.toInt()
        private const val BORDER_MIN_ALPHA = 0.35f
        private const val BORDER_BREATH_MS = 800L
        private const val BORDER_STROKE_DP = 12f
    }
}
