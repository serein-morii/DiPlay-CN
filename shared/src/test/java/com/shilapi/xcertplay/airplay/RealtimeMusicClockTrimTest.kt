package com.shilapi.xcertplay.airplay

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import com.shilapi.xcertplay.media.AudioChannelMapper
import com.shilapi.xcertplay.media.AudioFocusCoordinator
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.media.MediaClockTrim
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSystemClock

/**
 * A BYD DiLink 3.0 (#1068, #976) gets the iPhone's music in Wi-Fi bursts 400 ms apart and its DAC plays 0.08 % slow.
 * Playback starts partway through a burst and the rest of the burst lands on top.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [RealtimeMusicClockTrimTest.HeadUnitTrack::class],
    // The renderer times its trim with System.nanoTime(); instrumented, it reads Robolectric's clock.
    instrumentedPackages = ["com.shilapi.xcertplay.media"])
class RealtimeMusicClockTrimTest {
    @Before @After fun forgetLearnedTrim() { MediaClockTrim.learnedTrim = 0.0 }

    @Test fun aDiLinkKeepsTheIphonesRateFromTheStartWithTheDefaultBuffer() = keepsTheRate(Car(startMs = 300))

    @Test fun aDiLinkKeepsTheIphonesRateFromTheStartWith500Ms() = keepsTheRate(Car(startMs = 500))

    @Test fun aDiLinkKeepsTheIphonesRateFromTheStartWithTheLongestBuffer() = keepsTheRate(Car(startMs = 1000))

    /**
     * #1074's head unit gets its bursts 430 ms apart, give or take 35 ms. With the longest buffer most of them find
     * the track full, so they land while a write is blocked. The seed is the network's timing.
     */
    @Test fun burstsThatFindTheTrackFullKeepTheIphonesRateWithTheLongestBuffer() =
        keepsTheRate(Car(startMs = 1000, burstMs = 430, jitterMs = 35, seed = 9), seconds = 240)

    @Test fun burstsThatFindTheTrackFullKeepTheIphonesRateWith500Ms() =
        keepsTheRate(Car(startMs = 500, burstMs = 430, jitterMs = 35, seed = 6), seconds = 240)

    @Test fun steadyArrivalsKeepTheIphonesRateFromTheStart() =
        keepsTheRate(Car(startMs = 300, drift = 0.0, burstMs = 21, jitterMs = 0))

    @Test fun theNextStreamOnAFastHeadUnitStartsAtTheDriftTheLastOneLearned() =
        keepsTheRate(Car(startMs = 300, drift = 0.0076, burstMs = 7, jitterMs = 0, gapChancePerMs = 0.0004, learned = -0.0075))

    /** #610's Humax BHCN-T3103C: 0.76 % fast, packets as they are played with a 50-150 ms gap now and then. */
    @Test fun aFastHeadUnitIsTrimmedToItsDriftWithoutRunningLow() =
        settlesAtTheDrift(Car(startMs = 300, drift = 0.0076, burstMs = 7, jitterMs = 0, gapChancePerMs = 0.0004), lowestMs = 80)

    @Test fun aSlowHeadUnitIsTrimmedToItsDriftWithoutRunningLow() =
        settlesAtTheDrift(Car(startMs = 1000, drift = -0.0025), lowestMs = 800)

    private fun keepsTheRate(car: Car, seconds: Int = 120) {
        val run = car.play(seconds)
        val off = run.seconds.withIndex().filter { abs(it.value.rate - car.iphonesRate) > car.iphonesRate * 0.001 }
        assertTrue("${off.size} s off ${car.iphonesRate.roundToInt()} Hz ± 0.1 %, from " +
            off.take(8).joinToString { "${it.index + 1} s: ${it.value.rate}" }, off.isEmpty())
        assertEquals("rebuffers", 0, run.rebuffers)
    }

    private fun settlesAtTheDrift(car: Car, lowestMs: Long) {
        val run = car.play(seconds = 180)
        val settled = run.seconds.takeLast(30).map { it.rate }
        assertEquals("rate over the last 30 s", car.iphonesRate, settled.average(), car.iphonesRate * 0.001)
        val lowest = run.seconds.drop(SETTLE_SECONDS).minOf { it.lowestMs }
        assertTrue("the level fell to $lowest ms", lowest >= lowestMs)
        assertEquals("rebuffers", 0, run.rebuffers)
    }

    private class Second(val rate: Int, val lowestMs: Long)

    private class Run(val seconds: List<Second>, val rebuffers: Int)

    private class Car(
        val startMs: Int,
        val drift: Double = -0.0008,
        val burstMs: Long = 400,
        val jitterMs: Long = 8,
        val gapChancePerMs: Double = 0.0,
        val learned: Double = 0.0,
        seed: Int = 1068,
    ) {
        val iphonesRate = 48_000 / (1 + drift)
        private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        private val network = Random(seed)
        private val gaps = Random(1070)
        private val arrivals = ArrayDeque<Pair<Double, Int>>()
        private val reports = mutableListOf<String>()
        private var nowMs = 0L
        private var sent = 0
        private var nextBurstMs = 0L
        private var heldUntilMs = 0L
        private var lowestFrames = Long.MAX_VALUE
        private val perSecond = mutableListOf<Second>()
        private lateinit var renderer: Any
        private lateinit var queue: LinkedBlockingQueue<*>
        private lateinit var track: HeadUnitTrack
        private val submit = method("submit", ByteArray::class.java, Int::class.javaPrimitiveType!!)

        fun play(seconds: Int): Run {
            MediaClockTrim.learnedTrim = learned
            HeadUnitTrack.capacityFrames = MediaAudioBuffer.plan(true, 48_000, 2, MIN_BUFFER_BYTES, startMs).trackBufferBytes / 4
            val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 2, AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO, "media")
            renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
                .newInstance(format, false, false, 0, 0, AudioFocusCoordinator(null, false), 0, startMs,
                    { line: String -> reports += line }, 0L, false, null, false)
            field("started").set(renderer, true)
            method("createTrack").invoke(renderer)
            queue = field("queue").get(renderer) as LinkedBlockingQueue<*>
            track = Shadow.extract(field("track").get(renderer) as AudioTrack)
            track.drift = drift
            track.passOneMs = ::passOneMs
            val packetType = Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
            val handle = method("handle", packetType)
            val maintain = method("maintainPlaybackBuffer")
            var busyMs = 0.0
            while (nowMs < seconds * 1000L) {
                var waited = 0L
                while (queue.isEmpty() && waited++ < AUDIO_POLL_MILLIS) passOneMs()
                queue.poll()?.let { packet ->
                    handle.invoke(renderer, packet)
                    busyMs += DECODE_MS
                    while (busyMs >= 1.0) { passOneMs(); busyMs -= 1.0 }
                }
                maintain.invoke(renderer)
            }
            method("close").invoke(renderer)
            return Run(perSecond, reports.count { it.startsWith("Audio: rebuffer paused") })
        }

        private fun passOneMs() {
            nowMs++
            ShadowSystemClock.advanceBy(Duration.ofMillis(1))
            track.advanceOneMs()
            if (nowMs >= nextBurstMs) {
                val playedByIphone = (nextBurstMs * 48.0 / FRAME).toInt() + LEAD_PACKETS
                var at = nowMs.toDouble()
                while (sent < playedByIphone) {
                    arrivals.addLast(at to sent++)
                    at += PACKET_SPACING_MS
                }
                nextBurstMs += burstMs + network.nextLong(-jitterMs, jitterMs + 1)
            }
            if (gapChancePerMs > 0 && heldUntilMs < nowMs && gaps.nextDouble() < gapChancePerMs) {
                heldUntilMs = nowMs + gaps.nextLong(SHORTEST_GAP_MS, LONGEST_GAP_MS + 1)
            }
            while (nowMs >= heldUntilMs && arrivals.isNotEmpty() && arrivals.first().first <= nowMs) {
                val index = arrivals.removeFirst().second
                submit.invoke(renderer, ByteArray(12 + FRAME * 4), (BASE + index.toLong() * FRAME).toInt())
            }
            lowestFrames = minOf(lowestFrames, track.queuedFrames + queue.size * FRAME.toLong())
            if (nowMs % 1000 == 0L) {
                perSecond += Second(track.rate, lowestFrames / 48)
                lowestFrames = Long.MAX_VALUE
            }
        }

        private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        private fun method(name: String, vararg parameters: Class<*>) =
            type.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
    }

    @Implements(AudioTrack::class)
    class HeadUnitTrack {
        var passOneMs: () -> Unit = {}
        var drift = 0.0
        private var writtenFrames = 0L
        private var playedFrames = 0.0
        private var playing = false
        var rate = 48_000
            private set
        private var underruns = 0
        private var starving = false
        val queuedFrames: Long get() = writtenFrames - playedFrames.toLong()

        @Implementation fun play() { playing = true }
        @Implementation fun pause() { playing = false }

        @Implementation fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, writeMode: Int): Int {
            var remaining = sizeInBytes / 4L
            while (true) {
                val now = minOf(remaining, capacityFrames - queuedFrames)
                writtenFrames += now
                remaining -= now
                if (remaining == 0L) return sizeInBytes
                check(playing) { "a paused full track never drains" }
                // AOSP wakes a blocked writer once the server has freed notificationFrames, frameCount / 2 by default.
                do passOneMs() while (capacityFrames - queuedFrames < capacityFrames / 2)
            }
        }

        @Implementation fun getPlaybackHeadPosition(): Int = playedFrames.toLong().toInt()
        @Implementation fun getUnderrunCount(): Int = underruns
        @Implementation fun getBufferSizeInFrames(): Int = capacityFrames
        @Implementation fun getRoutedDevice(): AudioDeviceInfo? = null
        @Implementation fun setPlaybackRate(sampleRateInHz: Int): Int {
            rate = sampleRateInHz
            return AudioTrack.SUCCESS
        }

        fun advanceOneMs() {
            if (!playing) return
            val frames = rate * (1 + drift) / 1000.0
            if (playedFrames + frames > writtenFrames) {
                if (!starving) underruns++
                starving = true
                playedFrames = writtenFrames.toDouble()
            } else {
                starving = false
                playedFrames += frames
            }
        }

        companion object {
            var capacityFrames = 0

            @JvmStatic @Implementation
            fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = MIN_BUFFER_BYTES
        }
    }

    private companion object {
        const val BASE = 100_000L
        const val FRAME = 1024
        const val MIN_BUFFER_BYTES = 4096
        const val AUDIO_POLL_MILLIS = 10L
        const val LEAD_PACKETS = 4
        const val PACKET_SPACING_MS = 0.25
        const val DECODE_MS = 0.5
        const val SETTLE_SECONDS = 5
        const val SHORTEST_GAP_MS = 50L
        const val LONGEST_GAP_MS = 150L
    }
}
