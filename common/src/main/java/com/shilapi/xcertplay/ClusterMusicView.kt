package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import com.shilapi.xcertplay.media.CarPlayNowPlaying
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
    private var previousArtwork: Bitmap? = null
    private var previousInfo: CarPlayNowPlaying? = null
    private var frameColor = Color.rgb(28, 32, 40)
    private var previousFrameColor = frameColor
    private var artworkChangedAt = 0L
    private var textChangedAt = 0L
    private var visibleUntil = 0L
    private var lastTrackRevision: Long? = null
    private val expire = Runnable { updateVisibility() }
    private var captionVisible = false
    private val colorEvaluator = android.animation.ArgbEvaluator()
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
            val now = SystemClock.elapsedRealtime()
            if (snapshot.artwork !== it.artwork) {
                previousArtwork = snapshot.artwork
                previousFrameColor = frameColor
                frameColor = coverFrameColor(it.artwork)
                artworkChangedAt = now
            }
            if (snapshot.info.title != it.info.title || snapshot.info.artist != it.info.artist ||
                snapshot.info.album != it.info.album) {
                previousInfo = snapshot.info
                textChangedAt = now
            }
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
        previousArtwork = null
        previousInfo = null
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
        if (visibility == VISIBLE && remaining > 0) {
            // Wake for the fade-out, then stop drawing after the deadline. No idle frame loop.
            main.postDelayed(expire, if (remaining > FADE_MILLIS) remaining - FADE_MILLIS else remaining)
        }
        if (visibility != VISIBLE) {
            previousArtwork = null
            previousInfo = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val now = SystemClock.elapsedRealtime()
        val remaining = visibleUntil - now
        val captionAlpha = (remaining.toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
        val artProgress = progress(now, artworkChangedAt)
        val textProgress = progress(now, textChangedAt)
        val side = tileSide(width, height, ClusterMusicSettings.size(context))
        val left = (width * ClusterMusicSettings.x(context) / 100f - side / 2).coerceIn(0f, width - side)
        val top = (height * ClusterMusicSettings.y(context) / 100f - side / 2).coerceIn(0f, height - side)
        val rect = RectF(left, top, left + side, top + side)
        val cardLayer = if (!ClusterMusicSettings.permanent(context))
            canvas.saveLayerAlpha(rect, (captionAlpha * 255).toInt()) else canvas.save()
        // Match the speedometer's palette and corner radius relative to tile height.
        val radius = side * .22f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = colorEvaluator.evaluate(artProgress, previousFrameColor, frameColor) as Int
        canvas.drawRoundRect(rect, radius, radius, paint)
        val inset = side * .015f
        val artRect = RectF(left + inset, top + inset, rect.right - inset, rect.bottom - inset)
        val clipped = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(artRect, radius - inset, radius - inset, Path.Direction.CW) })
        val art = snapshot.artwork?.takeUnless { it.isRecycled }
        val oldArt = previousArtwork?.takeUnless { it.isRecycled }
        paint.color = Color.rgb(28, 32, 40)
        canvas.drawRect(artRect, paint)
        if (oldArt != null && artProgress < 1f) {
            // Draw the old image opaque beneath the incoming alpha: its contribution falls
            // as the new one rises, without briefly exposing the map through the card.
            paint.alpha = 255
            canvas.drawBitmap(oldArt, null, artRect, paint)
        }
        if (art != null) {
            paint.alpha = (artProgress * 255).toInt()
            canvas.drawBitmap(art, null, artRect, paint)
            paint.alpha = 255
        } else {
            textPaint.textSize = side * .40f
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("♪", rect.centerX(), top + side * .67f, textPaint)
        }
        if (captionVisible) {
            previousInfo?.takeIf { textProgress < 1f }?.let {
                drawCaption(canvas, it, artRect, side, captionAlpha * (1f - textProgress))
            }
            drawCaption(canvas, snapshot.info, artRect, side, captionAlpha * textProgress)
        }
        canvas.restoreToCount(clipped)
        canvas.restoreToCount(cardLayer)
        if (artProgress >= 1f) previousArtwork = null
        if (textProgress >= 1f) previousInfo = null
        if (artProgress < 1f || textProgress < 1f ||
            (captionVisible && (captionAlpha < 1f || remaining <= FADE_MILLIS))) postInvalidateOnAnimation()
    }

    private fun drawCaption(canvas: Canvas, info: CarPlayNowPlaying, artRect: RectF, side: Float, alpha: Float) {
        if (alpha <= 0f || (info.title.isNullOrBlank() && info.artist.isNullOrBlank())) return
        val layer = canvas.saveLayerAlpha(artRect, (alpha * 255).toInt())
        val pad = side * .075f
        val textLeft = artRect.left + pad
        val available = (artRect.width() - pad * 2).toInt().coerceAtLeast(1)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        textPaint.textSize = side * .125f
        val title = info.title.orEmpty()
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
        val detail = info.artist?.takeIf { it.isNotBlank() }
            ?: info.album?.takeIf { it.isNotBlank() } ?: context.getString(R.string.settings_cluster_music_unknown)
        canvas.drawText(TextUtils.ellipsize(detail, textPaint, available.toFloat(), TextUtils.TruncateAt.END).toString(),
            textLeft, artRect.top + pad * 2 + layout.height + side * .095f, textPaint)
        canvas.restoreToCount(layer)
    }

    companion object {
        // Sample once when artwork changes, never on animation frames. Reuse the album hue
        // selector; monochrome covers fall back to their average instead of an unrelated hue.
        internal fun coverFrameColor(artwork: Bitmap?): Int {
            if (artwork == null || artwork.isRecycled) return Color.rgb(28, 32, 40)
            val sample = Bitmap.createScaledBitmap(artwork, 24, 24, true)
            val pixels = IntArray(24 * 24)
            try { sample.getPixels(pixels, 0, 24, 0, 0, 24, 24) }
            finally { if (sample !== artwork) sample.recycle() }
            com.shilapi.xcertplay.media.AmbientAlbumPalette.dominantRgb(pixels)?.let { return it }
            var red = 0; var green = 0; var blue = 0; var count = 0
            for (pixel in pixels) if (Color.alpha(pixel) >= 128) {
                red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++
            }
            return if (count == 0) Color.rgb(28, 32, 40) else Color.rgb(red / count, green / count, blue / count)
        }

        const val FADE_MILLIS = 400L
        internal fun progress(now: Long, started: Long): Float =
            ((now - started).toFloat() / FADE_MILLIS).coerceIn(0f, 1f)
        const val TEMPORARY_MILLIS = 8_000L
        // 100% matches the default speedometer tile height on a 1280x480 panel.
        fun tileSide(width: Int, height: Int, sizePercent: Int): Float =
            (height * .30f * sizePercent.coerceIn(50, 300) / 100f)
                .coerceAtLeast(minOf(80f, width.toFloat(), height.toFloat()))
                .coerceAtMost(minOf(width.toFloat(), height.toFloat()))
    }
}
