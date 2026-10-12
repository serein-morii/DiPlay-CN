package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.text.StaticLayout
import android.view.View
import com.shilapi.xcertplay.host.R

/** Local music card inside the existing projection; does not change any OEM cluster mode. */
internal class ClusterMusicView(context: Context) : View(context) {
    private val main = Handler(Looper.getMainLooper())
    private var stopState: (() -> Unit)? = null
    private var stopSettings: (() -> Unit)? = null
    private var snapshot = ClusterMusicState.Snapshot()
    private var visibleUntil = 0L
    private var lastTrackRevision: Long? = null
    private val expire = Runnable { updateVisibility() }
    private var captionVisible = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopSettings = ClusterMusicSettings.observe(context) {
            visibleUntil = SystemClock.elapsedRealtime() + TEMPORARY_MILLIS
            updateVisibility()
        }
        stopState = ClusterMusicState.observe {
            snapshot = it
            if (lastTrackRevision != it.revision) {
                lastTrackRevision = it.revision
                visibleUntil = it.changedAt + TEMPORARY_MILLIS
            }
            updateVisibility()
        }
    }

    override fun onDetachedFromWindow() {
        stopState?.invoke(); stopState = null
        stopSettings?.invoke(); stopSettings = null
        main.removeCallbacksAndMessages(null)
        visibility = GONE
        snapshot = ClusterMusicState.Snapshot()
        lastTrackRevision = null
        captionVisible = false
        super.onDetachedFromWindow()
    }

    private fun updateVisibility() {
        main.removeCallbacks(expire)
        val hasTrack = !snapshot.info.title.isNullOrBlank() || !snapshot.info.artist.isNullOrBlank()
        val permanent = ClusterMusicSettings.permanent(context)
        val remaining = visibleUntil - SystemClock.elapsedRealtime()
        captionVisible = ClusterMusicSettings.enabled(context) && hasTrack && remaining > 0
        visibility = if (ClusterMusicSettings.enabled(context) && hasTrack && (permanent || captionVisible)) VISIBLE else GONE
        // The caption expires even when permanent artwork keeps the view visible.
        if (visibility == VISIBLE && remaining > 0) main.postDelayed(expire, remaining)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val side = tileSide(width, height, ClusterMusicSettings.size(context))
        val left = (width * ClusterMusicSettings.x(context) / 100f - side / 2).coerceIn(0f, width - side)
        val top = (height * ClusterMusicSettings.y(context) / 100f - side / 2).coerceIn(0f, height - side)
        val rect = RectF(left, top, left + side, top + side)
        // Match the speedometer's palette and corner radius relative to tile height.
        val radius = side * .22f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(217, 12, 14, 18)
        canvas.drawRoundRect(rect, radius, radius, paint)
        val inset = side * .05f
        val artRect = RectF(left + inset, top + inset, rect.right - inset, rect.bottom - inset)
        val clipped = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(artRect, radius - inset, radius - inset, Path.Direction.CW) })
        val art = snapshot.artwork?.takeUnless { it.isRecycled }
        paint.color = Color.WHITE
        if (art != null) canvas.drawBitmap(art, null, artRect, paint)
        else {
            paint.color = Color.rgb(28, 32, 40)
            canvas.drawRect(artRect, paint)
            textPaint.textSize = side * .40f
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("♪", rect.centerX(), top + side * .67f, textPaint)
        }
        if (captionVisible) {
            val pad = side * .075f
            val textLeft = artRect.left + pad
            val available = (artRect.width() - pad * 2).toInt().coerceAtLeast(1)
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.WHITE
            textPaint.textSize = side * .125f
            val title = snapshot.info.title.orEmpty()
            val layout = StaticLayout.Builder.obtain(title, 0, title.length, textPaint, available)
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
            val bandBottom = artRect.top + layout.height + side * .13f + pad * 3
            paint.shader = LinearGradient(0f, artRect.top, 0f, bandBottom,
                Color.argb(240, 12, 14, 18), Color.argb(160, 12, 14, 18), Shader.TileMode.CLAMP)
            canvas.drawRect(artRect.left, artRect.top, artRect.right, bandBottom, paint)
            paint.shader = null
            val translated = canvas.save()
            canvas.translate(textLeft, artRect.top + pad)
            layout.draw(canvas)
            canvas.restoreToCount(translated)
            textPaint.textSize = side * .095f
            textPaint.color = Color.rgb(220, 224, 232)
            val detail = snapshot.info.artist?.takeIf { it.isNotBlank() }
                ?: snapshot.info.album?.takeIf { it.isNotBlank() } ?: context.getString(R.string.settings_cluster_music_unknown)
            canvas.drawText(TextUtils.ellipsize(detail, textPaint, available.toFloat(), TextUtils.TruncateAt.END).toString(),
                textLeft, artRect.top + pad * 2 + layout.height + side * .095f, textPaint)
        }
        canvas.restoreToCount(clipped)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = side * .012f
        paint.color = Color.argb(35, 255, 255, 255)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    companion object {
        const val TEMPORARY_MILLIS = 8_000L
        // 100% matches the default speedometer tile height on a 1280x480 panel.
        fun tileSide(width: Int, height: Int, sizePercent: Int): Float =
            (height * .30f * sizePercent.coerceIn(50, 150) / 100f)
                .coerceAtLeast(minOf(80f, width.toFloat(), height.toFloat()))
                .coerceAtMost(minOf(width.toFloat(), height.toFloat()))
    }
}
