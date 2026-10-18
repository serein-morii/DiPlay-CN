package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * iOS's `topLeftCornerMask` is a square 8-bit grayscale PNG: luminance is coverage
 * (0 = hide image, 255 = keep). The accessory fills the cut portions in black (#635).
 * Decode off the UI and RTSP control threads; the returned bitmap belongs to its overlay.
 */
internal class IphoneCornerMask private constructor(
    val blackPixels: Bitmap,
    val size: Int,
    val displayWidth: Int,
) {
    companion object {
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        const val MAX_PNG_BYTES = 512 * 1024
        const val MAX_SIDE = 512

        fun decode(png: ByteArray, displayWidth: Int): IphoneCornerMask? {
            if (displayWidth !in 64..8192 || png.size !in 45..MAX_PNG_BYTES) return null
            if (!PNG_MAGIC.indices.all { png[it] == PNG_MAGIC[it] }) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            if (bounds.outWidth !in 2..MAX_SIDE || bounds.outHeight != bounds.outWidth) return null
            val source = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return null
            return try {
                val side = source.width
                if (source.height != side || side < 2 || side > MAX_SIDE) return null
                val pixels = IntArray(side * side)
                source.getPixels(pixels, 0, side, 0, 0, side, side)
                // The true 8-bit grayscale image carries coverage in its luminance, not alpha.
                if (pixels.any { Color.red(it) != Color.green(it) || Color.red(it) != Color.blue(it) }) return null
                val tl = Color.red(pixels[0])
                val tr = Color.red(pixels[side - 1])
                val bl = Color.red(pixels[(side - 1) * side])
                val br = Color.red(pixels[side * side - 1])
                val flipX = minOf(tr, br) < minOf(tl, bl)
                val flipY = minOf(bl, br) < minOf(tl, tr)
                val output = IntArray(side * side)
                for (y in 0 until side) for (x in 0 until side) {
                    val sx = if (flipX) side - 1 - x else x
                    val sy = if (flipY) side - 1 - y else y
                    val coverage = Color.red(pixels[sy * side + sx])
                    output[y * side + x] = Color.argb(255 - coverage, 0, 0, 0)
                }
                val bitmap = Bitmap.createBitmap(output, side, side, Bitmap.Config.ARGB_8888)
                IphoneCornerMask(bitmap, side, displayWidth)
            } finally {
                source.recycle()
            }
        }
    }
}
