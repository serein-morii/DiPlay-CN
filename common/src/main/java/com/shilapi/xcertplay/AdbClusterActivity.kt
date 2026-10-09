package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.ColorDrawable
import java.util.UUID
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import java.lang.ref.WeakReference

/** A separate task for stream 111. Routing is control traffic; video stays in the app process. */
class AdbClusterActivity : Activity() {
    private var waiting: TextView? = null
    private var surface: Surface? = null
    private var videoTexture: ClusterVideoTexture? = null
    private var turnCard: ClusterTurnCardView? = null
    private var safeAreaPreview: SafeAreaEditorView? = null
    internal var routeStatus = ""
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdbClusterRouter.enabled(this)) { finish(); return }
        val token = intent.getStringExtra("cluster_launch_token")
        if (!ClusterActivityOutput.acceptsToken(token)) { finish(); return }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val video = ClusterVideoTexture(this, onPresented = { ClusterActivityOutput.presented(this) }) { next ->
            surface?.let { ClusterActivityOutput.detach(this, it) }
            surface = next
            if (next != null && ClusterActivityOutput.activity.get() === this)
                ClusterActivityOutput.attach(this, next)
        }.also { videoTexture = it }
        val legacy = AirPlayPersistence.loadLegacyClusterEnabled(this)
        val videoRegion = FrameLayout(this)
        videoRegion.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(videoRegion, FrameLayout.LayoutParams(-1, -1))
        if (legacy) root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            LegacyClusterLayout.plan(root.width, root.height)?.let { plan ->
                val current = videoRegion.layoutParams as FrameLayout.LayoutParams
                if (current.width != plan.width || current.height != plan.height ||
                    current.leftMargin != plan.left || current.topMargin != plan.top) {
                    videoRegion.layoutParams = FrameLayout.LayoutParams(plan.width, plan.height).apply {
                        leftMargin = plan.left; topMargin = plan.top
                    }
                }
            }
        }
        waiting = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        videoRegion.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        turnCard = ClusterTurnCardView(this).apply { visibility = View.GONE }
        root.addView(turnCard, FrameLayout.LayoutParams(-1, -1))
        safeAreaPreview = SafeAreaEditorView(this).apply {
            visibility = View.GONE
            interactive = false
            dimOutside = false
        }
        root.addView(safeAreaPreview, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.post {
            val attached = root.display?.displayId ?: -1
            if (attached > 0 && !legacy) confirmDisplay(token, attached)
            else Thread({
                val verified = AdbClusterRouter.verify(applicationContext, taskId)
                runOnUiThread { confirmDisplay(token, verified ?: -1) }
            }, "cluster-display-check").start()
        }
        updateStream()
    }

    internal fun updateStream() {
        waiting?.visibility = if (ClusterActivityOutput.streamActive) View.GONE else View.VISIBLE
        waiting?.text = ""
        updateTurnCard()
    }

    internal fun updateTurnCard() {
        turnCard?.setLayout(ClusterActivityOutput.cardX, ClusterActivityOutput.cardY, ClusterActivityOutput.cardSize)
        turnCard?.setOpacity(ClusterActivityOutput.cardOpacity)
        turnCard?.setNightMode(ClusterActivityOutput.cardNight)
        turnCard?.setShowArrival(ClusterActivityOutput.showArrival)
        turnCard?.setGuidance(if (ClusterActivityOutput.streamActive) ClusterActivityOutput.guidance else null)
        updateSafeAreaPreview()
    }

    internal fun updateSafeAreaPreview() {
        val rect = ClusterActivityOutput.previewRect
        safeAreaPreview?.apply {
            visibility = if (rect == null) View.GONE else View.VISIBLE
            if (rect != null) { setRect(rect, 1920, 720); bringToFront() }
        }
    }

    private fun confirmDisplay(token: String?, display: Int) {
        if (isFinishing || isDestroyed || !ClusterActivityOutput.confirm(this, token, display)) {
            if (token != null && !ClusterActivityOutput.hasConfirmedRoute())
                com.shilapi.xcertplay.hud.BydOemClusterNavi.release(this, token)
            finish(); return
        }
        routeStatus = getString(R.string.adb_cluster_routed)
        surface?.let { ClusterActivityOutput.attach(this, it) }
        updateStream()
    }

    internal fun route() { ClusterActivityOutput.retry() }

    // Losing focus must not detach stream 111. The texture owns the rendering lifetime.
    override fun onDestroy() {
        videoTexture?.close()
        videoTexture = null
        if (ClusterActivityOutput.activity.get() === this) {
            intent.getStringExtra("cluster_launch_token")?.let {
                com.shilapi.xcertplay.hud.BydOemClusterNavi.release(this, it)
            }
            ClusterActivityOutput.activity.clear()
            ClusterActivityOutput.launchPending = false
            ClusterActivityOutput.retry()
        }
        super.onDestroy()
    }
}

/** Main-thread handoff, with identity checks so a stale activity cannot clear a newer surface. */
internal object ClusterActivityOutput {
    var activity = WeakReference<AdbClusterActivity>(null)
    var launchPending = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0
    @Volatile private var launchToken: String? = null
    @Volatile private var expectedDisplay = -1
    private var launchHost = WeakReference<Activity>(null)
    private var legacyPresented = false
    private val retryTick = Runnable {
        launchHost.get()?.let { host ->
            ensure(host)
            if (AirPlayPersistence.loadLegacyClusterEnabled(host) && !legacyPresented) retry()
        }
    }

    /** Texture updates confirm an output frame, rather than only a successful shell reply. */
    fun presented(window: AdbClusterActivity) {
        if (legacyPresented || activity.get() !== window || !AirPlayPersistence.loadLegacyClusterEnabled(window) ||
            !streamActive || surfaceOwner !== window || surface?.isValid != true || !hasConfirmedRoute()) return
        legacyPresented = true
        main.removeCallbacks(retryTick)
    }

    fun acceptsToken(token: String?): Boolean = token != null && token == launchToken && (hostOwner != null || previewHost.get() != null)
    fun hasConfirmedRoute(): Boolean = activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true &&
        expectedDisplay > 0
    fun confirm(window: AdbClusterActivity, token: String?, display: Int): Boolean {
        if (!acceptsToken(token) || display <= 0 || display != expectedDisplay ||
            !AdbClusterRouter.enabled(window)) return false
        activity.get()?.takeIf { it !== window }?.finish()
        activity = WeakReference(window)
        launchPending = false
        main.removeCallbacks(retryTick)
        if (AirPlayPersistence.loadLegacyClusterEnabled(window) && !legacyPresented) retry()
        return true
    }
    fun retry(force: Boolean = false) {
        if (force) legacyPresented = false
        main.removeCallbacks(retryTick)
        val host = launchHost.get() ?: return
        if (AirPlayPersistence.loadLegacyClusterEnabled(host) && legacyPresented) return
        if (hostOwner != null || previewHost.get() != null) main.postDelayed(retryTick, 5_000L)
    }
    var mainTaskId = -1
        private set
    var surface: Surface? = null
        private set
    private var surfaceOwner: Any? = null
    private var hostOwner: Any? = null
    private var onSurface: ((Surface?) -> Unit)? = null
    var streamActive = false
        private set

    var guidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance? = null
        private set
    var cardX = com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.DEFAULT_X_PERCENT
        private set
    var cardY = com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
        private set
    var cardSize = com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
        private set
    var cardOpacity = com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT
        private set
    var cardNight = false
        private set
    var showLanes = true
        private set
    var showArrival = true
        private set

    fun setTurnCard(next: com.shilapi.xcertplay.hud.ClusterTurnGuidance?, x: Int, y: Int,
        size: Int, opacity: Int, night: Boolean, lanes: Boolean = true, arrival: Boolean = true) {
        guidance = next
        cardX = x
        cardY = y
        cardSize = size
        cardOpacity = opacity
        cardNight = night
        showLanes = lanes
        showArrival = arrival
        activity.get()?.updateTurnCard()
    }

    private var previewOwner: Any? = null
    private var previewHost = WeakReference<Activity>(null)
    var previewRect: com.shilapi.xcertplay.airplay.SafeAreaRect? = null
        private set

    fun beginSafeAreaPreview(owner: Any, rect: com.shilapi.xcertplay.airplay.SafeAreaRect, host: Activity? = null) {
        previewOwner = owner
        previewHost = WeakReference(host)
        updateSafeAreaPreview(owner, rect)
        // Calibration owns a window, not a CarPlay session or decoder callback.
        if (hostOwner == null && host != null) ensure(host)
    }

    fun updateSafeAreaPreview(owner: Any, rect: com.shilapi.xcertplay.airplay.SafeAreaRect) {
        if (previewOwner !== owner) return
        previewRect = rect.clampTo(1920, 720)
        activity.get()?.updateSafeAreaPreview()
    }

    fun endSafeAreaPreview(owner: Any) {
        if (previewOwner !== owner) return
        previewOwner = null
        previewHost.clear()
        previewRect = null
        activity.get()?.updateSafeAreaPreview()
        if (hostOwner == null) closeRoute()
    }

    fun bind(owner: Any, taskId: Int, callback: (Surface?) -> Unit) {
        if (hostOwner !== owner && !hasConfirmedRoute() && previewHost.get() == null) {
            ++generation
            launchToken = null
            expectedDisplay = -1
            launchPending = false
        }
        hostOwner = owner
        if (owner is Activity) launchHost = WeakReference(owner)
        mainTaskId = taskId
        onSurface = callback
        callback(surface)
    }

    fun ensure(host: Activity) {
        if (!AdbClusterRouter.enabled(host) || host.isFinishing || host.isDestroyed) return
        if (AirPlayPersistence.loadLegacyClusterEnabled(host) && legacyPresented &&
            activity.get()?.let { !it.isFinishing && !it.isDestroyed } != true) return
        if (hostOwner !== host && !(hostOwner == null && previewHost.get() === host)) return
        launchHost = WeakReference(host)
        if (activity.get()?.let { !it.isFinishing && !it.isDestroyed } == true || launchPending) return
        launchPending = true
        val epoch = ++generation
        val token = UUID.randomUUID().toString()
        launchToken = token
        expectedDisplay = -1
        val app = host.applicationContext
        val holdStockMap = hostOwner != null
        Thread({
            val result = AdbClusterRouter.launch(app, token, holdStockMap = holdStockMap) { display ->
                if (generation != epoch || launchToken != token) false
                else { expectedDisplay = display; true }
            }
            main.post {
                completeLaunch(token, generation == epoch, result.success) {
                    com.shilapi.xcertplay.hud.BydOemClusterNavi.release(app, token)
                }
            }
        }, "adb-cluster-launch").start()
    }

    /** Actual display confirmation overrides an inconclusive shell reply. Main thread only. */
    internal fun completeLaunch(token: String, current: Boolean, accepted: Boolean, release: () -> Unit) {
        if (!current) { release(); return }
        launchPending = false
        if (!hasConfirmedRoute()) {
            if (!accepted) {
                // Invalidate admission before releasing OEM state: a delayed Activity cannot attach.
                if (launchToken == token) { launchToken = null; expectedDisplay = -1 }
                release()
            }
            retry()
        }
    }

    fun attach(owner: Any, next: Surface) {
        surfaceOwner = owner
        surface = next
        onSurface?.invoke(next)
    }

    fun detach(owner: Any, old: Surface) {
        if (surfaceOwner !== owner || surface !== old) return
        surfaceOwner = null
        surface = null
        onSurface?.invoke(null)
    }

    fun setStreamActive(active: Boolean) {
        streamActive = active
        activity.get()?.updateStream()
    }

    /** Settings may change while the projection host is paused. Stop its old lease immediately. */
    fun stopForSettings() { closeRoute() }

    fun stop(owner: Any) {
        if (hostOwner !== owner) return
        closeRoute()
    }

    private fun closeRoute() {
        val releaseContext = launchHost.get()?.applicationContext
        val releaseLease = launchToken
        ++generation
        legacyPresented = false
        launchToken = null
        expectedDisplay = -1
        launchHost.clear()
        if (releaseContext != null)
            com.shilapi.xcertplay.hud.BydOemClusterNavi.release(releaseContext, releaseLease)
        main.removeCallbacks(retryTick)
        onSurface?.invoke(null)
        onSurface = null
        hostOwner = null
        mainTaskId = -1
        surface = null
        surfaceOwner = null
        launchPending = false
        guidance = null
        previewOwner = null
        previewHost.clear()
        previewRect = null
        setStreamActive(false)
        val oldActivity = activity.get()
        activity.clear()
        oldActivity?.finish()
    }
}
