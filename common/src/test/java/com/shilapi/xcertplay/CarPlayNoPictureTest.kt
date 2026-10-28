package com.shilapi.xcertplay

import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.File
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * #975, #249: the iPhone opened the CarPlay screen stream and played audio, but sent only keepalives on the
 * screen stream, never a frame, until it was restarted. The CarPlay screen stayed black and said nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayNoPictureTest {
    @get:Rule val folder = TemporaryFolder()
    private val hosts = mutableListOf<CarPlayHostActivity>()
    private val sinks = mutableListOf<AndroidMediaSink>()
    private lateinit var log: File

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        log = folder.newFile("diplay.log")
    }

    @After fun tearDown() {
        for (host in hosts) {
            ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        }
        sinks.forEach { it.close() }
        sinks.forEach { it.awaitVideoReleased(5_000) }
        CarPlayBackgroundSession.clear()
    }

    @Test fun tenSecondsOfKeepalivesAloneAskToRestartTheIphone() {
        val host = host()
        val sink = sessionSink(host)
        sink.onScreenStreamActive(MAIN_SCREEN, true)
        idle(9_900)
        assertPictureUncovered(host)
        idle(100)
        assertAsksToRestartTheIphone(host)
        idle(30_000)
        assertEquals("one report line per stream", 1, reportLines())
    }

    @Test fun aFirstFrameInTimeSaysNothing() {
        val host = host()
        val sink = sessionSink(host)
        sink.onScreenStreamActive(MAIN_SCREEN, true)
        idle(300)
        frame(sink)
        idle(60_000)
        assertPictureUncovered(host)
        assertEquals(0, reportLines())
    }

    @Test fun aLateFirstFrameTakesTheTextAway() {
        val host = host()
        val sink = sessionSink(host)
        sink.onScreenStreamActive(MAIN_SCREEN, true)
        idle(10_000)
        assertAsksToRestartTheIphone(host)
        frame(sink)
        idle(300)
        assertPictureUncovered(host)
    }

    @Test fun whenTheRestartedIphoneLeavesTheScreenSaysItReconnects() {
        val host = host()
        val sink = sessionSink(host)
        sink.onScreenStreamActive(MAIN_SCREEN, true)
        idle(10_000)
        assertAsksToRestartTheIphone(host)
        ReflectionHelpers.callInstanceMethod<AirPlaySessionListener>(host, "createSessionListener",
            ClassParameter.from(Int::class.javaPrimitiveType, ReflectionHelpers.getField<Int>(host, "restartGeneration")),
        ).onSessionEnded(mock(AirPlaySession::class.java))
        idle(0)
        assertEquals(View.VISIBLE, panel(host).visibility)
        assertEquals(host.getString(R.string.reconnecting_to_your_iphone), stage(host))
    }

    @Test fun aStillScreenAfterThePictureCameIsNeverTakenForAStuckIphone() {
        val host = host()
        val sink = sessionSink(host)
        sink.onScreenStreamActive(MAIN_SCREEN, true)
        frame(sink)
        idle(60_000)
        assertPictureUncovered(host)
        // The iPhone sets the screen stream up again within the same session, and a still screen sends no frame.
        sink.onScreenStreamActive(MAIN_SCREEN, true)
        idle(60_000)
        assertPictureUncovered(host)
        assertEquals(0, reportLines())
    }

    @Test fun aHostThatTakesOverASessionWhosePictureCameSaysNothing() {
        val sink = AndroidMediaSink().also(sinks::add)
        sink.onScreenStreamActive(MAIN_SCREEN, true)
        frame(sink)
        val host = adoptingHost(sink)
        idle(60_000)
        assertPictureUncovered(host)
    }

    @Test fun aHostThatTakesOverASessionWithoutAPictureAsksToRestartTheIphone() {
        val sink = AndroidMediaSink().also(sinks::add)
        sink.onScreenStreamActive(MAIN_SCREEN, true)
        val host = adoptingHost(sink)
        idle(10_000)
        assertAsksToRestartTheIphone(host)
    }

    private fun assertAsksToRestartTheIphone(host: CarPlayHostActivity) {
        assertEquals("the waiting screen covers the black video", View.VISIBLE, panel(host).visibility)
        assertEquals(host.getString(R.string.iphone_sent_no_picture), stage(host))
    }

    private fun assertPictureUncovered(host: CarPlayHostActivity) {
        assertEquals("stage=${stage(host)}", View.GONE, panel(host).visibility)
    }

    private fun host(): CarPlayHostActivity {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        hosts += host
        ReflectionHelpers.callInstanceMethod<View>(host, "buildContentView")
        ReflectionHelpers.setField(host, "sessionLog", SessionLogFile(log))
        return host
    }

    /** The sink the host builds for a new session, whose screen callbacks reach that host. */
    private fun sessionSink(host: CarPlayHostActivity): AndroidMediaSink {
        val sink = ReflectionHelpers.callInstanceMethod<AndroidMediaSink>(host, "createMediaSink",
            ClassParameter.from(Int::class.javaPrimitiveType, 1280),
            ClassParameter.from(Int::class.javaPrimitiveType, 720),
            ClassParameter.from(Int::class.javaPrimitiveType, ReflectionHelpers.getField<Int>(host, "restartGeneration")),
        )
        sinks += sink
        ReflectionHelpers.setField(host, "sink", sink)
        return sink
    }

    /** A new CarPlay screen taking over a session that kept running without one. */
    private fun adoptingHost(sink: AndroidMediaSink): CarPlayHostActivity {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayBackgroundSession.store(controller, sink, 1280, 720, Any(),
            CarPlaySessionDisplay(1280, 720, Surface.ROTATION_0, false, false, 1280, 720)) {}
        val host = host()
        ReflectionHelpers.setField(host, "smoothVideo", false)
        assertTrue(ReflectionHelpers.callInstanceMethod<Boolean>(host, "adoptBackgroundSession"))
        return host
    }

    private fun frame(sink: AndroidMediaSink) =
        sink.onVideoFrame(MAIN_SCREEN, byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte()), 1L, System.nanoTime())

    private fun panel(host: CarPlayHostActivity) = ReflectionHelpers.getField<View>(host, "connectionPanel")

    private fun stage(host: CarPlayHostActivity) =
        ReflectionHelpers.getField<TextView>(host, "stageStatusView").text.toString()

    private fun reportLines(): Int {
        hosts.forEach { assertTrue(ReflectionHelpers.getField<SessionLogFile>(it, "sessionLog").awaitIdle(2_000)) }
        return log.readLines().count { "Screen stream open 10 s with no video frame" in it }
    }

    private fun idle(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private companion object {
        const val MAIN_SCREEN = 110
    }
}
