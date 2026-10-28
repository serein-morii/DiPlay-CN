package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
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
class ClusterMusicTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    @Before fun clear() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        idle()
    }

    @Test fun temporaryExpiresDespitePositionAndLateArtworkThenNewTrackShowsAgain() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view)
        assertFalse(ClusterMusicSettings.enabled(context))
        ClusterMusicSettings.saveEnabled(context, true)
        val track = CarPlayNowPlaying(title = "Song", artist = "Artist", playing = true)
        ClusterMusicState.publish(track, null); idle()
        assertEquals(View.VISIBLE, view.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        ClusterMusicState.publish(track.copy(elapsedMillis = 6_000), null); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(View.GONE, view.visibility)
        val art = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        ClusterMusicState.publish(track.copy(artworkTransferId = 7), art); idle()
        assertEquals(View.GONE, view.visibility)
        ClusterMusicState.publish(track.copy(title = "Next song"), null); idle()
        assertEquals(View.VISIBLE, view.visibility)
        ClusterMusicState.publish(CarPlayNowPlaying(), null); idle()
        assertEquals(View.GONE, view.visibility)
        controller.pause().stop().destroy()
    }

    @Test fun permanentSurvivesPauseButDisableAndDisconnectClearItAndReattachKeepsDeadline() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        val track = CarPlayNowPlaying(title = "Paused song", artist = "Artist", playing = false)
        ClusterMusicState.publish(track, null); idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(view, "captionVisible"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(View.VISIBLE, view.visibility)
        assertFalse(ReflectionHelpers.getField<Boolean>(view, "captionVisible"))
        ClusterMusicState.publish(track.copy(artworkTransferId = 7), Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)); idle()
        assertFalse(ReflectionHelpers.getField<Boolean>(view, "captionVisible"))
        ClusterMusicState.publish(track.copy(title = "New song"), null); idle()
        assertTrue(ReflectionHelpers.getField<Boolean>(view, "captionVisible"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(View.VISIBLE, view.visibility)
        assertFalse(ReflectionHelpers.getField<Boolean>(view, "captionVisible"))
        ClusterMusicSettings.saveEnabled(context, false); idle()
        assertEquals(View.GONE, view.visibility)
        ClusterMusicSettings.saveEnabled(context, true); idle()
        assertEquals(View.VISIBLE, view.visibility)
        ClusterMusicState.publish(CarPlayNowPlaying(), null); idle()
        assertEquals(View.GONE, view.visibility)
        ClusterMusicSettings.savePermanent(context, false); idle()
        ClusterMusicState.publish(track, null); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        val replacement = ClusterMusicView(controller.get())
        controller.get().setContentView(replacement); idle()
        assertEquals(View.GONE, replacement.visibility)
        controller.pause().stop().destroy()
    }

    @Test fun sameMillisecondTrackChangesHaveDistinctRevisionAndSettingsClamp() {
        val captured = mutableListOf<ClusterMusicState.Snapshot>()
        val stop = ClusterMusicState.observe { captured.add(it) }
        ClusterMusicState.publish(CarPlayNowPlaying(title = "A"), null)
        ClusterMusicState.publish(CarPlayNowPlaying(title = "B"), null); idle()
        assertNotEquals(captured[captured.lastIndex - 1].revision, captured.last().revision)
        stop()
        ClusterMusicSettings.save(context, "x", 999)
        ClusterMusicSettings.save(context, "y", -5)
        ClusterMusicSettings.save(context, "size", 999)
        assertEquals(95, ClusterMusicSettings.x(context))
        assertEquals(5, ClusterMusicSettings.y(context))
        assertEquals(300, ClusterMusicSettings.size(context))
    }

    @Test fun rendersRealSettingsCompactFullAndRtlWithLargeFontsAndClusterCard() {
        for ((locale, width) in listOf("es" to 600, "es" to 1280, "ar" to 600)) {
            RuntimeEnvironment.setQualifiers("$locale-w${width}dp-h960dp")
            val old = Configuration(context.resources.configuration)
            context.resources.updateConfiguration(Configuration(old).apply { fontScale = 1.5f }, context.resources.displayMetrics)
            ClusterMusicSettings.saveEnabled(context, true)
            val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
                Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
            val activity = controller.get()
            activity.applicationInfo.flags = activity.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(20, 27, 38))
                layoutDirection = if (locale == "ar") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            }
            ReflectionHelpers.callInstanceMethod<Unit>(activity, "clusterMusicControls",
                ReflectionHelpers.ClassParameter(LinearLayout::class.java, card))
            val px = (width * context.resources.displayMetrics.density).toInt()
            card.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            card.layout(0, 0, card.measuredWidth, card.measuredHeight)
            for (i in 0 until card.childCount) assertTrue(card.getChildAt(i).height > 0)
            write(card, "$locale-$width-settings")
            controller.pause().stop().destroy()
            context.resources.updateConfiguration(old, context.resources.displayMetrics)
        }
        RuntimeEnvironment.setQualifiers("es")
        ClusterMusicSettings.savePermanent(context, true)
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val card = ClusterMusicView(controller.get())
        controller.get().setContentView(card)
        val art = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(92, 70, 164)) }
        ClusterMusicState.publish(CarPlayNowPlaying(title = "Canción de prueba", artist = "Artista de prueba"), art); idle()
        card.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, 1280, 480)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        write(card, "cluster-1280x480")
        ClusterMusicSettings.save(context, "size", 300); idle()
        write(card, "cluster-1280x480-size-300")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(View.VISIBLE, card.visibility)
        assertFalse(ReflectionHelpers.getField<Boolean>(card, "captionVisible"))
        write(card, "cluster-1280x480-cover-only")
        controller.pause().stop().destroy()
    }

    @Test fun squareMatchesDefaultSpeedometerHeightAndFitsNarrowProjection() {
        val speed = com.shilapi.xcertplay.airplay.ClusterSpeedometerOverlay.rect(1280, 480, 82, 30, 50)
        assertEquals(speed.height.toFloat(), ClusterMusicView.tileSide(1280, 480, 100), 0.01f)
        assertTrue(ClusterMusicView.tileSide(60, 200, 150) <= 60f)
    }

    @Test fun missingCoverKeepsLastImageUntilReplacementAndDisconnectClearsIt() {
        val states = mutableListOf<ClusterMusicState.Snapshot>()
        val stop = ClusterMusicState.observe { states.add(it) }
        val art = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val nextArt = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val song = CarPlayNowPlaying(title = "Song", artist = "Artist", artworkTransferId = 7)
        ClusterMusicState.publish(song, art); idle()
        ClusterMusicState.publish(song.copy(elapsedMillis = 90_000), null); idle()
        assertSame(art, states.last().artwork)
        ClusterMusicState.publish(song.copy(title = "Next", artworkTransferId = 8), null); idle()
        assertSame(art, states.last().artwork)
        ClusterMusicState.publish(song.copy(title = "Next", artworkTransferId = 8), nextArt); idle()
        assertSame(nextArt, states.last().artwork)
        ClusterMusicState.publish(CarPlayNowPlaying(), null); idle()
        assertNull(states.last().artwork)
        stop()
    }

    @Test fun coverCrossfadeHasIntermediatePixelsAndPositionUpdatesDoNotRestartIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = ClusterMusicView(controller.get())
        controller.get().setContentView(view)
        ClusterMusicSettings.saveEnabled(context, true)
        ClusterMusicSettings.savePermanent(context, true)
        view.layout(0, 0, 1280, 480)
        val red = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val blue = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val song = CarPlayNowPlaying(title = "A", artworkTransferId = 7)
        ClusterMusicState.publish(song, red); idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        fun pixel(y: Int = 400): Int {
            view.layout(0, 0, 1280, 480)
            val image = Bitmap.createBitmap(1280, 480, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            return image.getPixel(640, y).also { image.recycle() }
        }
        assertEquals(Color.RED, pixel())
        ClusterMusicState.publish(song.copy(title = "B", artworkTransferId = 8), blue); idle()
        val start = ReflectionHelpers.getField<Long>(view, "artworkChangedAt")
        val beginning = pixel() // The first simulated display frame may already be 16 ms in.
        assertTrue(Color.red(beginning) > 230)
        assertTrue(Color.blue(beginning) < 25)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        ClusterMusicState.publish(song.copy(title = "B", artworkTransferId = 8, elapsedMillis = 200), null); idle()
        assertEquals(start, ReflectionHelpers.getField<Long>(view, "artworkChangedAt"))
        val middle = pixel()
        assertTrue(Color.red(middle) in 80..180)
        assertTrue(Color.blue(middle) in 80..180)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals(Color.BLUE, pixel())
        assertNull(ReflectionHelpers.getField<Bitmap?>(view, "previousArtwork"))
        assertTrue(Color.blue(pixel(300)) < 100) // Caption's dark band is still visible.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
            start + 7_800 - android.os.SystemClock.elapsedRealtime()))
        val fadingCaption = Color.blue(pixel(300))
        assertTrue("caption must have intermediate opacity: $fadingCaption", fadingCaption in 100..245)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(Color.BLUE, pixel(300)) // Title faded away; the cover did not.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(Color.BLUE, pixel())
        controller.pause().stop().destroy()
    }

    @Test fun sizeAndPositionPersistSinglePercentStepsAndLargeCardFits() {
        ClusterMusicSettings.save(context, "size", 251)
        ClusterMusicSettings.save(context, "x", 51)
        ClusterMusicSettings.save(context, "y", 74)
        assertEquals(251, ClusterMusicSettings.size(context))
        assertEquals(51, ClusterMusicSettings.x(context))
        assertEquals(74, ClusterMusicSettings.y(context))
        assertEquals(432f, ClusterMusicView.tileSide(1280, 480, 300), .01f)
        assertEquals(60f, ClusterMusicView.tileSide(60, 200, 300), .01f)
    }

    private fun write(view: View, name: String) {
        assertTrue(view.width > 0 && view.height > 0)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val directory = File("build/reports/cluster-music-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
