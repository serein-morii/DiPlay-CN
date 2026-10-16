package com.shilapi.xcertplay.turncamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Synthetic test chart only. No SurfaceTexture, permission, camera or vehicle access. */
internal class TurnCameraCalibrationPreview(context: Context) : View(context) {
    var profile = TurnCameraCalibration()
        set(value) { field = value.normalized(); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize((w * 9f / 16f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        canvas.drawColor(Color.rgb(15, 22, 32))
        val p = profile.normalized()
        val left = p.windowX * (1 - p.windowWidth) * width
        val top = p.windowY * (1 - p.windowHeight) * height
        val area = RectF(left, top, left + p.windowWidth * width, top + p.windowHeight * height)
        val save = canvas.save()
        canvas.clipRect(area)
        paint.color = Color.BLACK
        canvas.drawRect(area, paint)
        canvas.translate(area.left, area.top)
        val points = floatArrayOf(0f, 0f, CHART_WIDTH, 0f, 0f, CHART_HEIGHT)
        val mapped = points.copyOf()
        for (i in 0..2) {
            val (x, y) = p.mapPoint(points[i * 2], points[i * 2 + 1], CHART_WIDTH, CHART_HEIGHT, area.width(), area.height())
            mapped[i * 2] = x; mapped[i * 2 + 1] = y
        }
        val matrix = Matrix().apply { setPolyToPoly(points, 0, mapped, 0, 3) }
        canvas.concat(matrix)
        paint.color = Color.rgb(45, 65, 70)
        canvas.drawRect(0f, 0f, CHART_WIDTH, CHART_HEIGHT, paint)
        paint.color = Color.LTGRAY
        paint.strokeWidth = 1f
        for (x in 0..8) canvas.drawLine(x * CHART_WIDTH / 8, 0f, x * CHART_WIDTH / 8, CHART_HEIGHT, paint)
        for (y in 0..4) canvas.drawLine(0f, y * CHART_HEIGHT / 4, CHART_WIDTH, y * CHART_HEIGHT / 4, paint)
        paint.color = Color.RED; canvas.drawRect(0f, 0f, 32f, 32f, paint)
        paint.color = Color.GREEN; canvas.drawRect(CHART_WIDTH - 32f, 0f, CHART_WIDTH, 32f, paint)
        paint.color = Color.BLUE; canvas.drawRect(0f, CHART_HEIGHT - 32f, 32f, CHART_HEIGHT, paint)
        paint.color = Color.YELLOW; canvas.drawRect(CHART_WIDTH - 32f, CHART_HEIGHT - 32f, CHART_WIDTH, CHART_HEIGHT, paint)
        // An asymmetric upward arrow makes rotation and mirroring visible without text.
        paint.color = Color.WHITE; paint.strokeWidth = 5f
        canvas.drawLine(160f, 135f, 160f, 45f, paint)
        canvas.drawLine(160f, 45f, 140f, 65f, paint)
        canvas.drawLine(160f, 45f, 180f, 65f, paint)
        canvas.drawLine(160f, 110f, 200f, 110f, paint)
        canvas.restoreToCount(save)
        paint.color = Color.CYAN; paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * 2
        canvas.drawRect(area, paint)
        paint.style = Paint.Style.FILL
    }

    private companion object {
        const val CHART_WIDTH = 320f
        const val CHART_HEIGHT = 180f
    }
}
