package com.shilapi.xcertplay.media

import android.content.Context
import com.shilapi.xcertplay.hud.BydCarMusicRhythm
import com.shilapi.xcertplay.hud.BydCarMusicRhythmCall
import com.shilapi.xcertplay.hud.BydCarMusicRhythmDirect
import com.shilapi.xcertplay.hud.BydCarMusicRhythmHelper
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AmbientMusicTickingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun noLampUpdateRunsWhileTheFeatureIsOff() {
        context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().clear().commit()
        val sink = AmbientMusicController.openSink(context)
        try {
            settle()
            assertNull(field("ticking"))
            AmbientMusicController.settingsChanged(context)
            settle()
            assertNull(field("ticking"))
        } finally {
            AmbientMusicController.closeSink(sink)
            settle()
        }
    }

    @Test fun theCarsOwnRhythmKeepsTheUpdateRunningWithoutLampControl() {
        context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("carRhythm", true).commit()
        val sink = AmbientMusicController.openSink(context)
        try {
            settle()
            org.junit.Assert.assertNotNull(field("ticking"))
            context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().putBoolean("carRhythm", false).commit()
            AmbientMusicController.settingsChanged(context)
            settle()
            assertNull(field("ticking"))
        } finally {
            AmbientMusicController.closeSink(sink)
            context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().clear().commit()
            settle()
        }
    }

    @Test fun bandsPlayedBetweenTwoSendsStillReachTheCar() {
        context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("carRhythm", true).putBoolean("carRhythmFeed", true).commit()
        val car = RecordingCall()
        BydCarMusicRhythm.direct = car
        BydCarMusicRhythm.helper = car
        val quiet = IntArray(16) { 1 }
        val onset = IntArray(16) { 15 }
        val envelope = AmbientMusicEnvelope().apply {
            append(0, 1_000, 0.1, bands = quiet)
            append(1_000, 500, 0.1, bands = quiet)
            append(1_500, 400, 0.9, bands = onset)
            append(1_900, 2_000, 0.1, bands = quiet)
        }
        var head = 0
        val sink = AmbientMusicController.openSink(context)
        try {
            settle()
            // The test plays the 50 ms updates itself.
            (field("ticking") as ScheduledFuture<*>).cancel(false)
            AmbientMusicController.openRenderer(sink, envelope) { head to true }
            settle()
            fun update(playedTo: Int) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(50))
                head = playedTo
                (field("executor") as ExecutorService).submit {
                    AmbientMusicController::class.java.getDeclaredMethod("carRhythm").apply { isAccessible = true }
                        .invoke(AmbientMusicController)
                }.get(2, TimeUnit.SECONDS)
                rhythmSettle()
            }
            AmbientMusicController::class.java.getDeclaredField("lastBandsMillis").apply { isAccessible = true }
                .setLong(AmbientMusicController, 0L)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
            update(1_000)
            // The onset plays between this update and the last one, and the next send is not due yet.
            update(2_000)
            update(3_000)
            assertEquals(listOf(quiet.toList(), onset.toList()), car.bands.map { it.toList() })
        } finally {
            AmbientMusicController.closeSink(sink)
            context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().clear().commit()
            AmbientMusicController.settingsChanged(context)
            settle()
            BydCarMusicRhythm.setPlaying(context, false)
            rhythmSettle()
            BydCarMusicRhythm.direct = BydCarMusicRhythmDirect
            BydCarMusicRhythm.helper = BydCarMusicRhythmHelper
        }
    }

    private class RecordingCall : BydCarMusicRhythmCall {
        val bands = mutableListOf<IntArray>()
        override fun supported(context: Context) = true
        override fun start(context: Context) = true
        override fun stop(context: Context) = Unit
        override fun sendBands(context: Context, bands: IntArray): Boolean = this.bands.add(bands)
    }

    private fun rhythmSettle() {
        (BydCarMusicRhythm::class.java.getDeclaredField("executor").apply { isAccessible = true }
            .get(BydCarMusicRhythm) as ExecutorService).submit {}.get(2, TimeUnit.SECONDS)
    }

    private fun settle() {
        (field("executor") as ExecutorService).submit {}.get(2, TimeUnit.SECONDS)
    }

    private fun field(name: String): Any? = AmbientMusicController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(AmbientMusicController)
}
