package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [AudioRebufferDiagnosticsTest.TestTrack::class])
class AudioRebufferDiagnosticsTest {

    @Test
    fun rebufferingEmitsDiagnosticReportsAndResetsQueueOverflowFlag() {
        val reports = mutableListOf<String>()
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, 500)
        val threshold = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 4096)

        TestTrack.capacity = plan.trackBufferBytes
        TestTrack.queued = threshold / 2
        TestTrack.playing = true

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(plan.trackBufferBytes)
            .build()

        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }
            .apply { isAccessible = true }
            .newInstance(
                AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
                false, false, 0, 0,
                AudioFocusCoordinator(null, false, false),
                0, 500, { msg: String -> reports.add(msg) }, 0L, false, null, false
            )

        fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        fun get(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

        try {
            set("track", track)
            set("mappedChannel", AudioChannel.MEDIA)
            set("playbackStarted", true)
            set("startThresholdBytes", threshold)
            set("lastPcmWriteNs", System.nanoTime())
            set("bytesPerSecond", 48_000 * 4)

            (get("bufferProgress") as AudioBufferProgress).written(TestTrack.queued)

            // Trigger buffer maintenance rebuffering
            type.getDeclaredMethod("maintainPlaybackBuffer").apply { isAccessible = true }.invoke(renderer)

            assertTrue(reports.any { it.contains("Audio: rebuffer paused audioType=media rebufferCount=1") })

            // Write PCM to recover playback
            type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(renderer, ByteArray(threshold), 0, threshold)

            assertTrue(reports.any { it.contains("Audio: rebuffer recovered audioType=media rebufferCount=1") })

            // Check queue full flag reset when queue is empty
            set("droppedPacketsLogged", true)
            val audioPacketClass = Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
            val packetConstructor = audioPacketClass.declaredConstructors.single().apply { isAccessible = true }
            val dummyRtp = ByteArray(12 + 100)
            val packetInstance = packetConstructor.newInstance(dummyRtp, 0, 0L)

            type.getDeclaredMethod("handle", audioPacketClass).apply { isAccessible = true }.invoke(renderer, packetInstance)

            assertFalse(get("droppedPacketsLogged") as Boolean)

        } finally {
            track.release()
        }
    }

    @Test
    fun rebufferAndQueueFullReportsAreCappedAtFiveEvents() {
        val reports = mutableListOf<String>()
        val plan = MediaAudioBuffer.plan(true, 48_000, 2, 4096, 500)
        val threshold = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 4096)

        TestTrack.capacity = plan.trackBufferBytes
        TestTrack.queued = threshold / 2
        TestTrack.playing = true

        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().build())
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(plan.trackBufferBytes)
            .build()

        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 13 }
            .apply { isAccessible = true }
            .newInstance(
                AudioFormat(AudioCodecKind.LPCM, 48_000, 2, 96, "media"),
                false, false, 0, 0,
                AudioFocusCoordinator(null, false, false),
                0, 500, { msg: String -> reports.add(msg) }, 0L, false, null, false
            )

        fun set(name: String, value: Any?) = type.getDeclaredField(name).apply { isAccessible = true }.set(renderer, value)
        fun get(name: String) = type.getDeclaredField(name).apply { isAccessible = true }.get(renderer)

        try {
            set("track", track)
            set("mappedChannel", AudioChannel.MEDIA)
            set("startThresholdBytes", threshold)
            set("bytesPerSecond", 48_000 * 4)

            val bufferProgress = get("bufferProgress") as AudioBufferProgress

            // Trigger 10 rebuffer pause and recovery cycles
            repeat(10) {
                set("playbackStarted", true)
                set("underrunsAtPlaybackStart", 0)
                set("lastPcmWriteNs", System.nanoTime())
                bufferProgress.written(threshold)

                // Simulate hardware underrun (head position equals written samples)
                bufferProgress.queuedBytes(threshold / 4) // Advance head

                type.getDeclaredMethod("maintainPlaybackBuffer").apply { isAccessible = true }.invoke(renderer)

                type.getDeclaredMethod("writePcm", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(renderer, ByteArray(threshold), 0, threshold)
            }

            val pauseReports = reports.filter { it.contains("Audio: rebuffer paused") }
            val recoverReports = reports.filter { it.contains("Audio: rebuffer recovered") }

            assertEquals(5, pauseReports.size)
            assertEquals(5, recoverReports.size)
            assertEquals(10, get("rebufferCount") as Int)

            // Test queue full cap over 10 overflow/drain cycles
            set("started", true)
            set("running", true)
            val audioPacketClass = Class.forName("com.shilapi.xcertplay.media.AudioRenderer\$AudioPacket")
            val packetConstructor = audioPacketClass.declaredConstructors.single().apply { isAccessible = true }
            val dummyRtp = ByteArray(12 + 100)
            val packetInstance = packetConstructor.newInstance(dummyRtp, 0, 0L)

            @Suppress("UNCHECKED_CAST")
            val queue = get("queue") as java.util.concurrent.BlockingQueue<Any>

            repeat(10) {
                // Fill queue to capacity (MAX_QUEUED_PACKETS = 192)
                while (queue.remainingCapacity() > 0) {
                    queue.offer(packetInstance)
                }

                // Submit one packet that fails to offer because queue is full
                type.getDeclaredMethod("submit", ByteArray::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(renderer, dummyRtp, 0)

                // Drain queue
                queue.clear()

                // Process handle on an empty queue to reset droppedPacketsLogged
                type.getDeclaredMethod("handle", audioPacketClass)
                    .apply { isAccessible = true }
                    .invoke(renderer, packetInstance)
            }

            val queueFullReports = reports.filter { it.contains("Audio: queue full") }
            assertEquals(5, queueFullReports.size)

        } finally {
            track.release()
        }
    }

    @Implements(AudioTrack::class)
    class TestTrack {
        @Implementation fun getPlaybackHeadPosition() = 100_000
        @Implementation fun getUnderrunCount() = 2
        @Implementation fun pause() { playing = false }
        @Implementation fun play() { playing = true }
        @Implementation fun write(bytes: ByteArray, offset: Int, size: Int, mode: Int): Int {
            if (!playing) queued += size
            return size
        }
        companion object {
            var capacity = 0
            var queued = 0
            var playing = true
        }
    }
}
