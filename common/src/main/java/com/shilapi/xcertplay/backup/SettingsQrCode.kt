package com.shilapi.xcertplay.backup

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Renders backup URLs as QR codes on a rounded card. */
object SettingsQrCode {
    fun encode(content: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val radius = sizePx / 10f
        val half = sizePx / 2f - radius
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                if (matrix.get(x, y)) {
                    pixels[y * sizePx + x] = Color.BLACK
                    continue
                }
                val qx = abs(x + 0.5f - sizePx / 2f) - half
                val qy = abs(y + 0.5f - sizePx / 2f) - half
                val ox = max(qx, 0f)
                val oy = max(qy, 0f)
                val d = sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - radius
                val alpha = ((0.5f - d).coerceIn(0f, 1f) * 255f).toInt()
                pixels[y * sizePx + x] = alpha shl 24 or 0x00FFFFFF
            }
        }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }
}
