package com.shilapi.xcertplay.airplay

import java.net.InetAddress

/** Everything one captured microphone stream needs to send samples to the phone. */
data class MicrophoneConfig(
    val audioType: String,
    val sampleRate: Int,
    val channels: Int,
    val payloadType: Int,
    val frameMillis: Int,
    val host: InetAddress,
    val port: Int,
    val key: ByteArray,
    val codec: AudioCodecKind = AudioCodecKind.LPCM,
    val bitrate: Int? = null,
    /**
     * The CarPlay clock for an Opus stream. Capture and encoding remain at 48 kHz.
     * Captured Siri packets for audioFormat 0x20000000 advance by 480 per 20 ms and calls
     * (0x40000000) by 960, so the clock follows the format's rate; 0x10000000 counts at 16 kHz on that
     * rule. The report's "Audio: rtp clock" line measures the iPhone's own clock for each stream.
     */
    val opusClockRate: Int = OPUS_CAPTURE_RATE,
) {
    val samplesPerPacket: Int
        get() = if (codec == AudioCodecKind.OPUS) {
            OPUS_SAMPLES_PER_PACKET
        } else {
            maxOf(1, sampleRate * frameMillis / 1000)
        }

    /** The clock the RTP timestamps count in: the Opus format's rate, or the PCM sample rate. */
    val rtpClockRate: Int
        get() = if (codec == AudioCodecKind.OPUS) opusClockRate else sampleRate

    /** How far each packet moves the RTP timestamp, on the negotiated clock. */
    val rtpSamplesPerPacket: Int
        get() = if (codec == AudioCodecKind.OPUS) opusClockRate * OPUS_FRAME_MILLIS / 1000 else samplesPerPacket

    val frameBytes: Int
        get() = samplesPerPacket * channels * 2

    companion object {
        private const val OPUS_SAMPLES_PER_PACKET = 960
        private const val OPUS_CAPTURE_RATE = 48_000
        private const val OPUS_FRAME_MILLIS = 20

        /** The negotiated Opus format's rate: 48, 24 or 16 kHz; 48 kHz when no Opus bit is set. */
        fun opusClockRate(formatBits: Long): Int = when {
            formatBits and 0x40000000L != 0L -> 48_000
            formatBits and 0x20000000L != 0L -> 24_000
            formatBits and 0x10000000L != 0L -> 16_000
            else -> OPUS_CAPTURE_RATE
        }

        /**
         * Milliseconds of PCM per packet when the iPhone names no framesPerPacket: the longest of 20, 10 and 5 ms whose
         * sealed packet still fits one 1,500-byte link MTU with its IPv6 and UDP headers. 20 ms of 48 kHz mono is a
         * 1,956-byte packet that leaves as IP fragments. Over USB every 48 kHz recording in the #1094 reports came out
         * silent while 24 kHz Siri packets (996 bytes) worked, and wireless sends small Opus packets instead (#718,
         * #1017). 48 kHz mono therefore sends 10 ms, the 480 samples a 24 kHz Siri packet already carries.
         */
        fun pcmFrameMillis(sampleRate: Int, channels: Int): Int = PCM_FRAME_MILLIS.firstOrNull { millis ->
            MicrophonePacketizer.SEAL_OVERHEAD + sampleRate * millis / 1000 * channels * 2 <= MAX_UDP_PAYLOAD
        } ?: PCM_FRAME_MILLIS.last()

        private val PCM_FRAME_MILLIS = listOf(20, 10, 5)

        /** A 1,500-byte link MTU less the 40-byte IPv6 and 8-byte UDP headers. */
        private const val MAX_UDP_PAYLOAD = 1500 - 40 - 8
    }
}

/** Mutable RTP/ChaCha counters for one microphone uplink. */
class MicrophoneCounters(
    var sequence: Int = 0,
    var timestamp: Int = 0,
    var nonce: Long = 0,
)

/**
 * Seals one PCM microphone frame in the wired CarPlay RTP layout.
 *
 * The 12-byte RTP header is clear; its timestamp and SSRC are authenticated as AAD. The packet
 * tail repeats the eight-byte little-endian nonce counter after the 16-byte Poly1305 tag.
 */
object MicrophonePacketizer {
    const val RTP_HEADER_LEN = 12
    const val TAG_LEN = 16
    const val NONCE_LEN = 8

    /** Bytes a sealed packet adds to its samples: the RTP header, the Poly1305 tag and the nonce. */
    const val SEAL_OVERHEAD = RTP_HEADER_LEN + TAG_LEN + NONCE_LEN

    fun sealPacket(
        key: ByteArray,
        payloadType: Int,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte()
        header[1] = (payloadType and 0x7f).toByte()
        putU16Be(header, 2, counters.sequence)
        putU32Be(header, 4, counters.timestamp)

        val nonce = ByteArray(12)
        putU64Le(nonce, 4, counters.nonce)
        val sealed = AirPlayCrypto.chachaSeal(
            key = key,
            nonce = nonce,
            plaintext = body,
            aad = header.copyOfRange(4, RTP_HEADER_LEN),
        )

        val packet = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_LEN)
        header.copyInto(packet, 0)
        sealed.copyInto(packet, RTP_HEADER_LEN)
        putU64Le(packet, RTP_HEADER_LEN + sealed.size, counters.nonce)

        counters.sequence = (counters.sequence + 1) and 0xffff
        counters.timestamp += samples
        counters.nonce++
        return packet
    }

    /** Captured Android PCM is little-endian; CarPlay's wired microphone payload is big-endian. */
    fun toWirePcm(pcmLittleEndian: ByteArray): ByteArray {
        val output = pcmLittleEndian.copyOf()
        var index = 0
        while (index + 1 < output.size) {
            val first = output[index]
            output[index] = output[index + 1]
            output[index + 1] = first
            index += 2
        }
        return output
    }

    private fun putU16Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 8).toByte()
        target[offset + 1] = value.toByte()
    }

    private fun putU32Be(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun putU64Le(target: ByteArray, offset: Int, value: Long) {
        var remaining = value
        for (index in 0 until 8) {
            target[offset + index] = remaining.toByte()
            remaining = remaining ushr 8
        }
    }
}
