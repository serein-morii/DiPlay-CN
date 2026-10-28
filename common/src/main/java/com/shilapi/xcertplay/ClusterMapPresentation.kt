package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.TextureView
import android.content.res.ColorStateList
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Shows CarPlay's instrument-cluster stream on a BYD cluster projection display.
 *
 * BYD exposes the cluster's projection area as public presentation displays owned by
 * com.byd.containerservice (DiLink 5) or com.xdja.containerservice (DiLink 4). The
 * cluster only shows this display while its projection mode is on, which DiPlay cannot switch.
 */
internal class ClusterMapPresentation(
    context: Context,
    display: Display,
    private val theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context),
    private val onSurface: (Surface?) -> Unit,
) : Presentation(context, display) {
    // The placeholder until the map arrives: a small spinner.
    private var waitingLabel: View? = null
    private var turnCardView: ClusterTurnCardView? = null
    private var videoView: View? = null
    private var streamActive = false
    private var cameraRoot: FrameLayout? = null
    private var cameraViewport: (() -> android.graphics.RectF)? = null
    private var cameraExperiment: com.shilapi.xcertplay.turncamera.ExperimentalTurnCameraAttachment? = null
    private var cameraHostActive = false
    var outputSurface: Surface? = null
        private set
    var mapVisible = true
        private set

    private val projectionHandler = Handler(Looper.getMainLooper())
    private val projectionWatchdog = ClusterProjectionWatchdog()
    private val projectionTick = object : Runnable {
        override fun run() {
            if (projectionWatchdog.onStatus(GeelyClusterProjection.status())) {
                Log.i(TAG, "cluster projection was taken away; asking for it again")
                if (GeelyClusterProjection.set(true, context)) projectionWatchdog.assertedProjection()
            }
            projectionHandler.postDelayed(this, PROJECTION_TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = sizeOf(display)
        val adaptive = DiLink51ClusterLayout.supported()
        val plan = if (adaptive) DiLink51ClusterLayout.plan(size.x, size.y, theme) else null
        // The adaptive DiLink 5.1 layout follows the cluster's own contrast; elsewhere the cluster follows DiPlay's theme.
        val dark = if (plan != null) DiLink51ClusterLayout.dark(context, theme) else context.resolveAppNightNow()
        val backdrop = if (dark) Color.rgb(15, 22, 30) else Color.rgb(207, 218, 229)
        val placeholder = if (dark) Color.BLACK else Color.rgb(233, 238, 246)
        val root = FrameLayout(context).apply {
            setBackgroundColor(if (plan == null) placeholder else if (plan.fullMap) backdrop else Color.TRANSPARENT)
        }
        if (plan != null) {
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val videoParams = if (plan == null) FrameLayout.LayoutParams(-1, -1) else
            FrameLayout.LayoutParams(plan.width, plan.height, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = plan.left; topMargin = plan.top
            }
        if (plan != null) {
            val textureView = TextureView(context)
            fun transform() {
                textureView.setTransform(Matrix().apply {
                    setScale(plan.streamWidth.toFloat() / plan.width, plan.streamHeight.toFloat() / plan.height)
                    postTranslate(-plan.sourceLeft.toFloat(), -plan.sourceTop.toFloat())
                })
            }
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(plan.streamWidth, plan.streamHeight)
                    transform()
                    outputSurface = Surface(texture).also(onSurface)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = transform()
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onSurface(null)
                    outputSurface?.release()
                    outputSurface = null
                    return true
                }
            }
            root.addView(textureView, videoParams)
            videoView = textureView
            if (plan.fullMap) root.addView(InstrumentContrastView(context, plan, backdrop), FrameLayout.LayoutParams(-1, -1))
            Log.i(TAG, "layout=$theme viewport=$plan source=${plan.sourceLeft},${plan.sourceTop} dark=$dark")
        } else {
            // Preserve the original PR renderer for DiLink 5 and unverified firmware.
            val surfaceView = SurfaceView(context)
            surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    Log.i(TAG, "cluster surface created")
                    outputSurface = holder.surface
                    onSurface(holder.surface)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    Log.i(TAG, "cluster surface ${width}x$height")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    Log.i(TAG, "cluster surface destroyed")
                    onSurface(null)
                    // SurfaceHolder owns this surface; do not release it ourselves.
                    outputSurface = null
                }
            })
            root.addView(surfaceView, videoParams)
            videoView = surfaceView
        }
        val waitingColor = if (dark) Color.WHITE else Color.DKGRAY
        val density = context.resources.displayMetrics.density
        waitingLabel = FrameLayout(context).apply {
            if (plan == null) setBackgroundColor(placeholder)
            addView(ProgressBar(context).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(waitingColor)
                contentDescription = context.getString(R.string.cluster_waiting_for_map)
            }, FrameLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt(), Gravity.CENTER))
        }
        root.addView(waitingLabel, FrameLayout.LayoutParams(videoParams))
        turnCardView = ClusterTurnCardView(context).apply { visibility = View.GONE }
        root.addView(turnCardView, FrameLayout.LayoutParams(-1, -1))
        root.addView(ClusterMusicView(context), FrameLayout.LayoutParams(videoParams))
        root.addView(ClusterSpeedometerView(context), FrameLayout.LayoutParams(-1, -1))
        cameraRoot = root
        cameraViewport = {
            val view = videoView
            if (view == null) android.graphics.RectF() else
                android.graphics.RectF(view.left.toFloat(), view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat())
        }
        // Start with the placeholder visible; SurfaceView would otherwise cover the light
        // backdrop with an empty black surface before the first stream notification.
        // SurfaceView ignores fractional alpha before API 34. Keep its decoder surface
        // visible and fade an opaque ordinary View over it instead.
        if (videoView !is SurfaceView) videoView?.alpha = if (streamActive) 1f else 0f
        waitingLabel?.apply {
            alpha = if (streamActive) 0f else 1f
            visibility = if (streamActive) View.GONE else View.VISIBLE
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        // On the Geely head unit the cluster keeps its factory layer over Android's display 2
        // until the DIM service is asked to project; the stock map does the same.
        if (!GeelyClusterDisplay.supported()) return
        projectionWatchdog.reset()
        if (GeelyClusterProjection.set(true, context)) projectionWatchdog.assertedProjection()
        // That switch belongs to the head unit, not to DiPlay: waking the stock map moves the
        // projection there and its session end turns the switch off, with no display callback and
        // no onStop here to notice. Watch the state for as long as this presentation is up.
        projectionHandler.removeCallbacks(projectionTick)
        projectionHandler.postDelayed(projectionTick, PROJECTION_TICK_MILLIS)
    }

    override fun onStop() {
        if (GeelyClusterDisplay.supported()) {
            projectionHandler.removeCallbacks(projectionTick)
            // Hand the projection back only while DiPlay still holds it. The switch is head-unit-wide
            // and the stock map drives it too, so when the map owns the cluster (the wheel key woke
            // it) switching it off here would take the map off the cluster as well. Read it back
            // first: the last readings decide, and this final one is the freshest evidence.
            val handBack = projectionWatchdog.shouldHandBack(GeelyClusterProjection.status())
            if (handBack) GeelyClusterProjection.set(false, context)
            projectionWatchdog.reset()
        }
        setTurnCameraExperimentActive(false)
        super.onStop()
    }

    /**
     * Cross-fades the waiting placeholder over the live map. TextureView can also fade the
     * video; legacy SurfaceView stays visible under the placeholder to preserve its surface.
     */
    fun setStreamActive(active: Boolean) {
        if (streamActive == active) return
        // Update before cancelling: a cancelled fade must not hide the newly restored label.
        streamActive = active
        refreshCameraExperiment()
        val video = videoView
        val label = waitingLabel
        video?.animate()?.cancel()
        label?.animate()?.cancel()
        if (video == null || label == null) return
        label.visibility = View.VISIBLE
        // Animate from the current opacity so a stream reversal does not jump or restart.
        if (video !is SurfaceView) video.animate().alpha(if (active) 1f else 0f).setDuration(300).start()
        label.animate().alpha(if (active) 0f else 1f).setDuration(300).withEndAction {
            if (streamActive == active) label.visibility = if (active) View.GONE else View.VISIBLE
        }.start()
    }

    /** Window alpha hides the pixels without destroying the TextureView/decoder surface. */
    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        refreshCameraExperiment()
        window?.let { window ->
            val alpha = if (visible) 1f else 0f
            if (window.attributes.alpha != alpha) window.attributes = window.attributes.apply { this.alpha = alpha }
        }
    }

    fun setTurnCardOverlay(xPercent: Int, yPercent: Int, sizePercent: Int) {
        turnCardView?.setLayout(xPercent, yPercent, sizePercent)
    }

    fun setTurnCardNightMode(night: Boolean) {
        turnCardView?.setNightMode(night)
    }

    fun setTurnCardOpacity(percent: Int) {
        turnCardView?.setOpacity(percent)
    }

    fun setTurnCardExtras(showLanes: Boolean, showArrival: Boolean) {
        turnCardView?.setShowArrival(showArrival)
    }

    fun setTurnCardGuidance(guidance: ClusterTurnGuidance?) {
        turnCardView?.setGuidance(guidance)
    }

    fun setTurnCameraExperimentActive(active: Boolean) {
        cameraHostActive = active
        // Reload the explicit profile when returning from Settings, including OFF.
        cameraExperiment?.close(); cameraExperiment = null
        refreshCameraExperiment()
    }
    private fun refreshCameraExperiment() {
        if (!cameraHostActive || !mapVisible || !streamActive || !isShowing) {
            cameraExperiment?.close(); cameraExperiment = null
            return
        }
        val root = cameraRoot ?: return
        val viewport = cameraViewport ?: return
        if (cameraExperiment == null) cameraExperiment = com.shilapi.xcertplay.turncamera.ExperimentalTurnCameraAttachment(
            context, root, viewport, { cameraHostActive && mapVisible && streamActive && isShowing && display.isValid })
    }
    companion object {
        const val TAG = "DiPlay-Cluster"

        /**
         * How often the Geely projection state is read. One binder transaction, and the watchdog needs
         * two off readings anyway, so the cluster is back within ~4 s of the stock map releasing it.
         */
        const val PROJECTION_TICK_MILLIS = 2_000L

        /** Keep the 5/5.1 selection order, then try the measured DiLink 4 projection display. */
        fun findDisplay(context: Context, theme: DiLink51ClusterLayout.Theme = DiLink51ClusterLayout.theme(context)): Display? {
            val displays = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: return null
            val name = DiLink51ClusterLayout.displayName(
                displays.map { it.name }, android.os.Build.FINGERPRINT, theme,
            )
            if (name == null) {
                // Never replace a missing 5.1 side layer with a full-screen display.
                if (DiLink51ClusterLayout.supported()) return null
                if (GeelyClusterDisplay.supported()) {
                    return displays.firstOrNull { display ->
                        val size = sizeOf(display)
                        GeelyClusterDisplay.matches(display, size.x, size.y)
                    }
                }
                return displays.firstOrNull { display ->
                    val size = sizeOf(display)
                    DiLink4ClusterDisplay.accepts(display.name, size.x, size.y)
                }
            }
            return displays.firstOrNull { it.name == name }?.takeIf {
                if (!DiLink51ClusterLayout.supported()) true else {
                    val size = sizeOf(it)
                    DiLink51ClusterLayout.plan(size.x, size.y, theme) != null
                }
            }
        }

        fun describeDisplays(context: Context): String =
            context.getSystemService(DisplayManager::class.java)?.displays
                ?.joinToString {
                    val size = sizeOf(it)
                    "${it.displayId}:${it.name} ${size.x}x${size.y} flags=${it.flags} valid=${it.isValid}"
                }.orEmpty()

        fun diagnosticReport(context: Context): String = buildString {
            appendLine("clusterEnabled=${AirPlayPersistence.loadClusterMapEnabled(context)}")
            appendLine("navigationReceiverAvailable=${com.shilapi.xcertplay.hud.BydOutputSettings.navigationAvailable(context)}")
            appendLine("allDisplays=${describeDisplays(context)}")
            val presentations = context.getSystemService(DisplayManager::class.java)
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).orEmpty()
            appendLine("presentationDisplayIds=${presentations.joinToString { it.displayId.toString() }}")
            val selected = findDisplay(context)
            append("selectedCluster=${selected?.let { "${it.displayId}:${it.name}" } ?: "none"}")
        }

        fun sizeOf(display: Display): Point = Point().also {
            @Suppress("DEPRECATION")
            display.getRealSize(it)
        }
    }
}

/** Shading belongs to DiPlay's map only; no stock cluster window is changed or covered outside the side card. */
private class InstrumentContrastView(
    context: Context,
    private val plan: DiLink51ClusterLayout.Plan,
    private val background: Int,
) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = plan.left.toFloat(); val top = plan.top.toFloat()
        val right = left + plan.width; val bottom = top + plan.height
        fun fade(x0: Float, y0: Float, x1: Float, y1: Float, start: Int, end: Int,
                 l: Float, t: Float, r: Float, b: Float) {
            paint.shader = LinearGradient(x0, y0, x1, y1, start, end, Shader.TileMode.CLAMP)
            canvas.drawRect(l, t, r, b, paint)
        }
        val clear = background and 0x00ffffff
        val soft = (background and 0x00ffffff) or (210 shl 24)
        if (plan.fullMap) {
            fade(left, top, left, top + 64, background, clear, left, top, right, top + 64)
            fade(left, bottom - 48, left, bottom, clear, background, left, bottom - 48, right, bottom)
            // The trip/menu readouts occupy the outer panels; keep the route and marker in the clear center.
            // They are 460 px wide on the measured 1920 px cluster and assumed to scale with a wider one.
            val panel = 460f * plan.width / DiLink51ClusterLayout.STREAM_WIDTH
            fade(left, top, left + panel, top, soft, clear, left, top, left + panel, bottom)
            fade(right - panel, top, right, top, clear, soft, right - panel, top, right, bottom)
        }
        paint.shader = null
    }
}
