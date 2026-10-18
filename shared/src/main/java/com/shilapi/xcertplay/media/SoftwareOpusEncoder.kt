package com.shilapi.xcertplay.media

import org.concentus.OpusApplication
import org.concentus.OpusSignal

/**
 * Opus encoder in pure Java (Concentus, a port of the Opus reference library) for head units whose
 * Android has no MediaCodec Opus encoder. Kept free of Android types so it stays testable on the JVM.
 */
internal class SoftwareOpusEncoder(
    bitrate: Int,
    complexity: Int = DEFAULT_COMPLEXITY,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    create: () -> org.concentus.OpusEncoder = {
        org.concentus.OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP)
    },
) : MicrophoneOpusEncoder {
    override val implementation: String = "software"

    private var encoder: org.concentus.OpusEncoder? = try {
        create().apply {
            setBitrate(bitrate)
            setComplexity(complexity)
            setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        }
    } catch (error: Throwable) {
        onError("software Opus encoder unavailable", error)
        null
    }
    private val output = ByteArray(MAX_PACKET_BYTES)
    private var failures = 0

    override val available: Boolean get() = encoder != null

    override fun encode(pcm: ByteArray): List<ByteArray> {
        val encoder = encoder ?: return emptyList()
        if (pcm.size != FRAME_BYTES) return emptyList()
        return try {
            val length = encoder.encode(pcm, 0, FRAME_SAMPLES, output, 0, output.size)
            if (length > 0) listOf(output.copyOf(length)) else emptyList()
        } catch (error: Exception) {
            failed(encoder, error)
        } catch (error: AssertionError) {
            // Concentus checks the reference library's debug assertions in every build and throws an Error,
            // which ended the microphone thread and DiPlay with it (#865). A failed check costs this frame only.
            failed(encoder, error)
        }
    }

    private fun failed(encoder: org.concentus.OpusEncoder, error: Throwable): List<ByteArray> {
        if (failures++ < MAX_REPORTED_FAILURES) onError("software Opus encode failed", error)
        // The frame stopped part way through, so the next one starts from a clean encoder state.
        runCatching { encoder.resetState() }
        return emptyList()
    }

    override fun close() {
        encoder = null
    }

    internal companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = 960
        const val FRAME_BYTES = FRAME_SAMPLES * CHANNELS * 2

        /** Speech matched complexity 10 in tests at about three quarters of its CPU time. */
        const val DEFAULT_COMPLEXITY = 5
        private const val MAX_PACKET_BYTES = 1_275
        private const val MAX_REPORTED_FAILURES = 3
    }
}
