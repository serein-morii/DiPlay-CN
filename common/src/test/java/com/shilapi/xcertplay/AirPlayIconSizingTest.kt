package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirPlayIconSizingTest {
    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Noise keeps the PNG large, like a cropped photo.
        for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, Color.rgb((x * 31 + y * 17) % 256, (x * y) % 256, (x + y * 7) % 256))
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    @Test fun aCroppedPhotoIconIsSentAtThePackagedIconSize() {
        val large = png(1024, 1024)
        val (size, bytes) = AirPlayIconSizing.fit(large)!!
        assertEquals(AirPlayIconSizing.MAX_PIXELS, size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(192, bounds.outWidth)
        assertEquals(192, bounds.outHeight)
        assertTrue("${bytes.size} >= ${large.size}", bytes.size < large.size)
    }

    @Test fun smallIconsAreSentUnchangedAndNonSquareImagesAreRejected() {
        val small = png(128, 128)
        assertSame(small, AirPlayIconSizing.fit(small)!!.second)
        assertNull(AirPlayIconSizing.fit(png(200, 100)))
        assertNull(AirPlayIconSizing.fit(byteArrayOf(1, 2, 3)))
    }
}
