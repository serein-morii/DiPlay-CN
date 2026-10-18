package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE, shadows = [NavigationAudioGainTest.TrackGain::class])
class NavigationAudioGainTest {
    @Test fun decibelsAreAmplitudeNotPercentAndOutOfRangeNeverBoosts() {
        assertEquals(1f, NavigationAudioGain.linear(0), 0.0001f)
        assertEquals(0.1f, NavigationAudioGain.linear(-20), 0.0001f)
        assertEquals(0.501187f, NavigationAudioGain.linear(-6), 0.0001f)
        assertEquals(0.1f, NavigationAudioGain.linear(-100), 0.0001f)
        assertEquals(1f, NavigationAudioGain.linear(20), 0.0001f)
    }

    @Test fun pcmIsScaledInPlaceAndZeroDecibelsLeavesItAlone() {
        val pcm = pcm16(32767, -32768, 1000, -1000, 0)
        NavigationAudioGain.scalePcm16(pcm, 0, pcm.size, -20)
        assertArrayEquals(pcm16(3277, -3277, 100, -100, 0), pcm)
        val untouched = pcm16(32767, -32768, 1234)
        NavigationAudioGain.scalePcm16(untouched, 0, untouched.size, 0)
        assertArrayEquals(pcm16(32767, -32768, 1234), untouched)
        // Only the given range, and never half a sample.
        val part = pcm16(20000, 20000, 20000) + byteArrayOf(0x7f)
        NavigationAudioGain.scalePcm16(part, 2, 3, -6)
        assertArrayEquals(pcm16(20000, 10024, 20000) + byteArrayOf(0x7f), part)
    }

    // #719: on a DiLink 3.0 every setVolume on the guidance track succeeded and the guidance stayed as loud.
    @Test fun navigationGuidanceIsScaledAsWrittenAndNeverThroughTheTrack() {
        val sink = AndroidMediaSink(navigationAttenuationDb = -12)
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "default")
        val create = AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer",
            AudioStreamId::class.java, AudioFormat::class.java).apply { isAccessible = true }
        val renderer = create.invoke(sink, AudioStreamId(96, "default"), format)
        val type = renderer.javaClass
        val track = track()
        try {
            type.getDeclaredField("mappedChannel").apply { isAccessible = true }.set(renderer, AudioChannel.NAVIGATION)
            type.getDeclaredField("track").apply { isAccessible = true }.set(renderer, track)
            val attenuate = type.getDeclaredMethod("attenuateNavigation", ByteArray::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            type.getDeclaredMethod("setNavigationAttenuationDb", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(renderer, -12)
            val pcm = pcm16(10000, -10000)
            attenuate.invoke(renderer, pcm, 0, pcm.size)
            assertArrayEquals(pcm16(2512, -2512), pcm)
            // A change applies to the next PCM written, on the audio worker.
            sink.setNavigationAttenuationDb(0)
            val next = pcm16(10000, -10000)
            attenuate.invoke(renderer, next, 0, next.size)
            assertArrayEquals(pcm16(10000, -10000), next)
            type.getDeclaredMethod("applyNavigationAttenuation").apply { isAccessible = true }.invoke(renderer)
            assertTrue(TrackGain.volumes[track].isNullOrEmpty())
        } finally {
            sink.close()
            track.release()
            TrackGain.volumes.clear()
        }
    }

    @Test fun unmodifiedNavigationAndOtherAudioTracksKeepTheirVolume() {
        val manager = RuntimeEnvironment.getApplication()
            .getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        val streamBefore = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val sink = AndroidMediaSink()
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val constructor = type.declaredConstructors.single { it.parameterCount == 13 }
            .apply { isAccessible = true }
        val tracks = mutableListOf<AudioTrack>()
        try {
            for (channel in AudioChannel.entries) {
                val renderer = constructor.newInstance(
                    AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "media"),
                    false, false, 0, 0, AudioFocusCoordinator(null, false, false), 0, 300,
                    { _: String -> }, 0L, false, null, false)
                val track = track().also(tracks::add)
                type.getDeclaredField("mappedChannel").apply { isAccessible = true }.set(renderer, channel)
                type.getDeclaredField("track").apply { isAccessible = true }.set(renderer, track)
                type.getDeclaredMethod("setNavigationAttenuationDb", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(renderer, 0)
                type.getDeclaredMethod("applyNavigationAttenuation")
                    .apply { isAccessible = true }.invoke(renderer)
                assertTrue(TrackGain.volumes[track].isNullOrEmpty())
                type.getDeclaredMethod("setNavigationAttenuationDb", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(renderer, -20)
                assertTrue(TrackGain.volumes[track].isNullOrEmpty())
                type.getDeclaredMethod("applyNavigationAttenuation")
                    .apply { isAccessible = true }.invoke(renderer)
                assertTrue("Unexpected track volume for $channel", TrackGain.volumes[track].isNullOrEmpty())
                val pcm = pcm16(10000)
                type.getDeclaredMethod("attenuateNavigation", ByteArray::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(renderer, pcm, 0, pcm.size)
                assertArrayEquals("Unexpected change to $channel",
                    pcm16(if (channel == AudioChannel.NAVIGATION) 1000 else 10000), pcm)
            }
            assertEquals(streamBefore, manager.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            sink.close()
            tracks.forEach(AudioTrack::release)
            TrackGain.volumes.clear()
        }
    }

    @Test fun sliderCallbackNeverBlocksOnDecoderReleaseMonitor() {
        val sink = AndroidMediaSink()
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "default")
        val id = AudioStreamId(96, "default")
        val create = AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer",
            AudioStreamId::class.java, AudioFormat::class.java).apply { isAccessible = true }
        val renderer = create.invoke(sink, id, format)
        val monitorTaken = CountDownLatch(1)
        val releaseMonitor = CountDownLatch(1)
        val codecThread = Thread({
            synchronized(renderer) {
                monitorTaken.countDown()
                releaseMonitor.await(5, TimeUnit.SECONDS)
            }
        }, "simulated-slow-codec-release")
        val ui = Executors.newSingleThreadExecutor()
        try {
            codecThread.start()
            assertTrue("Decoder release did not take the monitor", monitorTaken.await(2, TimeUnit.SECONDS))
            val updated = ui.submit {
                sink.setNavigationAttenuationDb(-8)
                sink.setNavigationAttenuationDb(-15)
            }
            // A synchronized setter would wait for the held monitor and fail here.
            updated.get(1, TimeUnit.SECONDS)
            val gain = renderer.javaClass.getDeclaredField("navigationAttenuationDb")
                .apply { isAccessible = true }.getInt(renderer)
            assertEquals(-15, gain)
        } finally {
            releaseMonitor.countDown()
            codecThread.join(2_000)
            ui.shutdownNow()
            sink.close()
        }
    }

    @Test fun eachChangeIsReportedOnceWhenItReachesTheGuidance() {
        val reports = mutableListOf<String>()
        val sink = AndroidMediaSink(onAudioDiagnostic = reports::add)
        val format = AudioFormat(AudioCodecKind.LPCM, 48_000, 1, 96, "default")
        val create = AndroidMediaSink::class.java.getDeclaredMethod("audioRenderer",
            AudioStreamId::class.java, AudioFormat::class.java).apply { isAccessible = true }
        val renderer = create.invoke(sink, AudioStreamId(96, "default"), format)
        val type = renderer.javaClass
        val flush = type.getDeclaredMethod("applyNavigationAttenuation").apply { isAccessible = true }
        try {
            type.getDeclaredField("mappedChannel").apply { isAccessible = true }
                .set(renderer, AudioChannel.NAVIGATION)
            repeat(10) { flush.invoke(renderer) }
            assertTrue(reports.none { it.contains("navigation attenuation") })
            sink.setNavigationAttenuationDb(-6)
            repeat(100) { flush.invoke(renderer) }
            assertEquals(1, reports.count { it.contains("attenuation=-6dB applied=true") })
            sink.setNavigationAttenuationDb(0)
            repeat(10) { flush.invoke(renderer) }
            assertEquals(1, reports.count { it.contains("attenuation=0dB applied=true") })
        } finally {
            sink.close()
        }
    }

    private fun pcm16(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { index, sample ->
            out[index * 2] = sample.toByte()
            out[index * 2 + 1] = (sample shr 8).toByte()
        }
    }

    private fun track(): AudioTrack =
        AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AndroidAudioFormat.Builder().setSampleRate(48_000)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(4096).build()

    @Implements(AudioTrack::class)
    class TrackGain {
        @RealObject private lateinit var track: AudioTrack
        @Implementation fun setVolume(value: Float): Int {
            volumes.getOrPut(track) { mutableListOf() }.add(value)
            return result
        }
        companion object {
            val volumes = linkedMapOf<AudioTrack, MutableList<Float>>()
            var result = AudioTrack.SUCCESS
        }
    }
}
