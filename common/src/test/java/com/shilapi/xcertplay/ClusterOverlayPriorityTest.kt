package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.CarPlayNowPlaying
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterOverlayPriorityTest {
    @Test fun occupiedRegionSuppressesArtworkInParentCoordinatesWithoutRenewingExpiry() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        shadowOf(Looper.getMainLooper()).idle()
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val root = FrameLayout(controller.get())
            val music = ClusterMusicView(controller.get())
            val reserved = object : View(controller.get()), ClusterOverlayBounds {
                override fun occupiedBounds() = RectF(380f, 140f, 460f, 220f)
            }
            root.addView(music)
            root.addView(reserved)
            controller.get().setContentView(root)
            ClusterMusicSettings.saveEnabled(context, true)
            ClusterMusicState.publish(CarPlayNowPlaying(title = "Track"), null)
            shadowOf(Looper.getMainLooper()).idle()
            root.layout(0, 0, 840, 240)
            music.layout(100, 0, 740, 240)
            reserved.layout(0, 0, 840, 240)
            val bitmap = Bitmap.createBitmap(640, 240, Bitmap.Config.ARGB_8888)
            try {
                music.draw(Canvas(bitmap))
                assertEquals(0, Color.alpha(bitmap.getPixel(320, 180)))
                reserved.visibility = View.GONE
                music.draw(Canvas(bitmap))
                assertTrue(Color.alpha(bitmap.getPixel(320, 180)) > 0)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
                assertEquals(View.GONE, music.visibility)
            } finally { bitmap.recycle() }
        } finally { controller.pause().stop().destroy() }
    }
}
