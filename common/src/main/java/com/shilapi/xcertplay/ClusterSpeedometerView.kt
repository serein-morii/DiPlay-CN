package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay

/**
 * Optional speedometer drawn by DiPlay over the dashboard map.
 *
 * The speed value is supplied independently from the CarPlay video stream so the
 * overlay remains available on clusters where full-screen projection hides the
 * OEM numerical speed readout.
 */
internal class ClusterSpeedometerView(
    context: Context,
    private val observeSpeed: ((Int?) -> Unit) -> (() -> Unit) = { changed ->
        com.shilapi.xcertplay.hud.BydClusterSpeedReader.observe(context, changed)
    },
) : View(context) {

    private var speedKmh: Int? = null
    private var stopObservingSettings: (() -> Unit)? = null
    private var stopObservingSpeed: (() -> Unit)? = null
    private var stopObservingArtwork: (() -> Unit)? = null
    private var albumArtwork: android.graphics.Bitmap? = null
    private var albumColor: Int? = null
    private var useAlbumColor = false
    private var previousBackgroundColor = Color.rgb(12, 14, 18)
    private var targetBackgroundColor = previousBackgroundColor
    private var backgroundChangedAt = 0L
    private val colorEvaluator = android.animation.ArgbEvaluator()
    private var readingGeneration = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val expireSpeed = Runnable { setSpeedKmh(null) }

    private fun stopSpeed() {
        readingGeneration++
        stopObservingSpeed?.invoke()
        stopObservingSpeed = null
        main.removeCallbacksAndMessages(null)
        setSpeedKmh(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopObservingSettings = AirPlayPersistence.observeClusterSpeedometer(context) { applySettings() }
        applySettings()
    }

    override fun onDetachedFromWindow() {
        stopObservingSettings?.invoke()
        stopObservingSettings = null
        stopSpeed()
        stopArtwork()
        super.onDetachedFromWindow()
    }

    private fun applySettings() {
        setLayout(AirPlayPersistence.loadClusterSpeedometerX(context),
            AirPlayPersistence.loadClusterSpeedometerY(context), AirPlayPersistence.loadClusterSpeedometerSize(context))
        setOpacity(AirPlayPersistence.loadClusterSpeedometerOpacity(context))
        useAlbumColor = AirPlayPersistence.loadClusterSpeedometerAlbumColor(context)
        val enabled = AirPlayPersistence.loadClusterSpeedometerEnabled(context)
        if (enabled && useAlbumColor) {
            if (stopObservingArtwork == null) {
                stopObservingArtwork = ClusterMusicState.observe { snapshot ->
                    if (albumArtwork !== snapshot.artwork) {
                        albumArtwork = snapshot.artwork
                        albumColor = snapshot.artwork?.takeUnless { it.isRecycled }
                            ?.let { ClusterMusicView.coverFrameColor(it) }
                        updateBackgroundTarget()
                    }
                }
            }
        } else {
            stopArtwork()
        }
        updateBackgroundTarget()
        if (!enabled) {
            stopSpeed()
        } else if (stopObservingSpeed == null) {
            val generation = ++readingGeneration
            stopObservingSpeed = observeSpeed { speed ->
                val receivedAt = android.os.SystemClock.elapsedRealtime()
                main.post {
                    if (generation == readingGeneration && isAttachedToWindow) {
                        main.removeCallbacks(expireSpeed)
                        setSpeedKmh(if (android.os.SystemClock.elapsedRealtime() - receivedAt < 2_000) speed else null)
                        main.postDelayed(expireSpeed, 2_000)
                    }
                }
            }
        }
    }

    private var xPercent = ClusterSpeedometerOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterSpeedometerOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterSpeedometerOverlay.DEFAULT_SIZE_PERCENT
    private var opacityPercent = ClusterSpeedometerOverlay.DEFAULT_OPACITY_PERCENT

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 220, 220, 225)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val rect = RectF()

    init {
        applyPalette()
        visibility = GONE
    }

    fun setLayout(xPercent: Int, yPercent: Int, sizePercent: Int) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.sizePercent = sizePercent
        invalidate()
    }

    fun setOpacity(percent: Int) {
        val next = percent.coerceIn(20, 100)
        if (next == opacityPercent) return

        opacityPercent = next
        applyPalette()
        invalidate()
    }

    fun setSpeedKmh(speed: Int?) {
        val next = speed?.coerceIn(0, 300)
        if (next == speedKmh) return

        speedKmh = next
        visibility = if (next == null) GONE else VISIBLE
        if (next != null) bringToFront()
        invalidate()
    }

    private fun stopArtwork() {
        stopObservingArtwork?.invoke()
        stopObservingArtwork = null
        albumArtwork = null
        albumColor = null
    }

    private fun backgroundColor(now: Long): Int = colorEvaluator.evaluate(
        ClusterMusicView.progress(now, backgroundChangedAt), previousBackgroundColor, targetBackgroundColor,
    ) as Int

    private fun updateBackgroundTarget() {
        val next = if (useAlbumColor) albumColor ?: Color.rgb(12, 14, 18) else Color.rgb(12, 14, 18)
        if (next == targetBackgroundColor) return
        val now = android.os.SystemClock.elapsedRealtime()
        previousBackgroundColor = backgroundColor(now)
        targetBackgroundColor = next
        backgroundChangedAt = now
        invalidate()
    }

    private fun applyPalette() {
        val alpha = (opacityPercent * 255 / 100).coerceIn(51, 255)

        backgroundPaint.color = Color.argb(alpha, 12, 14, 18)
        strokePaint.color =
            Color.argb((alpha * 0.16f).toInt().coerceAtLeast(10), 255, 255, 255)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val speed = speedKmh ?: return
        val card = ClusterSpeedometerOverlay.rect(
            width,
            height,
            xPercent,
            yPercent,
            sizePercent,
        )

        val w = card.width.toFloat()
        val h = card.height.toFloat()
        val centerX = card.left + w / 2f

        rect.set(
            card.left.toFloat(),
            card.top.toFloat(),
            card.left + w,
            card.top + h,
        )

        val now = android.os.SystemClock.elapsedRealtime()
        val color = backgroundColor(now)
        backgroundPaint.color = Color.argb((opacityPercent * 255 / 100).coerceIn(51, 255),
            Color.red(color), Color.green(color), Color.blue(color))
        if (ClusterMusicView.progress(now, backgroundChangedAt) < 1f) postInvalidateOnAnimation()
        val radius = h * 0.22f
        canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        // Use the displayed (interpolated) album color, not the final fade target.
        // Never fade the text through low-contrast gray. The map behind this translucent
        // view is a separate surface, so contrast is measured against the card RGB.
        if (useAlbumColor) {
            val luminance = ColorUtils.calculateLuminance(color)
            val blackContrast = (luminance + 0.05) / 0.05
            val whiteContrast = 1.05 / (luminance + 0.05)
            val foreground = if (blackContrast >= whiteContrast) Color.BLACK else Color.WHITE
            speedPaint.color = foreground
            unitPaint.color = foreground
        } else {
            speedPaint.color = Color.WHITE
            unitPaint.color = Color.argb(210, 220, 220, 225)
        }

        speedPaint.textSize = h * 0.48f
        unitPaint.textSize = h * 0.16f

        // Center the actual glyph bounds of the number and unit as one vertical block.
        val speedBounds = android.graphics.Rect()
        val unitBounds = android.graphics.Rect()
        val text = speed.toString()
        speedPaint.getTextBounds(text, 0, text.length, speedBounds)
        unitPaint.getTextBounds("km/h", 0, 4, unitBounds)
        val gap = h * 0.06f
        val blockHeight = speedBounds.height() + gap + unitBounds.height()
        val blockTop = card.top + (h - blockHeight) / 2f
        val speedY = blockTop - speedBounds.top
        val unitY = blockTop + speedBounds.height() + gap - unitBounds.top
        canvas.drawText(text, centerX, speedY, speedPaint)
        canvas.drawText("km/h", centerX, unitY, unitPaint)
    }
}
