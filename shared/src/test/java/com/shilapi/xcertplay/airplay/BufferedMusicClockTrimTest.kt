package com.shilapi.xcertplay.airplay

import android.media.AudioTrack
import android.os.SystemClock
import com.shilapi.xcertplay.media.AudioChannelMapper
import com.shilapi.xcertplay.media.AudioFocusCoordinator
import com.shilapi.xcertplay.media.MediaAudioBuffer
import com.shilapi.xcertplay.media.MediaClockTrim
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [BufferedMusicClockTrimTest.HeadUnitTrack::class],
    // The renderer times its trim with System.nanoTime(); instrumented, it reads Robolectric's clock.
    instrumentedPackages = ["com.shilapi.xcertplay.media"])
class BufferedMusicClockTrimTest {
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val format = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, BufferedAudioStream.STREAM_TYPE, "media")

    @Before @After fun forgetLearnedTrim() { MediaClockTrim.learnedTrim = 0.0 }

    @Test fun anAccurateHeadUnitPlaysBufferedMusicAtTheIphonesRateWithTheDefaultBuffer() = playsAtTheIphonesRate(300)

    @Test fun anAccurateHeadUnitPlaysBufferedMusicAtTheIphonesRateWithTheLongestBuffer() = playsAtTheIphonesRate(1000)

    @Test fun aFastHeadUnitIsTrimmedToItsDriftWithoutRunningLow() = settlesAtTheDrift(300, 0.0076)

    @Test fun aSlowHeadUnitIsTrimmedToItsDriftWithoutRunningLow() = settlesAtTheDrift(1000, -0.0025)

    @Test fun realtimeMusicWithTheSameStartAlsoKeepsTheIphonesRate() {
        val realtime = HeadUnit(300, payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO)
        val phase = playFromStartAndAfterASkip(realtime, 125).first()
        assertEquals("${phase.name}: seconds off 48000 ± 0.1 %", emptyList<String>(), phase.secondsOffTheIphonesRate)
    }

    @Test fun afterARebufferTheTrimSteersToTheLevelMusicResumesAt() {
        val phase = playFromStartAndAfterASkip(HeadUnit(300), 125, iphoneStalls(sentMs = 20_000, untilMs = 20_500)).first()
        assertEquals("${phase.name}: rebuffers", 1, phase.reports.count { it.startsWith("Audio: rebuffer paused") })
        assertEquals("${phase.name}: packets dropped", 0, phase.reports.count { it.startsWith("Audio: stale media packet") })
        assertEquals("${phase.name}: seconds off 48000 ± 0.1 %", emptyList<String>(), phase.secondsOffTheIphonesRate)
    }

    @Test fun afterStalePacketsAreDroppedTheTrimSteersToTheLevelLeft() {
        val phase = playFromStartAndAfterASkip(HeadUnit(300), 125, iphoneStalls(sentMs = 20_000, untilMs = 30_000)).first()
        assertTrue("${phase.name}: packets dropped", phase.reports.any { it.startsWith("Audio: stale media packet") })
        assertEquals("${phase.name}: seconds off 48000 ± 0.1 %", emptyList<String>(), phase.secondsOffTheIphonesRate)
    }

    @Test fun aStallWhileTheTrimMeasuresDoesNotSetItsTarget() {
        val phase = playFromStartAndAfterASkip(HeadUnit(300), 125, iphoneStalls(sentMs = 3_000, untilMs = 3_100)).first()
        assertEquals("${phase.name}: underruns", 0, phase.underruns)
        assertEquals("${phase.name}: seconds off 48000 ± 0.1 %", emptyList<String>(), phase.secondsOffTheIphonesRate)
    }

    @Test fun aStallEarlyInTheMeasurementDoesNotSetItsTarget() {
        val phase = playFromStartAndAfterASkip(HeadUnit(300), 40, iphoneStalls(sentMs = 1_500, untilMs = 1_700)).first()
        assertEquals("${phase.name}: underruns", 0, phase.underruns)
        assertEquals("${phase.name}: seconds off 48000 ± 0.1 %", emptyList<String>(), phase.secondsOffTheIphonesRate)
    }

    private fun iphoneStalls(sentMs: Long, untilMs: Long) = { elapsedMs: Long ->
        if (elapsedMs < untilMs) sentMs else elapsedMs + IPHONE_LEAD_MS
    }

    private fun playsAtTheIphonesRate(startMs: Int) {
        for (phase in playFromStartAndAfterASkip(HeadUnit(startMs), 125)) {
            assertEquals("${phase.name}: seconds off 48000 ± 0.1 %", emptyList<String>(), phase.secondsOffTheIphonesRate)
            val settled = phase.seconds[SETTLED_SECONDS - 1].levelMs
            assertTrue("${phase.name}: the level fell to ${phase.lowestLevelMs} ms from $settled ms", phase.lowestLevelMs >= settled - 20)
            assertEquals("${phase.name}: underruns", 0, phase.underruns)
        }
    }

    private fun settlesAtTheDrift(startMs: Int, drift: Double) {
        for (phase in playFromStartAndAfterASkip(HeadUnit(startMs, drift), 300)) {
            assertEquals("${phase.name}: final rate", 48_000 / (1 + drift), phase.finalRate.toDouble(), 15.0)
            assertTrue("${phase.name}: the level fell to ${phase.lowestLevelMs} ms, below the $startMs ms start level",
                phase.lowestLevelMs >= startMs)
            assertEquals("${phase.name}: underruns", 0, phase.underruns)
        }
    }

    private data class HeadUnit(val startMs: Int, val drift: Double = 0.0, val payloadType: Int = BufferedAudioStream.STREAM_TYPE)

    private class Second(val rate: Int, val levelMs: Long)

    private class Phase(val name: String, val seconds: List<Second>, val underruns: Int, val reports: List<String>) {
        val finalRate get() = seconds.last().rate
        val lowestLevelMs get() = seconds.drop(SETTLED_SECONDS - 1).minOf { it.levelMs }
        val secondsOffTheIphonesRate get() =
            seconds.withIndex().filter { abs(it.value.rate - 48_000) > 48 }.map { "${it.index + 1} s: ${it.value.rate}" }
    }

    private class RendererWithModelledDecoder(format: AudioFormat, startMs: Int, drift: Double, reports: MutableList<String>) {
        private val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        private val renderer = type.declaredConstructors.single { it.parameterCount == 13 }.apply { isAccessible = true }
            .newInstance(format, false, false, 0, 0, AudioFocusCoordinator(null, false), 0, startMs,
                { line: String -> reports += line }, 0L, false, null, false)
        private val createdMs = SystemClock.uptimeMillis()
        private val packets: LinkedBlockingQueue<*>
        private val track: AudioTrack
        val output: HeadUnitTrack
        private var decodeCredit = 0.0

        init {
            field("started").set(renderer, true)
            method("createTrack").invoke(renderer)
            packets = field("queue").get(renderer) as LinkedBlockingQueue<*>
            track = field("track").get(renderer) as AudioTrack
            output = Shadow.extract(track)
            output.drift = drift
        }

        val levelMs: Long get() = (output.queuedFrames + packets.size * FRAME) / 48

        fun submit(rtp: ByteArray, sample: Int) {
            method("submit", ByteArray::class.java, Int::class.javaPrimitiveType!!).invoke(renderer, rtp, sample)
        }

        fun tick(nowMs: Long) {
            output.advance(nowMs)
            if (nowMs >= createdMs + SETUP_MS) {
                decodeCredit += DECODE_TIMES_REALTIME * 48.0 * STEP_MS
                val write = method("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
                while (decodeCredit >= FRAME && packets.isNotEmpty() && output.queuedFrames + FRAME <= HeadUnitTrack.capacityFrames) {
                    packets.poll()
                    decodeCredit -= FRAME
                    write.invoke(renderer, ByteArray(FRAME.toInt() * 4), 0, FRAME.toInt() * 4)
                }
                decodeCredit = minOf(decodeCredit, FRAME.toDouble())
            }
            method("maintainPlaybackBuffer").invoke(renderer)
        }

        fun close() {
            method("close").invoke(renderer)
            track.release()
        }

        private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        private fun method(name: String, vararg parameters: Class<*>) = type.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
    }

    @Implements(AudioTrack::class)
    class HeadUnitTrack {
        var drift = 0.0
        private var writtenFrames = 0L
        private var playedFrames = 0.0
        private var movesAtMs: Long? = null
        var rate = 48_000
            private set
        var underruns = 0
            private set
        val queuedFrames: Long get() = writtenFrames - playedFrames.toLong()

        @Implementation fun play() { movesAtMs = SystemClock.uptimeMillis() + PLAY_LATENCY_MS }
        @Implementation fun pause() { movesAtMs = null }
        @Implementation fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int, writeMode: Int): Int {
            writtenFrames += sizeInBytes / 4
            return sizeInBytes
        }
        @Implementation fun getPlaybackHeadPosition(): Int = playedFrames.toLong().toInt()
        @Implementation fun getUnderrunCount(): Int = underruns
        @Implementation fun getBufferSizeInFrames(): Int = capacityFrames
        @Implementation fun setPlaybackRate(sampleRateInHz: Int): Int {
            rate = sampleRateInHz
            return AudioTrack.SUCCESS
        }

        fun advance(nowMs: Long) {
            val moves = movesAtMs ?: return
            if (nowMs <= moves) return
            val frames = rate * (1 + drift) * minOf(STEP_MS, nowMs - moves) / 1000.0
            if (playedFrames + frames > writtenFrames) {
                underruns++
                playedFrames = writtenFrames.toDouble()
            } else {
                playedFrames += frames
            }
        }

        companion object {
            var capacityFrames = 0

            @JvmStatic @Implementation
            fun getMinBufferSize(sampleRateInHz: Int, channelConfig: Int, audioFormat: Int): Int = MIN_BUFFER_BYTES
        }
    }

    private sealed interface SinkEvent {
        data object Started : SinkEvent
        class Rtp(val rtp: ByteArray, val sample: Int) : SinkEvent
        data object Stopped : SinkEvent
    }

    private fun playFromStartAndAfterASkip(
        unit: HeadUnit,
        seconds: Int,
        iphoneSentMs: (elapsedMs: Long) -> Long = { it + IPHONE_LEAD_MS },
    ): List<Phase> {
        HeadUnitTrack.capacityFrames = MediaAudioBuffer.plan(true, 48_000, 2, MIN_BUFFER_BYTES, unit.startMs).trackBufferBytes / 4
        val events = ConcurrentLinkedQueue<SinkEvent>()
        val sink = object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { events += SinkEvent.Started }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { events += SinkEvent.Rtp(rtp, sample) }
            override fun onAudioStopped(id: AudioStreamId) { events += SinkEvent.Stopped }
        }
        val clock = VirtualClock()
        val stream = BufferedAudioStream(key, format, sink, nanoTime = clock::nanoTime, pause = {})
        val lock = privateField(stream, "lock") as Object
        val queue = privateField(stream, "queue") as ArrayDeque<*>
        val fed = privateField(stream, "fed") as ArrayDeque<*>
        val ntp = BigInteger.valueOf(2_208_988_800L + 100L).shiftLeft(32)
        val before = Thread.getAllStackTraces().keys
        val phases = mutableListOf<Phase>()
        var renderer: RendererWithModelledDecoder? = null
        var underruns = 0
        val reports = mutableListOf<String>()
        stream.start()
        val socket = Socket(InetAddress.getLoopbackAddress(), stream.port)
        val out = DataOutputStream(socket.getOutputStream().buffered())
        var sent = 0
        try {
            fun send(elapsedMs: Long) {
                val due = (iphoneSentMs(elapsedMs) * 48 / FRAME).toInt()
                if (sent >= due) return
                while (sent < due) {
                    val body = seal(sent, (BASE + sent * FRAME) and 0xffff_ffffL)
                    out.writeShort(body.size + 2)
                    out.write(body)
                    sent++
                }
                out.flush()
                val last = (BASE + (sent - 1) * FRAME) and 0xffff_ffffL
                val deadline = System.nanoTime() + 5_000_000_000L
                while (synchronized(lock) { ((queue.peekLast() ?: fed.peekLast()) as BufferedAudioStream.Frame?)?.timestamp } != last) {
                    assertTrue("the stream did not receive frame $sent", System.nanoTime() < deadline)
                    Thread.onSpinWait()
                }
            }

            fun feedUntilIdle() {
                val reads = clock.feedReads
                synchronized(lock) { lock.notifyAll() }
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!(clock.feedReads > reads && clock.feedThread!!.state == Thread.State.TIMED_WAITING)) {
                    assertTrue("the feed did not settle", System.nanoTime() < deadline)
                    Thread.onSpinWait()
                }
            }

            fun step(feeding: Boolean) {
                clock.ns += STEP_MS * 1_000_000L
                ShadowSystemClock.advanceBy(Duration.ofMillis(STEP_MS))
                send(clock.ns / 1_000_000L)
                if (feeding) feedUntilIdle()
                while (true) {
                    when (val event = events.poll() ?: break) {
                        SinkEvent.Started -> renderer = RendererWithModelledDecoder(
                            format.copy(codec = AudioCodecKind.LPCM, payloadType = unit.payloadType), unit.startMs, unit.drift, reports)
                        is SinkEvent.Rtp -> renderer!!.submit(event.rtp, event.sample)
                        SinkEvent.Stopped -> renderer?.let {
                            underruns += it.output.underruns
                            it.close()
                            renderer = null
                        }
                    }
                }
                renderer?.tick(SystemClock.uptimeMillis())
            }

            fun phase(name: String) {
                val underrunsBefore = underruns
                reports.clear()
                val perSecond = List(seconds) {
                    repeat((1000 / STEP_MS).toInt()) { step(feeding = true) }
                    val current = renderer!!
                    Second(current.output.rate, current.levelMs)
                }
                phases += Phase(name, perSecond, underruns - underrunsBefore + renderer!!.output.underruns, reports.toList())
            }

            send(0)
            clock.feedThread = (Thread.getAllStackTraces().keys - before).single { it.name == "airplay-buffered-feed" }
            stream.setRate(BASE, 1, ntp)
            feedUntilIdle()
            phase("stream start")

            stream.setRate(null, 0, ntp)
            val position = stream.anchor()!!["rtpTime"] as Long
            val skipTo = BASE + ((position - BASE + 10 * 48_000L) / FRAME + 1) * FRAME
            repeat(5) { step(feeding = false) }
            stream.flush(skipTo)
            repeat(60) { step(feeding = false) }
            stream.setRate(skipTo, 1, ntp)
            feedUntilIdle()
            phase("FLUSHBUFFERED skip")
        } finally {
            renderer?.close()
            stream.close()
            socket.close()
        }
        return phases
    }

    private class VirtualClock {
        @Volatile var ns = 0L
        @Volatile var feedReads = 0
        @Volatile var feedThread: Thread? = null
        fun nanoTime(): Long = ns.also { if (Thread.currentThread() === feedThread) feedReads++ }
    }

    private fun privateField(stream: BufferedAudioStream, name: String): Any =
        BufferedAudioStream::class.java.getDeclaredField(name).apply { isAccessible = true }.get(stream)!!

    private fun seal(sequence: Int, timestamp: Long): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x60
        header[2] = (sequence shr 8).toByte()
        header[3] = sequence.toByte()
        for (i in 0 until 4) header[4 + i] = (timestamp shr (24 - 8 * i)).toByte()
        val nonceTail = ByteArray(8) { (sequence + it).toByte() }
        val nonce = ByteArray(12).also { nonceTail.copyInto(it, 4) }
        return header + AirPlayCrypto.chachaSeal(key, nonce, byteArrayOf(0x21), header.copyOfRange(4, 12)) + nonceTail
    }

    private companion object {
        const val BASE = 100_000L
        const val FRAME = 1024L
        const val STEP_MS = 5L
        const val SETUP_MS = 90L
        const val DECODE_TIMES_REALTIME = 10.0
        const val PLAY_LATENCY_MS = 200L
        const val MIN_BUFFER_BYTES = 4096
        const val IPHONE_LEAD_MS = 60_000L
        const val SETTLED_SECONDS = 5
    }
}
