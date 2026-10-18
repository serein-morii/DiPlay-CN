package com.shilapi.xcertplay.media

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoftwareOpusEncoderTest {
    @Test
    fun encodesEach20MsFrameAsOneDecodableOpusPacket() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        val decoder = OpusDecoder(48_000, 1)
        assertTrue(encoder.available)

        repeat(10) { index ->
            val packets = encoder.encode(toneFrame(index))
            assertEquals(1, packets.size)
            val packet = packets.single()
            assertTrue(packet.isNotEmpty())
            assertEquals("one frame per packet", 0, packet[0].toInt() and 0x03)
            val pcm = ShortArray(SoftwareOpusEncoder.FRAME_SAMPLES)
            assertEquals(SoftwareOpusEncoder.FRAME_SAMPLES, decoder.decode(packet, 0, packet.size, pcm, 0, pcm.size, false))
        }
        encoder.close()
        assertTrue(encoder.encode(toneFrame(0)).isEmpty())
    }

    // #865: a failed Concentus check is an AssertionError, which ended the microphone thread and DiPlay.
    @Test
    fun aFailedEncoderCheckCostsOneFrameAndTheNextStartsClean() {
        val errors = mutableListOf<Throwable>()
        var resets = 0
        val encoder = SoftwareOpusEncoder(
            bitrate = 48_000,
            onError = { _, error -> errors += error },
            create = {
                object : org.concentus.OpusEncoder(SoftwareOpusEncoder.SAMPLE_RATE, SoftwareOpusEncoder.CHANNELS,
                    OpusApplication.OPUS_APPLICATION_VOIP) {
                    var frames = 0

                    override fun encode(pcm: ByteArray, pcmOffset: Int, frameSize: Int, out: ByteArray,
                        outOffset: Int, maxBytes: Int): Int {
                        if (frames++ == 1) throw AssertionError()
                        return super.encode(pcm, pcmOffset, frameSize, out, outOffset, maxBytes)
                    }

                    override fun resetState() {
                        resets++
                        super.resetState()
                    }
                }
            },
        )
        val decoder = OpusDecoder(48_000, 1)
        assertEquals(1, encoder.encode(toneFrame(0)).size)
        val resetsBefore = resets

        assertTrue(encoder.encode(toneFrame(1)).isEmpty())
        assertTrue(errors.single() is AssertionError)
        assertEquals(resetsBefore + 1, resets)
        assertTrue(encoder.available)

        repeat(5) { index ->
            val packet = encoder.encode(toneFrame(index + 2)).single()
            val pcm = ShortArray(SoftwareOpusEncoder.FRAME_SAMPLES)
            assertEquals(SoftwareOpusEncoder.FRAME_SAMPLES, decoder.decode(packet, 0, packet.size, pcm, 0, pcm.size, false))
        }
    }

    @Test
    fun rejectsFramesThatAreNot20Ms() {
        val encoder = SoftwareOpusEncoder(bitrate = 48_000)
        assertTrue(encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES - 2)).isEmpty())
    }

    @Test
    fun platformEncoderIsPreferredAndSoftwareIsTheFallback() {
        val platform = FakeEncoder(available = true)
        assertSame(platform, MicrophoneOpusEncoders.create(48_000, platform = { platform }, software = { error("unused") }))

        val missing = FakeEncoder(available = false)
        val software = FakeEncoder(available = true)
        assertSame(software, MicrophoneOpusEncoders.create(48_000, platform = { missing }, software = { software }))
        assertTrue(missing.closed)

        val brokenSoftware = FakeEncoder(available = false)
        assertNull(MicrophoneOpusEncoders.create(48_000, platform = { FakeEncoder(false) }, software = { brokenSoftware }))
        assertTrue(brokenSoftware.closed)
    }

    private fun toneFrame(index: Int): ByteArray {
        val bytes = ByteArray(SoftwareOpusEncoder.FRAME_BYTES)
        for (i in 0 until SoftwareOpusEncoder.FRAME_SAMPLES) {
            val t = (index * SoftwareOpusEncoder.FRAME_SAMPLES + i) / 48_000.0
            val sample = (8_000 * (sin(2 * PI * 220 * t) + 0.5 * sin(2 * PI * 660 * t))).toInt()
            bytes[2 * i] = sample.toByte()
            bytes[2 * i + 1] = (sample shr 8).toByte()
        }
        return bytes
    }

    private class FakeEncoder(override val available: Boolean) : MicrophoneOpusEncoder {
        override val implementation = "fake"
        var closed = false
        override fun encode(pcm: ByteArray): List<ByteArray> = emptyList()
        override fun close() {
            closed = true
        }
    }
}
