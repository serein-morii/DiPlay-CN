package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Optional local black corner fill for firmware that shows a wallpaper colour through CarPlay's
 * rounded corners (#635). This is NOT an iPhone-provided corner mask: its radius is user-selected.
 *
 * Draw above either TextureView or SurfaceView output, below touch handling and in-car menus.
 * A picture fitted into a letterbox uses [content] rather than the entire Activity window.
 */
internal class CarPlayBlackCornerOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val content = RectF()
    private val ring = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var iphoneMask: IphoneCornerMask? = null

    var radiusDp: Int = 0
        set(value) {
            field = value.coerceIn(0, MAX_RADIUS_DP)
            invalidate()
        }

    fun setContentBounds(bounds: RectF) {
        content.set(bounds)
        invalidate()
    }

    fun setIphoneMask(mask: IphoneCornerMask?) {
        val old = iphoneMask
        iphoneMask = mask
        invalidate()
        if (old !== mask) old?.blackPixels?.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val mask = iphoneMask
        if (mask != null && !content.isEmpty && mask.displayWidth > 0) {
            val side = (content.width() * mask.size / mask.displayWidth).coerceAtMost(
                minOf(content.width(), content.height()) / 2f)
            if (side > 0f) {
                for (mirrorX in listOf(false, true)) for (mirrorY in listOf(false, true)) {
                    canvas.save()
                    canvas.translate(if (mirrorX) content.right else content.left,
                        if (mirrorY) content.bottom else content.top)
                    canvas.scale(if (mirrorX) -1f else 1f, if (mirrorY) -1f else 1f)
                    canvas.drawBitmap(mask.blackPixels, null, RectF(0f, 0f, side, side), bitmapPaint)
                    canvas.restore()
                }
            }
            return
        }
        if (radiusDp == 0 || content.isEmpty) return
        val radius = (radiusDp * resources.displayMetrics.density)
            .coerceAtMost(minOf(content.width(), content.height()) / 2f)
        if (radius <= 0f) return
        ring.reset()
        ring.fillType = Path.FillType.EVEN_ODD
        ring.addRect(content, Path.Direction.CW)
        ring.addRoundRect(content, radius, radius, Path.Direction.CW)
        canvas.drawPath(ring, paint)
    }

    companion object {
        const val MAX_RADIUS_DP = 48
        /** Opt-in native mask mode; a local radius of 24dp is shown until iOS sends the mask. */
        const val IPHONE_MASK = -1
        val RADII_DP = listOf(0, 12, 24, 36, 48, IPHONE_MASK)
    }
}
