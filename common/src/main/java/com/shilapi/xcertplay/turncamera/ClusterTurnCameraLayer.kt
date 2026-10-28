package com.shilapi.xcertplay.turncamera

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.ClusterOverlayBounds
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

/** A child of DiPlay's existing cluster content, NOT a new full-screen window.
 * Instrument/status/navigation siblings remain above it. Placement is constrained to
 * the existing map viewport AND the user's explicitly confirmed safe area. */
internal class ClusterTurnCameraLayer(
    private val root: FrameLayout,
    private val viewport: () -> RectF,
    private val safeArea: List<Float>,
    private val quarantine: () -> Unit,
) : FrameLayout(root.context), TurnCameraSurfacePort<Surface>, TurnCameraPresenter, ClusterOverlayBounds {
    private class Entry(val lease: CameraLease, val route: TurnCameraRoute, val frame: FrameLayout,
                        val texture: TextureView, val shutter: View, val lost: () -> Unit) {
        var source: SurfaceTexture? = null
        var surface: Surface? = null
        var released = false
        var shown = false
    }
    private var entry: Entry? = null
    private var area: RectF? = null
    init { clipChildren = true; clipToPadding = true; isClickable = false }

    override fun prepare(lease: CameraLease, route: TurnCameraRoute, ready: (Surface) -> Unit,
                         frame: (Long) -> Unit, lost: () -> Unit) {
        check(entry == null && isAttachedToWindow)
        val texture = TextureView(context).apply { isOpaque = false }
        // Keep the TextureView participating in drawing while waiting for its real
        // first update. alpha=0 on an ancestor may suppress updateTexImage entirely,
        // preventing the callback that is required to reveal it. An opaque shutter
        // hides all camera pixels without disabling the texture's draw lifecycle.
        val shutter = View(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        val box = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(texture, LayoutParams(-1, -1))
            addView(shutter, LayoutParams(-1, -1))
        }
        val e = Entry(lease, route, box, texture, shutter, lost)
        entry = e
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(source: SurfaceTexture, width: Int, height: Int) {
                if (entry !== e || e.released) return
                e.source = source
                source.setDefaultBufferSize(route.width, route.height)
                applyTransform(e)
                val surface = Surface(source)
                e.surface = surface
                ready(surface)
            }
            override fun onSurfaceTextureSizeChanged(source: SurfaceTexture, width: Int, height: Int) = applyTransform(e)
            override fun onSurfaceTextureUpdated(source: SurfaceTexture) {
                if (entry === e && !e.released) frame(source.timestamp)
            }
            override fun onSurfaceTextureDestroyed(source: SurfaceTexture): Boolean {
                // Returning false retains it until the owning worker confirms native close.
                if (entry === e && !e.released) lost()
                return false
            }
        }
        addView(box)
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyTransform(e) }
        if (!place(e)) { lost() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        entry?.let { if (!place(it)) it.lost() }
    }
    private fun place(e: Entry): Boolean {
        val v = viewport()
        if (v.width() <= 0 || v.height() <= 0 || v.left < 0 || v.top < 0 || v.right > width || v.bottom > height) return false
        val safe = RectF(v.left + safeArea[0] * v.width(), v.top + safeArea[1] * v.height(),
            v.left + safeArea[2] * v.width(), v.top + safeArea[3] * v.height())
        val p = e.route.calibration.normalized()
        // Round INWARD: even a sub-pixel safe area must not spill onto instruments.
        val left = kotlin.math.ceil(safe.left).toInt(); val top = kotlin.math.ceil(safe.top).toInt()
        val right = kotlin.math.floor(safe.right).toInt(); val bottom = kotlin.math.floor(safe.bottom).toInt()
        if (right <= left || bottom <= top) return false
        val w = (p.windowWidth * (right - left)).roundToInt().coerceIn(1, right - left)
        val h = (p.windowHeight * (bottom - top)).roundToInt().coerceIn(1, bottom - top)
        val x = left + (p.windowX * (right - left - w)).roundToInt()
        val y = top + (p.windowY * (bottom - top - h)).roundToInt()
        area = RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        e.frame.layoutParams = LayoutParams(w, h, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = x; topMargin = y
        }
        return clearOfPriorityOverlays()
    }

    fun clearOfPriorityOverlays(): Boolean {
        val rect = area ?: return true
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child === this || child.visibility != View.VISIBLE) continue
            // Only navigation/speed safety overlays are above camera priority. Music
            // yields to this layer via ClusterOverlayBounds, rather than blocking it.
            if (child !is com.shilapi.xcertplay.ClusterTurnCardView && child !is com.shilapi.xcertplay.ClusterSpeedometerView) continue
            val bounds = (child as? ClusterOverlayBounds)?.occupiedBounds() ?: continue
            bounds.offset(child.x - x, child.y - y)
            if (RectF.intersects(rect, bounds)) return false
        }
        return true
    }
    private fun applyTransform(e: Entry) {
        val w = e.texture.width.toFloat(); val h = e.texture.height.toFloat()
        if (w <= 0 || h <= 0 || e.released) return
        val src = floatArrayOf(0f, 0f, w, 0f, 0f, h)
        val dst = FloatArray(6)
        val original = floatArrayOf(0f, 0f, e.route.width.toFloat(), 0f, 0f, e.route.height.toFloat())
        for (i in 0..2) {
            val point = e.route.calibration.mapPoint(original[i * 2], original[i * 2 + 1],
                e.route.width.toFloat(), e.route.height.toFloat(), w, h)
            dst[i * 2] = point.first; dst[i * 2 + 1] = point.second
        }
        e.texture.setTransform(Matrix().apply { setPolyToPoly(src, 0, dst, 0, 3) })
    }
    override fun show(side: TurnCameraSide, lease: CameraLease) {
        val e = entry?.takeIf { it.lease == lease && !it.released } ?: return
        check(clearOfPriorityOverlays())
        e.shown = true; e.shutter.visibility = View.GONE
        invalidateMusic()
    }
    override fun hide(reason: TurnCameraPolicy.Reason) { entry?.let { hide(it.lease) } }
    override fun hide(lease: CameraLease) {
        entry?.takeIf { it.lease == lease }?.let { it.shown = false; it.shutter.visibility = View.VISIBLE }
        invalidateMusic()
    }
    override fun occupiedBounds(): RectF? = if (entry?.shown == true) area?.let(::RectF) else null
    override fun dispose(lease: CameraLease, confirmed: Boolean) {
        val e = entry?.takeIf { it.lease == lease } ?: return
        e.shown = false; e.released = true; e.shutter.visibility = View.VISIBLE
        entry = null; area = null
        removeView(e.frame)
        if (confirmed) {
            e.surface?.release(); e.source?.release()
        } else {
            // One experimental host is admitted process-wide. Retain this bounded
            // resource bundle until restart; never free a surface under vendor code.
            retained.add(e)
            quarantine()
        }
        invalidateMusic()
    }
    private fun invalidateMusic() { for (i in 0 until root.childCount) root.getChildAt(i).invalidate() }
    private companion object { val retained = CopyOnWriteArrayList<Any>() }
}
