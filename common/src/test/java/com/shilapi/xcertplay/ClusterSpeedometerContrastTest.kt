package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import androidx.core.graphics.ColorUtils
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterSpeedometerContrastTest {
    @Test fun automaticContrastTracksEveryFadeFrameAndRestoresOriginalTextWhenDisabled() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        shadowOf(Looper.getMainLooper()).idle()
        AirPlayPersistence.saveClusterSpeedometerEnabled(context, true)
        AirPlayPersistence.saveClusterSpeedometerAlbumColor(context, true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        var subscriptions = 0
        val view = ClusterSpeedometerView(controller.get()) { subscriptions++; { } }
        controller.get().setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        view.layout(0, 0, 1280, 480)
        view.setSpeedKmh(188)
        val bitmap = Bitmap.createBitmap(1280, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val speed = ReflectionHelpers.getField<Paint>(view, "speedPaint")
        val unit = ReflectionHelpers.getField<Paint>(view, "unitPaint")
        val background = ReflectionHelpers.getField<Paint>(view, "backgroundPaint")
        val directory = File("build/reports/cluster-speedometer-contrast").apply { mkdirs() }
        fun render() {
            canvas.drawColor(Color.rgb(232, 235, 239))
            view.draw(canvas)
        }
        fun verifyContrast() {
            render()
            assertTrue(speed.color == Color.BLACK || speed.color == Color.WHITE)
            assertEquals(speed.color, unit.color)
            // The actual map is a separate surface: validate contrast against card RGB,
            // without claiming a guarantee for arbitrary content behind transparency.
            val opaque = ColorUtils.setAlphaComponent(background.color, 255)
            assertTrue(ColorUtils.calculateContrast(speed.color, opaque) >= 4.5)
        }
        try {
            for ((name, color, expected) in listOf(
                Triple("yellow", Color.YELLOW, Color.BLACK),
                Triple("dark-blue", Color.rgb(10, 20, 65), Color.WHITE),
                Triple("beige", Color.rgb(226, 192, 156), Color.BLACK),
                Triple("black", Color.BLACK, Color.WHITE),
                Triple("white", Color.WHITE, Color.BLACK),
            )) {
                val artwork = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                ClusterMusicState.publish(CarPlayNowPlaying(title = name), artwork)
                shadowOf(Looper.getMainLooper()).idle()
                verifyContrast()
                repeat(25) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                    verifyContrast()
                }
                assertEquals(expected, speed.color)
                File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            for (opacity in listOf(20, 40, 85, 100)) {
                AirPlayPersistence.saveClusterSpeedometerOpacity(context, opacity)
                shadowOf(Looper.getMainLooper()).idle()
                verifyContrast()
                assertEquals(opacity * 255 / 100, Color.alpha(background.color))
                assertEquals(Color.BLACK, speed.color)
                assertEquals(Color.BLACK, unit.color)
            }
            AirPlayPersistence.saveClusterSpeedometerAlbumColor(context, false)
            shadowOf(Looper.getMainLooper()).idle()
            render()
            assertEquals(Color.WHITE, speed.color)
            assertEquals(Color.argb(210, 220, 220, 225), unit.color)
            AirPlayPersistence.saveClusterSpeedometerAlbumColor(context, true)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            verifyContrast()
            assertEquals(Color.BLACK, speed.color)
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            verifyContrast()
            assertEquals(Color.WHITE, speed.color)
            assertEquals(1, subscriptions)
        } finally {
            controller.pause().stop().destroy()
            bitmap.recycle()
            ClusterMusicState.publish(CarPlayNowPlaying(), null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
