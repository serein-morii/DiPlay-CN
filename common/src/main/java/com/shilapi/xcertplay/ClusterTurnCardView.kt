package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.View
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/**
 * Instruction card drawn by DiPlay on top of the dashboard map.
 *
 * Visual language follows Apple's turn banners: a dark glass capsule with a hairline stroke,
 * the maneuver glyph in a soft chip on the left, distance and road stacked on the right.
     * Maneuver glyphs are Material Symbols (Apache 2.0), tinted white; the roundabout
 * exit number sits in a small badge on the glyph.
 */
internal class ClusterTurnCardView(context: Context) : View(context), ClusterOverlayBounds {
    override fun occupiedBounds(): RectF? {
        if (guidance == null || width <= 0 || height <= 0) return null
        val area = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        return RectF(area.left.toFloat(), area.top.toFloat(), (area.left + area.width).toFloat(), (area.top + area.height).toFloat())
    }

    private fun invalidateMusic() {
        val siblings = parent as? android.view.ViewGroup ?: return
        for (index in 0 until siblings.childCount) (siblings.getChildAt(index) as? ClusterMusicView)?.invalidate()
    }
    private var guidance: ClusterTurnGuidance? = null
    private var night = false
    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
    private var showArrival = true

    private val accent = Color.rgb(10, 132, 255)
    /** Card opacity as percent; 100 keeps the historical fully-opaque look available. */
    private var opacityPercent = 85
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 199, 199, 204); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(224, 235, 235, 240); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()
    init { applyPalette() }

    private var glyph: Drawable? = null
    private var glyphTag: Int = -1
    private var thenGlyph: Drawable? = null
    private var thenGlyphTag: Int = -1
    private val thenTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 199, 199, 204); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    fun setLayout(xPercent: Int, yPercent: Int, sizePercent: Int) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.sizePercent = sizePercent
        invalidateMusic()
        invalidate()
    }

    /** Percent 20..100 of the card's background alpha; text stays readable at any value. */
    fun setOpacity(percent: Int) {
        val next = percent.coerceIn(20, 100)
        if (next == opacityPercent) return
        opacityPercent = next
        applyPalette()
        invalidate()
    }

    /** Day: dark glass on a light map; night: lighter translucent panel for the dark map. */
    fun setNightMode(night: Boolean) {
        if (night == this.night) return
        this.night = night
        applyPalette()
        invalidate()
    }

    private fun applyPalette() {
        val alpha = (opacityPercent * 255 / 100).coerceIn(51, 255)
        if (night) {
            glassPaint.color = Color.argb(alpha, 12, 14, 18)
            chipPaint.color = Color.argb((alpha * 0.18f).toInt().coerceAtLeast(12), 255, 255, 255)
            strokePaint.color = Color.argb((alpha * 0.16f).toInt().coerceAtLeast(10), 255, 255, 255)
            distancePaint.color = Color.WHITE
            roadPaint.color = Color.argb(245, 248, 248, 252)
            infoPaint.color = Color.WHITE
            badgePaint.color = accent
        } else {
            glassPaint.color = Color.argb(alpha, 28, 28, 30)
            chipPaint.color = Color.argb((alpha * 0.18f).toInt().coerceAtLeast(12), 255, 255, 255)
            strokePaint.color = Color.argb((alpha * 0.15f).toInt().coerceAtLeast(10), 255, 255, 255)
            distancePaint.color = Color.WHITE
            roadPaint.color = Color.argb(245, 248, 248, 252)
            infoPaint.color = Color.WHITE
            badgePaint.color = accent
        }
        thenTextPaint.color = roadPaint.color
    }

    fun setShowArrival(enabled: Boolean) {
        if (enabled == showArrival) return
        showArrival = enabled
        invalidate()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        invalidateMusic()
        if (guidance == next) {
            visibility = if (next == null) GONE else VISIBLE
            return
        }
        guidance = next
        visibility = if (next == null) GONE else VISIBLE
        bringToFront()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        val h = card.height.toFloat()
        val w = card.width.toFloat()

        val radius = h * 0.30f
        rect.set(card.left.toFloat(), card.top.toFloat(), card.left + w, card.top + h)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val chip = h * 0.76f
        val chipLeft = card.left + h * 0.12f
        val chipTop = card.top + (h - chip) / 2f
        rect.set(chipLeft, chipTop, chipLeft + chip, chipTop + chip)
        canvas.drawRoundRect(rect, chip * 0.26f, chip * 0.26f, chipPaint)

        val exit = next.roundaboutExit.takeIf { it in 1..9 }
        drawGlyph(canvas, next, chipLeft, chipTop, chip, exit)

        val textLeft = chipLeft + chip + h * 0.14f
        var textWidth = card.left + w - h * 0.10f - textLeft
        // The following-turn preview takes the card's right edge, Apple-banner style, once there
        // is room for it beside the distance and road; on a cramped card it yields.
        val showThen = next.secondIcon != 0 && textWidth > h * 1.6f
        if (showThen) textWidth -= h * 0.64f
        if (textWidth <= 0f) return
        distancePaint.textSize = h * 0.30f
        roadPaint.textSize = h * 0.17f
        canvas.drawText(
            ellipsize(distanceLabel(next.distanceMeters), textWidth, distancePaint),
            textLeft, card.top + h * 0.44f, distancePaint,
        )
        val road = roadLabel(next)
        if (road.isNotEmpty()) {
            canvas.drawText(
                ellipsize(road, textWidth, roadPaint),
                textLeft, card.top + h * 0.72f, roadPaint,
            )
        }
        if (showThen) drawThen(canvas, next, card.left + w, card.top.toFloat(), h)
        drawInfoStrip(canvas, next, card.left, (card.top + h).toInt(), w.toInt(), h.toInt())
    }

    /**
     * The following-turn preview on the card's right edge: a hairline divider, the small maneuver
     * glyph (roundabout exit badge included) and the word "then". The iPhone sends no distance for
     * the second turn, so — like the stock CarPlay banner — the block shows the glyph alone.
     */
    private fun drawThen(canvas: Canvas, next: ClusterTurnGuidance, cardRight: Float, cardTop: Float, h: Float) {
        val blockWidth = h * 0.52f
        val blockRight = cardRight - h * 0.04f
        val blockLeft = blockRight - blockWidth
        val dividerX = blockLeft - h * 0.06f
        canvas.drawLine(dividerX, cardTop + h * 0.24f, dividerX, cardTop + h * 0.76f, strokePaint)
        val glyphSide = h * 0.38f
        val centreX = blockLeft + blockWidth / 2f
        val resId = glyphRes(next.secondIcon)
        if (resId != thenGlyphTag) {
            thenGlyph = ContextCompat.getDrawable(context, resId)?.mutate()?.apply { setTint(Color.WHITE) }
            thenGlyphTag = resId
        }
        val glyphTop = cardTop + h * 0.13f
        thenGlyph?.setBounds(
            (centreX - glyphSide / 2f).toInt(), glyphTop.toInt(),
            (centreX + glyphSide / 2f).toInt(), (glyphTop + glyphSide).toInt(),
        )
        thenGlyph?.draw(canvas)
        val exit = next.secondExit.takeIf { it in 1..9 }
        if (exit != null) {
            val d = glyphSide * 0.46f
            val badgeCx = centreX + glyphSide / 2f - d / 2f
            val badgeCy = glyphTop + glyphSide - d / 2f
            canvas.drawCircle(badgeCx, badgeCy, d / 2f, badgePaint)
            badgeTextPaint.textSize = d * 0.62f
            val textY = badgeCy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(exit.toString(), badgeCx, textY, badgeTextPaint)
        }
        val label = context.getString(R.string.turn_card_then)
        thenTextPaint.textSize = h * 0.14f
        val labelWidth = thenTextPaint.measureText(label)
        canvas.drawText(label, centreX - labelWidth / 2f, cardTop + h * 0.88f, thenTextPaint)
    }

    /** The arrival/duration/distance pill that hangs under the card, like the stock nav bar. */
    private fun drawInfoStrip(canvas: Canvas, next: ClusterTurnGuidance, left: Int, belowTop: Int, width: Int, cardHeight: Int) {
        val parts = infoParts(next)
        if (parts.isEmpty()) return
        val h = (cardHeight * 0.40f).coerceAtLeast(34f)
        val gap = cardHeight * 0.05f
        val top = (belowTop + gap).coerceAtMost(height - h)
        infoPaint.textSize = h * 0.42f
        val separator = "   ·   "
        val text = parts.joinToString(separator)
        val textWidth = paintMeasure(text)
        // Wider than the card is fine: the strip centers on the card and may reach the panel edges.
        // left is an absolute panel coordinate, so every clamp below uses the panel width, never
        // the card width — clamping against the card width is what threw the strip to panel-left.
        val maxStrip = this.width - (dp(8f) * 2).toInt()
        val stripWidth = maxOf(width, (textWidth + h * 0.9f).toInt()).coerceAtMost(maxStrip)
        val stripLeft = (left + width / 2f - stripWidth / 2f).toInt()
            .coerceIn(4, (this.width - stripWidth - 4).coerceAtLeast(4))
        rect.set(stripLeft.toFloat(), top.toFloat(), (stripLeft + stripWidth).toFloat(), top + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, glassPaint)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, strokePaint)
        val draw = if (textWidth <= stripWidth - h * 0.6f) text else ellipsize(text, stripWidth - h * 0.6f, infoPaint)
        val drawWidth = paintMeasure(draw)
        val centerX = stripLeft + stripWidth / 2f
        canvas.drawText(draw, centerX - drawWidth / 2f, top + h * 0.69f, infoPaint)
    }

    private fun paintMeasure(text: String): Float = infoPaint.measureText(text)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun infoParts(next: ClusterTurnGuidance): List<String> {
        if (!showArrival) return emptyList()
        val parts = mutableListOf<String>()
        next.arrivalEpochSeconds?.takeIf { it > 0 }?.let { epoch ->
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epoch * 1000L))
            parts += context.getString(R.string.turn_card_info_arrival, time)
        }
        next.remainingSeconds?.takeIf { it > 0 }?.let { total ->
            val hours = total / 3600
            val minutes = (total % 3600) / 60
            parts += if (hours > 0) {
                context.getString(R.string.turn_card_info_duration_hm, hours.toInt(), minutes.toInt())
            } else {
                context.getString(R.string.turn_card_info_duration_m, minutes.toInt())
            }
        }
        next.remainingMeters?.takeIf { it > 0 }?.let { meters ->
            parts += context.getString(R.string.turn_card_info_km, meters / 1000.0)
        }
        return parts
    }

    /** Draws the tinted Material Symbols glyph; the roundabout exit number gets a corner badge. */
    private fun drawGlyph(canvas: Canvas, next: ClusterTurnGuidance, left: Float, top: Float, side: Float, exit: Int?) {
        val resId = glyphRes(next.icon)
        if (resId != glyphTag) {
            glyph = ContextCompat.getDrawable(context, resId)?.mutate()?.apply { setTint(Color.WHITE) }
            glyphTag = resId
        }
        val inset = side * 0.10f
        glyph?.setBounds(
            (left + inset).toInt(), (top + inset).toInt(),
            (left + side - inset).toInt(), (top + side - inset).toInt(),
        )
        glyph?.draw(canvas)
        if (exit != null) {
            val d = side * 0.42f
            val cx = left + side - d / 2f
            val cy = top + side - d / 2f
            canvas.drawCircle(cx, cy, d / 2f, badgePaint)
            badgeTextPaint.textSize = d * 0.62f
            val textY = cy - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(exit.toString(), cx, textY, badgeTextPaint)
        }
    }

    private fun glyphRes(icon: Int): Int = when (icon) {
        2 -> R.drawable.ic_turn_card_turn_left
        3 -> R.drawable.ic_turn_card_turn_right
        4 -> R.drawable.ic_turn_card_turn_slight_left
        5 -> R.drawable.ic_turn_card_turn_slight_right
        6 -> R.drawable.ic_turn_card_turn_sharp_left
        7 -> R.drawable.ic_turn_card_turn_sharp_right
        8 -> R.drawable.ic_turn_card_u_turn_left
        19 -> R.drawable.ic_turn_card_u_turn_right
        11, 12, 17, 18 -> R.drawable.ic_turn_card_roundabout_right
        15 -> R.drawable.ic_turn_card_sports_score
        else -> R.drawable.ic_turn_card_straight
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(R.string.turn_card_now)
        meters < 1000 -> context.getString(R.string.turn_card_distance_m, meters)
        else -> context.getString(
            R.string.turn_card_distance_km, meters / 100 / 10f,
        )
    }

    private fun roadLabel(next: ClusterTurnGuidance): String =
        if (next.roundaboutExit in 1..9) {
            context.getString(R.string.turn_card_exit, next.roundaboutExit)
        } else {
            next.road
        }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + ellipsis) > maxWidth) end--
        return if (end == 0) ellipsis else text.take(end) + ellipsis
    }
}
