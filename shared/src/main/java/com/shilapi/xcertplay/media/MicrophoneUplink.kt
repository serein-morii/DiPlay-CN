package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.telecom.PhoneAudioRoute

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
    private val speakerphoneCall: Boolean = false,
    /** The call's far-end audio; when set, telephony capture runs DiPlay's own echo canceller. */
    private val echoReference: EchoReference? = null,
    private val echoCancellerFactory: (Int, Int, Int) -> CallEchoCanceller? = { frame, rate, tail ->
        SpeexEchoCanceller.create(frame, rate, tail)
    },
    private val opusEncoderFactory: (Int) -> MicrophoneOpusEncoder? = { bitrate ->
        MicrophoneOpusEncoders.create(
            bitrate = bitrate,
            software = { value ->
                SoftwareOpusEncoder(value, onError = { message, error -> Log.w(TAG, message, error) })
            },
        )
    },
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: MicrophoneOpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    @Volatile private var echoCanceller: CallEchoCanceller? = null
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            running.set(false)
            return false
        }

        val source = when {
            config.audioType == "telephony" && speakerphoneCall -> MediaRecorder.AudioSource.MIC
            config.audioType == "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            // The Geely/Lynk head unit only opens the microphone for the voice-communication
            // source; its voice-recognition source records silence, so Siri never hears.
            config.audioType == "speechrecognition" ->
                if (com.shilapi.xcertplay.network.GeelyHeadUnit.detected) {
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
                } else {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION
                }
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoderFactory(config.bitrate ?: 48_000)
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }
        if (nextEncoder != null) {
            val message = "Microphone: encoder type=${config.audioType} codec=OPUS " +
                "implementation=${nextEncoder.implementation}"
            Log.i(TAG, message)
            runCatching { onDiagnostic(message) }
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        // Some head units throw on VOICE_RECOGNITION at build(); VOICE_COMMUNICATION works there.
        val nextRecorder = createRecorder(source, channelMask, bufferSize)
            ?: if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                stats.useVoiceCommunicationSource()
                createRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, channelMask, bufferSize)
            } else {
                null
            }
        if (nextRecorder == null) {
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            if (config.audioType == "telephony") {
                echoCanceller = createEchoCanceller()
                if (!speakerphoneCall) effects = voiceEffects(nextRecorder.audioSessionId)
                if (echoReference != null) {
                    Log.i(TAG, "microphone echo canceller enabled=${echoCanceller != null} tail=${ECHO_TAIL_MILLIS}ms")
                }
            }
            nextRecorder.startRecording()
            stats.started(routeType(nextRecorder))
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private fun createRecorder(source: Int, channelMask: Int, bufferSize: Int): AudioRecord? {
        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed source=$source", error)
            stats.failure(MicrophoneFailureStage.RECORDER_CREATION, error)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize source=$source")
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION, code = recorder.state)
            recorder.release()
            return null
        }
        return recorder
    }

    private fun createEchoCanceller(): CallEchoCanceller? {
        val reference = echoReference ?: return null
        if (config.channels != 1 || config.sampleRate != reference.sampleRate) {
            Log.i(TAG, "microphone echo canceller skipped rate=${config.sampleRate} channels=${config.channels}")
            return null
        }
        return echoCancellerFactory(config.frameBytes / 2, config.sampleRate, ECHO_TAIL_MILLIS)
    }

    private fun voiceEffects(sessionId: Int): List<AudioEffect> {
        if (PhoneAudioRoute.carPlayCallsUseCarAudio()) {
            // In the car's phone audio state its own processing handles echo and noise, and was only measured
            // with the platform effects off.
            Log.i(TAG, "microphone effects left to the car's phone audio state")
            return emptyList()
        }
        var aec: AudioEffect? = null
        if (echoCanceller != null) {
            // VOICE_COMMUNICATION can enable AEC by default. Keep its controller alive and
            // explicitly disable it while Speex runs, rather than stacking two cancellers.
            val platformAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(true)
            if (platformAvailable) {
                aec = configuredEffect("AEC", false) { AcousticEchoCanceler.create(sessionId) }
                if (aec == null) {
                    echoCanceller?.close()
                    echoCanceller = null
                    Log.w(TAG, "microphone native echo canceller skipped: platform AEC could not be disabled")
                }
            }
        }
        if (echoCanceller == null) {
            aec = enabledEffect("AEC") {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
            }
        }
        val ns = if (echoCanceller != null) {
            // Noise suppression before the canceller distorts the echo it has to model; Speex
            // denoises after cancelling instead. Keep the controller so a fallback can re-enable it.
            configuredEffect("NS", false) {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
            }
        } else {
            enabledEffect("NS") {
                if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
            }
        }
        return listOfNotNull(aec, ns)
    }

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? =
        configuredEffect(name, true, create)

    private fun configuredEffect(name: String, enabled: Boolean, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(enabled)
                if (status == AudioEffect.SUCCESS && effect.enabled == enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=$enabled")
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be ${if (enabled) "enabled" else "disabled"} status=$status")
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val counters = MicrophoneCounters()
        val routeInfo = { routeType(recorder) }
        var canceller = echoCanceller
        val clock = canceller?.let { CaptureClock(config.sampleRate) }
        val reference = canceller?.let { ShortArray(it.frameSamples) }
        var filled = 0
        try {
            while (running.get()) {
                stats.reading()
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                stats.read(count)
                if (count > 0) clock?.read(recorder, count / 2)
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo)
                    continue
                }
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        if (canceller != null && clock != null && reference != null) {
                            // Ask slightly ahead of the frame so speaker latency the clock misses stays inside the tail.
                            echoReference?.read(reference, clock.frameEndNs(offset / 2) + ECHO_REFERENCE_LEAD_NS)
                            val processed = try {
                                canceller.process(frame, reference)
                            } catch (error: RuntimeException) {
                                Log.w(TAG, "microphone native echo processing failed; restoring platform AEC", error)
                                false
                            } catch (error: LinkageError) {
                                Log.w(TAG, "microphone native echo processing unavailable; restoring platform AEC", error)
                                false
                            }
                            if (!processed) {
                                restorePlatformEchoCancellation()
                                canceller = null
                            }
                        }
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo)
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.rtpSamplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = runCatching { recorder.routedDevice?.type }.getOrNull()

    /** Capture can continue with the same recorder if the optional native processor stops working. */
    @Synchronized
    private fun restorePlatformEchoCancellation() {
        val current = echoCanceller ?: return
        echoCanceller = null
        current.close()
        if (!running.get()) return // close/release already owns all recorder effects.
        effects.filterIsInstance<NoiseSuppressor>().forEach { ns ->
            runCatching { ns.setEnabled(true) }
                .onFailure { Log.w(TAG, "microphone platform NS could not be restored", it) }
        }
        val aec = effects.filterIsInstance<AcousticEchoCanceler>().firstOrNull()
        if (aec != null) {
            val enabled = runCatching { aec.setEnabled(true) == AudioEffect.SUCCESS && aec.enabled }.getOrDefault(false)
            if (enabled) {
                Log.i(TAG, "microphone native echo processing stopped; platform AEC restored")
                return
            }
            effects = effects - aec
            releaseEffect(aec)
        }
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder!!.audioSessionId) else null
        }?.let { effects = effects + it }
        Log.i(TAG, "microphone native echo processing stopped; using platform effects")
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        echoCanceller?.close()
        echoCanceller = null
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        const val ECHO_TAIL_MILLIS = 250
        const val ECHO_REFERENCE_LEAD_NS = 30_000_000L
    }
}

/**
 * When each captured microphone sample was heard, on System.nanoTime's clock. Uses the recorder's
 * timestamp when the platform has one, otherwise assumes the newest sample arrived [FALLBACK_LATENCY_NS] ago.
 */
internal class CaptureClock(private val sampleRate: Int) {
    private val timestamp = android.media.AudioTimestamp()
    private var samplesRead = 0L
    private var lastReadSamples = 0
    private var lastReadEndNs = 0L

    fun read(recorder: AudioRecord, samples: Int) {
        samplesRead += samples
        lastReadSamples = samples
        val now = System.nanoTime()
        val stamped = runCatching {
            recorder.getTimestamp(timestamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
        }.getOrDefault(false)
        lastReadEndNs = if (stamped && timestamp.nanoTime > 0) {
            timestamp.nanoTime + (samplesRead - timestamp.framePosition) * 1_000_000_000L / sampleRate
        } else {
            now - FALLBACK_LATENCY_NS
        }
    }

    /** The capture time of the end of the first [samplesIntoRead] samples of the latest read. */
    fun frameEndNs(samplesIntoRead: Int): Long =
        lastReadEndNs - (lastReadSamples - samplesIntoRead).coerceAtLeast(0) * 1_000_000_000L / sampleRate

    private companion object {
        const val FALLBACK_LATENCY_NS = 20_000_000L
    }
}
