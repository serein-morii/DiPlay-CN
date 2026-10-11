package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * The car-button icon travels inside the /info reply the iPhone reads while connecting. A custom
 * icon was saved as a 1024-pixel PNG, which can be megabytes for a photo, and the iPhone stopped
 * connecting after it was replaced (#567). Send it at the size of the packaged icon instead.
 */
internal object AirPlayIconSizing {
    const val MAX_PIXELS = 192

    /** Square PNG bytes no larger than [MAX_PIXELS]; null when [encoded] is not a square image. */
    fun fit(encoded: ByteArray): Pair<Int, ByteArray>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val size = bounds.outWidth
        if (size <= 0 || size != bounds.outHeight) return null
        if (size <= MAX_PIXELS) return size to encoded
        var sample = 1
        while (size / (sample * 2) >= MAX_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(encoded, 0, encoded.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scaled = Bitmap.createScaledBitmap(decoded, MAX_PIXELS, MAX_PIXELS, true)
        if (scaled !== decoded) decoded.recycle()
        val bytes = ByteArrayOutputStream().use { output ->
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, output)) null else output.toByteArray()
        }
        scaled.recycle()
        return bytes?.let { MAX_PIXELS to it }
    }
}
