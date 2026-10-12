package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.hud.BydBluetoothSuspend
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes, var appliedVolume: Float = FULL_VOLUME)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequestCompat? = null
    private var requestedChannel: AudioChannel? = null
    private var mediaVolume = FULL_VOLUME
    private var closed = false
    private var focusGeneration = 0L
    private var currentListener = listenerFor(focusGeneration)
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            // Android may have queued callbacks before a request was abandoned or replaced.
            if (generation != focusGeneration || request == null || active.isEmpty()) {
                // Dropping this silently hides the one window that matters: between a telephony
                // track closing and media reopening, a head unit that keeps focus looks like
                // nothing at all. Say the callback arrived and why it was not acted on.
                runCatching {
                    report("Audio: focus change=$change dropped" +
                        " stale=${generation != focusGeneration} noRequest=${request == null}" +
                        " activeTracks=${active.size}")
                }
                return@synchronized
            }
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (muteMediaOnTransientLoss) {
                        // This reports a temporary Android focus owner, not a confirmed call.
                        Log.i(TAG, "Audio: muting media during transient focus loss")
                        setMediaVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
        applyMediaVolume(track, active.getValue(track))
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        active.clear()
        refreshRequest()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            val abandoned = request
            request = null
            requestedChannel = null
            mediaVolume = FULL_VOLUME
            manager?.let { abandoned?.abandon(it) }
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        focusGeneration += 1
        manager?.let { request?.abandon(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequestCompat(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        request = next
        requestedChannel = primary.channel
        val result = manager?.let(next::request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) setMediaVolume(FULL_VOLUME)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setMediaVolume(volume: Float) {
        mediaVolume = volume
        active.forEach { (track, entry) -> applyMediaVolume(track, entry) }
    }

    private fun applyMediaVolume(track: AudioTrack, entry: Entry) {
        // Telephony and assistant speech remain audible, and navigation never enters this map.
        // This changes only the renderer's relative gain, never Android's user stream volume.
        if (entry.channel != AudioChannel.MEDIA || entry.appliedVolume == mediaVolume) return
        val applied = runCatching { track.setStereoVolume(mediaVolume, mediaVolume) == AudioTrack.SUCCESS }.getOrDefault(false)
        if (applied) entry.appliedVolume = mediaVolume
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val audioFocusAutoYield: Boolean = true,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    /** Opt-in call echo cancellation, replacing a controllable platform canceller while available. */
    private val callEchoCancellation: Boolean = false,
    /** Cut the bass that head units add when they play a call as music. */
    private val callVoiceFilter: Boolean = false,
    /**
     * Smooth video: show main-screen frames at the iPhone's frame time plus a delay that starts here and
     * then follows how late the decoder releases frames ([PacingDelay]); 0 shows each as soon as it is
     * decoded. Only a SurfaceView honours the timestamps, so the host sets it with one.
     */
    private val videoPacingDelayMillis: Int = 0,
    /** The main screen's frame rate (the frame-rate setting), requested from its decoder as the operating rate; 0 for none. */
    private val mainVideoFrameRate: Int = 0,
    /**
     * Low-latency decoding (a setting): ask Qualcomm decoders to output each frame as soon as it is decoded,
     * and hand decoded frames to the screen from a thread of their own.
     */
    private val vendorLowLatencyDecoder: Boolean = false,
    /** Opt-in MTK ACodec low-latency/no-reorder modes, with per-session automatic fallback. */
    private val mtkDecoderTuningEnabled: Boolean = false,
) : MediaSink {
    // Each downlink publishes its own reference; a mic must match that stream and sample rate.
    private val callEchoReferences = ConcurrentHashMap<AudioStreamId, EchoReference>()
    private val appContext = context?.applicationContext
    private val ambientSinkToken = AmbientMusicController.openSink(appContext)
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        audioFocusAutoYield,
        onAudioDiagnostic,
    )

    /** The media-key session may own Android's current focus request for this same sink. */
    fun onMediaAudioFocusChanged(change: Int) {
        audioFocusCoordinator.onExternalFocusChange(change)
    }

    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private var defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    // Surface capture, worker publication, retirement and detach snapshots share one ownership boundary.
    // Native codec work and waits never run under this lock.
    private val videoOwnershipLock = Any()
    private var videoClosed = false
    private val videoReleasedListeners = ArrayList<() -> Unit>()
    // Closed decoders whose workers may still hold a codec on a surface until they exit; a surface
    // detach also waits for them.
    private val closingVideoDecoders = ConcurrentHashMap.newKeySet<VideoDecoder>()
    // How long a surface detach waits for decoders to confirm, and then for an unconfirmed one to exit
    // after it is closed. Variables so tests can shorten them.
    internal var detachTimeoutNanos = 2_000_000_000L
    internal var detachGraceNanos = 500_000_000L
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
        }
    }

    /**
     * Starts moving every decoder of this sink off [surface], which a SurfaceHolder destroy callback must
     * not return before. Each worker decides from the surface its codec actually renders to, after any
     * surface change queued before this: one already elsewhere has nothing to do. With [parkMain] the
     * main-screen decoder moves to an offscreen consumer it owns and keeps its state (smooth video, so the
     * picture can return at once); other decoders are released. Decoders closed earlier whose workers have
     * not exited yet are waited for too. [SurfaceDetach.await] returns the result.
     */
    fun beginSurfaceDetach(surface: Surface, parkMain: Boolean): SurfaceDetach {
        val deadline = System.nanoTime() + detachTimeoutNanos
        val requests = ArrayList<SurfaceDetachRequest>()
        val steps = ArrayList<() -> Boolean>()
        synchronized(videoOwnershipLock) {
            surfaces.entries.removeIf { it.value === surface } // future workers must not start on it
            if (defaultSurface === surface) defaultSurface = null
            for ((type, decoder) in videoDecoders.entries.toList()) {
                val request = decoder.requestDetach(surface, park = parkMain && type == MAIN_SCREEN_TYPE)
                requests += request
                steps += { settleDetach(type, decoder, request, deadline) }
            }
            closingVideoDecoders.removeIf { it.exited }
            closingVideoDecoders.toList().forEach { closing -> steps += { closing.awaitExitUntil(deadline + detachGraceNanos) } }
        }
        return SurfaceDetach(requests, steps)
    }

    /** A surface detach in flight across one sink's decoders; see [beginSurfaceDetach]. */
    class SurfaceDetach internal constructor(
        internal val requests: List<SurfaceDetachRequest>,
        private val steps: List<() -> Boolean>,
    ) {
        /**
         * Waits until every decoder has let go of the surface (moved, parked, released, or its worker
         * exited). False when one did not confirm in time and its worker, closed then, did not exit within
         * the grace period either; nothing more can be done from here, as with any native call that hangs.
         */
        fun await(): Boolean = steps.map { it() }.all { it }
    }

    private fun settleDetach(type: Int, decoder: VideoDecoder, request: SurfaceDetachRequest, deadline: Long): Boolean {
        if (decoder.awaitDetach(request, deadline)) return true
        Log.w("xcertplay-usb", "Video detach not confirmed in time type=$type; closing that decoder")
        videoDiagnosticHandlers[type]?.invoke("surface detach not confirmed; decoder closed")
        // The stream's next frame starts a fresh decoder on no surface, so it cannot compete for this one.
        retire(type, decoder)
        return decoder.awaitExitUntil(deadline + detachGraceNanos)
    }

    /** Closes a stream's decoder and keeps it visible to surface detaches until its worker exits. */
    private fun retire(type: Int, decoder: VideoDecoder) {
        synchronized(videoOwnershipLock) {
            // A timed-out detach can reach here after the worker has already completed cleanup. Its
            // onExit removed both registrations; do not republish it after its sole release notification.
            if (videoDecoders[type] !== decoder && decoder !in closingVideoDecoders) return
            closingVideoDecoders += decoder
            videoDecoders.remove(type, decoder)
            decoder.close()
        }
    }

    /** After [close], waits up to [timeoutMillis] for this sink's decoders to release their codecs. */
    fun awaitVideoReleased(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val closing = synchronized(videoOwnershipLock) { closingVideoDecoders.toList() }
        return closing.map { it.awaitExitUntil(deadline) }.all { it }
    }

    /** After [close], observes actual release of the tracked stream codecs, outside ownership locks. */
    fun whenVideoReleased(onReleased: () -> Unit) {
        val alreadyReleased = synchronized(videoOwnershipLock) {
            check(videoClosed) { "close before observing video release" }
            if (videoDecoders.isEmpty() && closingVideoDecoders.isEmpty()) true
            else {
                videoReleasedListeners += onReleased
                false
            }
        }
        if (alreadyReleased) runCatching(onReleased)
    }

    private fun onVideoDecoderExit(decoder: VideoDecoder) {
        val listeners = synchronized(videoOwnershipLock) {
            closingVideoDecoders -= decoder
            videoDecoders.entries.removeIf { it.value === decoder }
            if (videoClosed && videoDecoders.isEmpty() && closingVideoDecoders.isEmpty()) {
                videoReleasedListeners.toList().also { videoReleasedListeners.clear() }
            } else emptyList()
        }
        listeners.forEach { runCatching(it) }
    }

    /** True when this sink paces main-screen frames (smooth video); fixed for its lifetime. */
    val videoPacingEnabled: Boolean get() = videoPacingDelayMillis > 0

    /** Requests a keyframe for stream [type], e.g. after its surface came back. */
    /** Running totals of the main screen's decoder for a live FPS counter; null while it has none. */
    fun liveVideoCounters(): LiveVideoCounters? = videoDecoders[MAIN_SCREEN_TYPE]?.liveCounters()

    fun refreshPicture(type: Int) {
        videoDecoders[type]?.refreshPicture()
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val id = type to key
            synchronized(mirrorLock) {
                mirrorDecoders.remove(id)?.close()
                if (surface == null) {
                    mirrorSurfaces.remove(id)
                    return
                }
                mirrorSurfaces[id] = surface
            }
            lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
        }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(videoOwnershipLock) {
        if (videoClosed) return emptyList()
        synchronized(mirrorLock) {
            if (mirrorSurfaces.isEmpty()) return emptyList()
            mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
                mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
            }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        synchronized(videoOwnershipLock) {
            if (!videoClosed) pendingVideoCodec[type] = codec
        }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val codec = pendingVideoCodec[type] ?: VideoCodec.H264
            lastVideoConfig[type] = codec to codecData
            videoDecoder(type).configure(codec, codecData)
            mirrorDecoders(type).forEach { it.configure(codec, codecData) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes)
            mirrorDecoders(type).forEach { it.submit(naluBytes) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes, senderNanos, arrivalNanos)
            mirrorDecoders(type).forEach { it.submit(naluBytes) }
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            if (!active) {
                videoRecoveryHandlers.remove(type)
                videoDiagnosticHandlers.remove(type)
                videoDecoders[type]?.let { retire(type, it) }
                synchronized(mirrorLock) {
                    mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
                }
                lastVideoConfig.remove(type)
                pendingVideoCodec.remove(type)
            }
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        synchronized(this) {
            audioRenderers.remove(id)?.close()
            callEchoReferences.remove(id)
        }
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            val speakerphoneCall = config.audioType == "telephony" &&
                appContext != null && BydBluetoothSuspend.isSuspendedByUs(appContext)
            if (config.audioType == "telephony" && !speakerphoneCall) enterCommunicationMode(id)
            val uplink = microphoneUplinks.computeIfAbsent(id) {
                MicrophoneUplink(config, onAudioDiagnostic,
                    speakerphoneCall = speakerphoneCall,
                    echoReference = if (config.audioType == TELEPHONY_AUDIO_TYPE) callEchoReferences[id] else null)
            }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        try {
            microphoneUplinks.remove(id)?.close()
        } finally {
            restoreAudioMode(id)
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            savedAudioMode = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            Log.i("xcertplay-usb", "audio mode $savedAudioMode -> ${manager.mode} for telephony stream=$id")
            // The report, not just logcat: a head unit left in the voice path plays no media,
            // and that cannot be diagnosed from a car without adb. A failing report must not
            // disturb the mode, so it is the last thing done and it cannot throw out of here.
            runCatching {
                onAudioDiagnostic("Audio: mode entered communication from=$savedAudioMode" +
                    " now=${manager.mode} stream=$id")
            }
        }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            // The report is built inside the try but sent outside it: reporting from in there
            // would let a failing callback be caught as a failed restore.
            val line = try {
                manager.mode = savedAudioMode
                Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
                "Audio: mode restored requested=$savedAudioMode now=${manager.mode} stream=$active"
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not restore audio mode $savedAudioMode", error)
                val currentMode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
                "Audio: mode restore failed requested=$savedAudioMode now=$currentMode" +
                    " error=${error.javaClass.simpleName}"
            }
            runCatching { onAudioDiagnostic(line) }
        }
    }

    fun close() {
        AmbientMusicController.closeSink(ambientSinkToken)
        synchronized(videoOwnershipLock) {
            videoClosed = true
            videoDecoders.entries.toList().forEach { (type, decoder) -> retire(type, decoder) }
            synchronized(mirrorLock) {
                mirrorDecoders.values.forEach(VideoDecoder::close)
                mirrorDecoders.clear()
                mirrorSurfaces.clear()
            }
        }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        // Audio workers close asynchronously; none may reacquire focus after their sink closes.
        audioFocusCoordinator.close()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        callEchoReferences.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.values.forEach(MicrophoneUplink::close)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    private fun videoDecoder(type: Int): VideoDecoder = synchronized(videoOwnershipLock) {
        videoDecoders[type] ?: run {
            // A decoder that replaces a closed one picks up the stream's codec configuration.
            val decoder = newVideoDecoder(type, surfaces[type] ?: defaultSurface, startImmediately = false)
            videoDecoders[type] = decoder
            lastVideoConfig[type]?.let { (codec, data) -> decoder.configure(codec, data) }
            decoder.start()
            decoder
        }
    }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null, startImmediately: Boolean = true) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
        onExit = ::onVideoDecoderExit,
        // Only the main screen goes to the host's SurfaceView; mirrors and the cluster keep their path.
        pacingDelayNanos = if (type == MAIN_SCREEN_TYPE && statsLabel == null) videoPacingDelayMillis * 1_000_000L else 0L,
        operatingRate = videoOperatingRate(type, statsLabel, mainVideoFrameRate),
        vendorLowLatency = vendorLowLatencyDecoder,
        dedicatedOutputThread = vendorLowLatencyDecoder,
        framesAtOnce = type == MAIN_SCREEN_TYPE && statsLabel == null && videoPacingDelayMillis > 0,
        // Keep the experimental MTK mode on the main CarPlay picture only. Mirrors and the
        // cluster have separate timing and must retain their established decoder configuration.
        mtkDecoderTuningEnabled = mtkDecoderTuningEnabled && type == MAIN_SCREEN_TYPE && statsLabel == null,
    ).also { if (startImmediately) it.start() }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        // A replacement owns a fresh ring: the old worker can still finish a blocking write.
        // The car's phone audio state cancels the echo itself; a second canceller would work against it.
        val echoReference = if (callEchoCancellation && format.audioType == TELEPHONY_AUDIO_TYPE && format.sampleRate > 0 &&
            !PhoneAudioRoute.carPlayCallsUseCarAudio()) {
            EchoReference(format.sampleRate).also { callEchoReferences[id] = it }
        } else {
            callEchoReferences.remove(id)
            null
        }
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            ambientSinkToken,
            speakerphoneCall = format.audioType == "telephony" &&
                appContext != null && BydBluetoothSuspend.isSuspendedByUs(appContext),
            echoReference = echoReference,
            voiceFilter = callVoiceFilter && format.audioType == TELEPHONY_AUDIO_TYPE,
        ).also { audioRenderers[id] = it }
    }

    private companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
    }
}

/** CarPlay's main screen stream type. */
private const val MAIN_SCREEN_TYPE = 110

/**
 * An offscreen consumer a parked decoder renders to while its SurfaceView is gone. It drops each image
 * on its own thread, so it keeps draining while the main thread waits on a surface handoff. Only the
 * decoder worker creates and closes it, after the codec no longer renders to it.
 */
internal class ParkingOutput(width: Int, height: Int) {
    private val consumerLock = Any()
    private var closed = false
    private val thread = android.os.HandlerThread("carplay-video-parking").apply { start() }
    private val reader: android.media.ImageReader
    val surface: Surface
    /** The looper the consumer drains on, never the main one. */
    val listenerLooper: android.os.Looper get() = thread.looper

    init {
        try {
            reader = android.media.ImageReader.newInstance(
                width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.ImageFormat.PRIVATE, 3,
            )
            reader.setOnImageAvailableListener({ source ->
                synchronized(consumerLock) {
                    if (!closed) source.acquireLatestImage()?.close()
                }
            }, android.os.Handler(thread.looper))
            surface = reader.surface
        } catch (error: Throwable) {
            thread.quitSafely()
            throw error
        }
    }

    fun close() {
        try {
            synchronized(consumerLock) {
                if (!closed) {
                    closed = true
                    reader.close()
                }
            }
        } finally {
            thread.quitSafely()
        }
    }
}

/**
 * The operating rate requested from a stream's decoder, 0 for none: the main screen asks for the frame
 * rate the iPhone is asked for ([frameRate], the frame-rate setting). At 60 fps on my Tang, the Qualcomm
 * decoder's median time from queueing a frame to dequeueing its output was 8.5–9 ms lower with it.
 * Mirrors and the cluster stream keep the format they had.
 */
internal fun videoOperatingRate(type: Int, statsLabel: String?, frameRate: Int): Int =
    if (type == MAIN_SCREEN_TYPE && statsLabel == null && frameRate > 0) frameRate else 0

internal data class DecoderAttempt(
    val codecName: String?,
    val tuned: Boolean,
    val operatingRate: Int = 0,
    val mtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
)

/**
 * Configure attempts in order. Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
 * parameters with BAD_VALUE, so a minimal format and then software follow. An [operatingRate] is tried
 * first on its own, so a decoder that refuses it keeps the tuned format it gets without one. A
 * [lowLatencyDecoder] gets its tuned attempts before the default decoder's.
 */
internal fun videoDecoderAttempts(
    operatingRate: Int,
    softwareDecoder: String?,
    lowLatencyDecoder: String? = null,
    maximumMtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
): List<DecoderAttempt> = buildList {
    if (operatingRate > 0) lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true, operatingRate)) }
    lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true)) }
    val mtkModes = maximumMtkMode.attempts()
    if (operatingRate > 0) {
        mtkModes.forEach { add(DecoderAttempt(null, tuned = true, operatingRate, it)) }
    }
    mtkModes.forEach { add(DecoderAttempt(null, tuned = true, mtkMode = it)) }
    add(DecoderAttempt(codecName = null, tuned = false))
    softwareDecoder?.let { add(DecoderAttempt(it, tuned = false)) }
}

/**
 * The operating rate to ask for at the next configure: none once a later attempt ([used]) worked after
 * the rate was refused, so the refusal is not paid again; unchanged when every attempt failed.
 */
internal fun nextOperatingRate(requested: Int, used: DecoderAttempt?): Int =
    if (requested > 0 && used != null && used.operatingRate == 0) 0 else requested

/**
 * Qualcomm's vendor parameters that make its decoders output each frame as soon as it is decoded, in
 * decode order: without them c2.qti.avc.decoder released a frame only after about two more had been
 * queued (docs/SMOOTH_WIRELESS.md). Empty for other decoders. CarPlay's screen stream has no B-frames,
 * so decode order is display order.
 */
internal fun vendorLowLatencyKeys(codecName: String): List<String> =
    if (codecName.startsWith("c2.qti.") || codecName.startsWith("OMX.qcom.")) {
        listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")
    } else emptyList()

/** One regular decoder of the stream's type, as [lowLatencyDecoderName] sees it. */
internal data class DecoderCandidate(val name: String, val hardware: Boolean, val alias: Boolean, val lowLatency: Boolean)

/**
 * The decoder the main screen tries first with smooth video: the first hardware decoder (not an alias) that
 * advertises the low-latency feature, whose tuned format then asks for low latency. On my Tang this is
 * c2.qti.avc.decoder.low_latency; the default c2.qti.avc.decoder does not advertise the feature.
 */
internal fun lowLatencyDecoderName(candidates: List<DecoderCandidate>): String? =
    candidates.firstOrNull { it.lowLatency && it.hardware && !it.alias }?.name

/** The low-latency decoder to try at the next configure: none once another decoder ([used]) worked after it. */
internal fun nextLowLatencyDecoder(requested: String?, used: DecoderAttempt?): String? =
    if (requested != null && used != null && used.codecName != requested) null else requested

/**
 * Qualcomm's decoder parameter for output in decoding order. Without it my Tang's decoders released a frame
 * only once one or two later frames had been queued. Decoding order is the display order only for a stream
 * without reordered (B) frames; the iPhone's CarPlay H.264 had none. It is set only for the main screen's H.264
 * with smooth video, and only on a decoder that lists the parameter.
 */
internal const val PICTURE_ORDER_PARAMETER = "vendor.qti-ext-dec-picture-order.enable"

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    pacingDelayNanos: Long = 0L,
    /** Called on the worker as its last step, after it has released its codec. */
    private val onExit: (VideoDecoder) -> Unit = {},
    operatingRate: Int = 0,
    /** Set [vendorLowLatencyKeys] on the tuned configure attempts. */
    private val vendorLowLatency: Boolean = false,
    /**
     * Release decoded frames from a thread that waits on the codec's output, instead of when the worker
     * next comes back from its queue or from feeding: a frame is shown as soon as it is decoded.
     */
    dedicatedOutputThread: Boolean = false,
    /**
     * The main screen with smooth video: for H.264, try a low-latency decoder first ([lowLatencyDecoderName]) and
     * ask decoders that list it for [PICTURE_ORDER_PARAMETER], so each frame leaves the decoder as soon as it can.
     */
    private val framesAtOnce: Boolean = false,
    private val mtkDecoderTuningEnabled: Boolean = false,
) : Closeable {
    private val pacer = FramePacer()
    private val pacingDelay = if (pacingDelayNanos > 0) PacingDelay(pacingDelayNanos) else null
    // Per queued presentation time (decode thread only): when it was queued, and whether it is a paced
    // frame's local time rather than the queue time.
    private val queuedPresentationUs = LongArray(64)
    private val queuedAtNanos = LongArray(64)
    private val queuedPaced = BooleanArray(64)
    // Set on the paced frames queued just before a pause in the iPhone's frames: the decoder releases a
    // frame only once about two more are queued, so they waited for that pause, which no display delay
    // can hide.
    private val queuedBeforePause = BooleanArray(64)
    private val recentPacedSlots = IntArray(PAUSE_HELD_FRAMES) { -1 }
    private var queuedSlot = 0
    // The decoder releases a frame only once later input arrives, so a frame queued before a still-screen
    // gap waits for the next one; such waits are not decode time and are left out of the stats.
    private var lastQueuedNanos = 0L
    private var resumedAtNanos = 0L
    private var lastQueuedLocalNanos = 0L
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var parking: ParkingOutput? = null
    private var lastConfig: VideoJob.Config? = null
    @Volatile private var renderedFrameLogged = false
    // The operating rate still asked for, dropped for this stream once a decoder refuses it; and the rate
    // the current codec was configured with.
    private var operatingRate = operatingRate
    private var configuredRate = 0
    private var maximumMtkMode = if (mtkDecoderTuningEnabled) {
        MtkDecoderMode.LOW_LATENCY_NO_REORDER
    } else MtkDecoderMode.NONE
    private var configuredMtkMode = MtkDecoderMode.NONE
    private var configuredMtkCodec2LowLatency = false
    // Whether the vendor low-latency parameters were set on the codec being configured.
    private var configuredVendorLowLatency = false
    // Likewise the low-latency decoder, not tried again once another decoder had to take over; and what the
    // codec that tryConfigure started last was given.
    private var lowLatencyDecoderRefused = false
    private var startedLowLatency = false
    private var startedPictureOrder = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private val backlogRecovery = VideoBacklogRecovery()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == MAIN_SCREEN_TYPE) "" else " stream=$streamType")
    // Running totals for the live FPS counter, read from the UI thread.
    private val liveReceived = AtomicLong()
    private val liveRendered = AtomicLong()
    private val liveDecodeNanos = AtomicLong()
    private val liveDecodeSamples = AtomicLong()

    fun liveCounters() = LiveVideoCounters(
        received = liveReceived.get(),
        rendered = liveRendered.get(),
        decodeNanos = liveDecodeNanos.get(),
        decodeSamples = liveDecodeSamples.get(),
    )
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }
    private val outputThread = if (dedicatedOutputThread) {
        Thread(::runOutput, "carplay-video-output").apply { isDaemon = true }
    } else null
    // Guards the codec handoff between the worker and the output thread: [decoder] changes, [outputSurface]
    // and [parking] changes, the per-frame bookkeeping, and the output thread's handling of one buffer.
    // The output thread waits inside dequeueOutputBuffer without it.
    private val outputLock = Object()
    // The codec the output thread is waiting on; the worker waits for it to return before releasing that codec.
    private var dequeuing: MediaCodec? = null
    // A failure on the output thread, handled by the worker as if its own job had failed.
    @Volatile private var outputFailure: Exception? = null

    fun start() {
        thread.start()
        outputThread?.start()
    }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L) {
        stats.onReceived(nalus.size)
        liveReceived.incrementAndGet()
        // Pacing runs on the worker, in queue order: callbacks of a replaced stream may still deliver here.
        queue.offer(VideoJob.Frame(nalus, senderNanos = senderNanos, arrivalNanos = arrivalNanos))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    /** Asks the worker to stop rendering to [surface], parking the codec when [park]. */
    fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest {
        check(Thread.currentThread() !== thread) { "detach from the decoder worker" }
        return SurfaceDetachRequest(surface, park).also { queue.offer(VideoJob.DetachSurface(it)) }
    }

    /** True once [request] is confirmed or the worker has exited; false when [deadlineNanos] passed first. */
    fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean =
        request.await(deadlineNanos - System.nanoTime(), workerAlive = thread::isAlive)

    /** True once the worker has exited, so its codec is released. */
    val exited: Boolean get() = !thread.isAlive

    /**
     * After [close], waits until [deadlineNanos] for the worker to release its codec and exit. An
     * interrupt does not end the wait early; it is restored before returning.
     */
    fun awaitExitUntil(deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (thread.isAlive) {
                val leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0) break
                try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    fun refreshPicture() {
        queue.offer(VideoJob.RefreshPicture)
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                outputFailure?.let { failure ->
                    outputFailure = null
                    onJobFailure("output", failure)
                }
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            val backlog = queue.backlogAfterCurrent()
                            val overloaded = !referenceChain.needsKeyFrame && backlogRecovery.observe(
                                System.nanoTime(), job.receivedNs,
                                backlog.pendingFrames, backlog.newestPendingReceivedNs,
                            )
                            if (overloaded) {
                                recover("video backlog kept growing or exceeded hard limit")
                            } else feed(job.nalus, job.receivedNs, localTimeOf(job))
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        // Once per new surface, so it skips the rate limit an earlier request may still hold.
                        is VideoJob.RefreshPicture -> if (decoder != null) {
                            lastKeyFrameRequestNs = System.nanoTime()
                            requestKeyFrame()
                        }
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    if (outputThread == null) decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    onJobFailure(job?.javaClass?.simpleName ?: "drain", error)
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            running = false
            releaseDecoder()
            outputThread?.let { output ->
                output.interrupt()
                joinUninterruptibly(output, OUTPUT_EXIT_WAIT_MS)
            }
            // The codec is gone, so nothing renders to any surface or to the parking consumer any more.
            parking?.close()
            parking = null
            queue.drain().forEach { (it as? VideoJob.DetachSurface)?.request?.complete(DetachOutcome.RELEASED) }
            runCatching { onExit(this) }
        }
    }

    private fun onJobFailure(stage: String, error: Exception) {
        if (running) Log.e(TAG, "video decoder job failed: $stage", error)
        if (running) reportFailure("stage=$stage", error)
        // MTK vendor failures may surface as CodecException or the broader IllegalStateException.
        // Retire the active experimental mode before recreating the decoder.
        if (error is IllegalStateException) {
            dropMtkTuning("codec failed during $stage")
            dropMtkCodec2LowLatency("codec failed during $stage")
        }
        // A codec can accept the rate and still fail once it runs (start errors may surface on
        // later calls): one that failed before its first frame with it is not given it again.
        if (configuredRate > 0 && !renderedFrameLogged && error is MediaCodec.CodecException) {
            dropOperatingRate("codec failed before its first frame")
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /** The output thread: waits on the current codec's output and releases each frame as it comes. */
    private fun runOutput() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        val info = MediaCodec.BufferInfo()
        while (running) {
            val codec = synchronized(outputLock) {
                val current = decoder
                if (current == null || outputFailure != null) {
                    try { outputLock.wait(OUTPUT_IDLE_WAIT_MS) } catch (_: InterruptedException) { return }
                    null
                } else {
                    dequeuing = current
                    current
                }
            } ?: continue
            var index = MediaCodec.INFO_TRY_AGAIN_LATER
            var failure: Exception? = null
            try {
                index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (error: Exception) {
                failure = error
            }
            synchronized(outputLock) {
                dequeuing = null
                outputLock.notifyAll()
                // A codec the worker released or replaced meanwhile has no buffers left to hand out.
                if (decoder === codec) {
                    try {
                        if (failure != null) throw failure
                        handleOutput(codec, index, info)
                    } catch (error: Exception) {
                        if (running) outputFailure = error
                    }
                }
            }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        val requestedRate = operatingRate
        val atOnce = framesAtOnce && codec == VideoCodec.H264
        val requestedMtkCodec2 = if (mtkDecoderTuningEnabled && !lowLatencyDecoderRefused) {
            MtkDecoderTuning.codec2LowLatencyDecoderName(mime)
        } else null
        val requestedLowLatency = requestedMtkCodec2
            ?: if (atOnce && !lowLatencyDecoderRefused) findLowLatencyDecoder(mime) else null
        val requestedMtkMode = if (mtkDecoderTuningEnabled &&
            MtkDecoderTuning.legacyAcodecDecoderName(mime) != null) maximumMtkMode else MtkDecoderMode.NONE
        var configured: ConfiguredVideoDecoder? = null
        var used: DecoderAttempt? = null
        for (attempt in videoDecoderAttempts(
            requestedRate,
            softwareDecoderName(mime),
            requestedLowLatency,
            requestedMtkMode,
        )) {
            configured = tryConfigure(mime, csd, surface, attempt, atOnce)
            if (configured != null) { used = attempt; break }
        }
        if (configured == null) {
            queue.discardCurrentChain()
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        if (nextOperatingRate(requestedRate, used) != requestedRate) dropOperatingRate("refused at configure")
        if (nextLowLatencyDecoder(requestedLowLatency, used) != requestedLowLatency) {
            report("low-latency decoder $requestedLowLatency dropped: refused at configure")
            lowLatencyDecoderRefused = true
        }
        renderedFrameLogged = false
        submittedFrameLogged = false
        synchronized(outputLock) {
            decoder = configured?.codec
            outputLock.notifyAll()
        }
        configuredRate = used?.operatingRate ?: 0
        configuredMtkMode = configured?.mtkMode ?: MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = configured != null && requestedMtkCodec2 != null &&
            configured.codecName.equals(requestedMtkCodec2, ignoreCase = true)
        if (configured != null && MtkDecoderTuning.supportsLegacyAcodecKeys(configured.codecName) &&
            configuredMtkMode.ordinal < requestedMtkMode.ordinal) {
            report("MTK decoder tuning fell back at configure from=${requestedMtkMode.diagnosticName} " +
                "to=${configuredMtkMode.diagnosticName}")
            maximumMtkMode = configuredMtkMode
        }
        if (configured != null) {
            val rate = if (requestedRate > 0) " operatingRate=$configuredRate" else ""
            val lowLatency = if (vendorLowLatency) " vendorLowLatency=$configuredVendorLowLatency" else ""
            val output = if (outputThread != null) " outputThread=true" else ""
            val atOnceReport = if (atOnce) " lowLatency=$startedLowLatency pictureOrder=$startedPictureOrder" else ""
            val mtk = when {
                configuredMtkCodec2LowLatency -> " mtkTuning=codec2-low-latency"
                MtkDecoderTuning.isDecoderName(configured.codecName) ->
                    " mtkTuning=${configuredMtkMode.diagnosticName}"
                else -> ""
            }
            report("decoder=${configured.codecName} mime=$mime size=${width}x$height" +
                "$rate$lowLatency$output$atOnceReport$mtk")
            Log.i(
                TAG,
                "video decoder configured name=${configured.codecName} mime=$mime size=${width}x$height$mtk",
            )
        }
    }

    private fun dropOperatingRate(reason: String) {
        report("operating rate $operatingRate dropped: $reason")
        operatingRate = 0
    }

    private fun dropMtkTuning(reason: String) {
        val previous = configuredMtkMode
        if (previous == MtkDecoderMode.NONE) return
        val fallback = previous.fallback()
        if (fallback.ordinal < maximumMtkMode.ordinal) maximumMtkMode = fallback
        report("MTK decoder tuning ${previous.diagnosticName} dropped: $reason; " +
            "next=${fallback.diagnosticName}")
    }

    private fun dropMtkCodec2LowLatency(reason: String) {
        if (!configuredMtkCodec2LowLatency) return
        configuredMtkCodec2LowLatency = false
        lowLatencyDecoderRefused = true
        report("MTK Codec2 low-latency decoder dropped: $reason; next=default")
    }

    private fun buildFormat(
        mime: String,
        csd: List<ByteArray>,
        attempt: DecoderAttempt,
        mtkMode: MtkDecoderMode,
    ): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (attempt.tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (attempt.operatingRate > 0) setInteger(MediaFormat.KEY_OPERATING_RATE, attempt.operatingRate)
            }
            mtkMode.formatKeys().forEach { setInteger(it, 1) }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private data class ConfiguredVideoDecoder(
        val codec: MediaCodec,
        val codecName: String,
        val mtkMode: MtkDecoderMode,
    )

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
        /** Ask for [PICTURE_ORDER_PARAMETER] when the decoder lists it ([framesAtOnce], H.264). */
        atOnce: Boolean = false,
    ): ConfiguredVideoDecoder? {
        var candidate: MediaCodec? = null
        var mtkMode = MtkDecoderMode.NONE
        var codecName = attempt.codecName ?: "default"
        var phase = "create"
        var phaseStartNs = System.nanoTime()
        val startedNs = phaseStartNs
        return try {
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            val createdName = runCatching { codec.name }.getOrNull().orEmpty()
            codecName = createdName.ifEmpty { codecName }
            mtkMode = appliedMtkDecoderMode(attempt.mtkMode, createdName)
            val format = buildFormat(mime, csd, attempt, mtkMode)
            phase = "configure"
            phaseStartNs = System.nanoTime()
            val lowLatency = attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val vendorKeys = if (attempt.tuned && vendorLowLatency) vendorLowLatencyKeys(codec.name.orEmpty()) else emptyList()
            vendorKeys.forEach { format.setInteger(it, 1) }
            configuredVendorLowLatency = vendorKeys.isNotEmpty()
            val pictureOrder = attempt.tuned && atOnce && listsPictureOrder(codec)
            if (pictureOrder) format.setInteger(PICTURE_ORDER_PARAMETER, 1)
            codec.configure(format, surface, null, 0)
            phase = "start"
            phaseStartNs = System.nanoTime()
            codec.start()
            startedLowLatency = lowLatency
            startedPictureOrder = pictureOrder
            val configuredName = runCatching { codec.name }.getOrNull().orEmpty()
                .ifEmpty { createdName }
                .ifEmpty { attempt.codecName ?: "default" }
            ConfiguredVideoDecoder(codec, configuredName, mtkMode)
        } catch (error: Exception) {
            val phaseMs = (System.nanoTime() - phaseStartNs) / 1_000_000
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            runCatching { candidate?.release() }.onFailure {
                Log.w(TAG, "video decoder candidate release failed name=$codecName", it)
            }
            reportFailure("stage=configure phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                "mtkTuning=${mtkMode.diagnosticName} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=$codecName phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                    "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                    "mtkTuning=${mtkMode.diagnosticName} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    /** Android 11 added the low-latency feature; aliases and software-only flags exist from Android 10. */
    private fun findLowLatencyDecoder(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            lowLatencyDecoderName(MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && mime in it.supportedTypes }
                .map { info ->
                    DecoderCandidate(
                        name = info.name,
                        hardware = info.isHardwareAccelerated,
                        alias = info.isAlias,
                        lowLatency = info.getCapabilitiesForType(mime).isFeatureSupported("low-latency"),
                    )
                })
        }.getOrNull()
    }

    /** Android 12 lets a codec list its vendor parameters. */
    private fun listsPictureOrder(codec: MediaCodec): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { PICTURE_ORDER_PARAMETER in codec.supportedVendorParameters }.getOrDefault(false)

    private fun softwareDecoderName(mime: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
        }?.name
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun localTimeOf(frame: VideoJob.Frame): Long =
        if (pacingDelay != null && frame.senderNanos > 0 && frame.arrivalNanos > 0) {
            pacer.localTime(frame.senderNanos, frame.arrivalNanos)
        } else 0L

    /** Completes [request] once the codec no longer renders to its surface, whatever happens here. */
    private fun detach(request: SurfaceDetachRequest): Unit = synchronized(outputLock) {
        var outcome = DetachOutcome.RELEASED
        try {
            outcome = detachAction(outputSurface, request.surface, request.park, decoder != null)
            when (outcome) {
                DetachOutcome.NOT_RENDERING -> Unit
                DetachOutcome.PARKED -> {
                    val consumer = parking ?: ParkingOutput(width, height).also { parking = it }
                    try {
                        checkNotNull(decoder).setOutputSurface(consumer.surface)
                        outputSurface = consumer.surface
                        Log.i(TAG, "video decoder parked off screen")
                    } catch (error: Exception) {
                        Log.w(TAG, "video decoder could not park; releasing it", error)
                        outcome = DetachOutcome.RELEASED
                        outputSurface = null
                        releaseDecoder()
                    }
                }
                DetachOutcome.RELEASED -> {
                    outputSurface = null
                    releaseDecoder()
                    Log.i(TAG, "video decoder detached from surface")
                }
            }
        } catch (error: Exception) {
            outcome = DetachOutcome.RELEASED
            outputSurface = null
            releaseDecoder()
            throw error
        } finally {
            request.complete(outcome)
        }
    }

    private fun changeSurface(surface: Surface?): Unit = synchronized(outputLock) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            queue.discardCurrentChain()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray, receivedNs: Long, presentNs: Long = 0L) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        // A slow rebuild must not immediately restart on its obsolete triggering IDR.
        if (referenceChain.needsKeyFrame && VideoRecoveryFrameAge.isObsolete(
                System.nanoTime(), receivedNs, queue.backlogAfterCurrent(),
            )) {
            queue.discardCurrentChain()
            backlogRecovery.reset()
            requestKeyFrameIfDue()
            return
        }
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        val index = DecoderInputPump.acquire(
            running = { running }, drain = { if (outputThread == null) drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            dropMtkTuning("video decoder input stalled")
            dropMtkCodec2LowLatency("video decoder input stalled")
            recover("video decoder input stalled")
            return
        }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            // A paced frame carries its local time; MediaCodec returns it with the decoded output.
            val presentationUs = if (presentNs > 0) presentNs / 1000 else System.nanoTime() / 1000
            // Recorded before queueing: the output thread may dequeue the frame as soon as it is queued.
            synchronized(outputLock) {
                val queuedNow = System.nanoTime()
                if (lastQueuedNanos != 0L && queuedNow - lastQueuedNanos > STILL_GAP_NS) resumedAtNanos = queuedNow
                lastQueuedNanos = queuedNow
                queuedPresentationUs[queuedSlot] = presentationUs
                queuedAtNanos[queuedSlot] = queuedNow
                queuedPaced[queuedSlot] = presentNs > 0
                queuedBeforePause[queuedSlot] = false
                if (presentNs > 0) {
                    // A gap between consecutive iPhone frame times, not a decoder backlog, marks a pause.
                    if (lastQueuedLocalNanos != 0L && presentNs - lastQueuedLocalNanos > SENDER_PAUSE_NS) {
                        recentPacedSlots.forEach { if (it >= 0) queuedBeforePause[it] = true }
                    }
                    lastQueuedLocalNanos = presentNs
                    recentPacedSlots.copyInto(recentPacedSlots, 1, 0, PAUSE_HELD_FRAMES - 1)
                    recentPacedSlots[0] = queuedSlot
                }
                queuedSlot = (queuedSlot + 1) % queuedPresentationUs.size
            }
            codec.queueInputBuffer(index, 0, annexB.size, presentationUs, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        if (outputThread == null) drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun queuedSlotOf(presentationUs: Long): Int =
        queuedPresentationUs.indices.firstOrNull { queuedPresentationUs[it] == presentationUs } ?: -1

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            if (!handleOutput(codec, codec.dequeueOutputBuffer(info, 0), info)) return
        }
    }

    /** Handles one dequeueOutputBuffer result; false when there is nothing more to drain for now. */
    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): Boolean {
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
            index >= 0 -> {
                val render = outputSurface != null
                // Parked output goes to the offscreen consumer: it keeps the codec's state, but it is
                // not shown, so it is neither paced nor counted.
                val shown = render && outputSurface !== parking?.surface
                val now = System.nanoTime()
                val slot = queuedSlotOf(info.presentationTimeUs)
                val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
                val heldOverGap = queued in 1 until resumedAtNanos
                if (queued > 0 && !heldOverGap) {
                    stats.onDecodeLatency(now - queued)
                    liveDecodeNanos.addAndGet(now - queued)
                    liveDecodeSamples.incrementAndGet()
                }
                val delay = pacingDelay
                val paced = shown && delay != null && slot >= 0 && queuedPaced[slot]
                val localNs = info.presentationTimeUs * 1000
                val pauseHeld = paced && queuedBeforePause[slot]
                val targetNs = if (paced && delay != null) {
                    if (!heldOverGap && !pauseHeld) delay.onFrame(now - localNs)
                    stats.onPacingDelay(delay.nanos)
                    localNs + delay.nanos
                } else 0L
                if (targetNs - now in 1..MAX_PACING_AHEAD_NS) {
                    codec.releaseOutputBuffer(index, targetNs)
                } else {
                    if (shown && delay != null && !heldOverGap && !pauseHeld) stats.onLate()
                    codec.releaseOutputBuffer(index, render)
                }
                if (shown) {
                    stats.onRendered()
                    liveRendered.incrementAndGet()
                }
                if (shown && !renderedFrameLogged) {
                    renderedFrameLogged = true
                    report("first frame rendered")
                    Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
            }
            else -> return false
        }
        return true
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    // Worker only; [outputLock] orders it against the output thread.
    private fun releaseDecoder() {
        backlogRecovery.reset()
        val codec: MediaCodec?
        synchronized(outputLock) {
            codec = decoder
            decoder = null
            // The output thread returns from dequeueOutputBuffer within its timeout and then sees no codec.
            awaitNotDequeuing(codec)
        }
        configuredRate = 0
        configuredMtkMode = MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = false
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    /** Waits, holding [outputLock], until the output thread no longer waits on [codec]; keeps an interrupt. */
    private fun awaitNotDequeuing(codec: MediaCodec?) {
        if (codec == null) return
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OUTPUT_EXIT_WAIT_MS)
        while (dequeuing === codec) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try { outputLock.wait(leftMillis) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joinUninterruptibly(thread: Thread, timeoutMillis: Long) {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (thread.isAlive) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        // The output thread's wait for a decoded frame; a release waits at most this long for it to return.
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_IDLE_WAIT_MS = 10L
        const val OUTPUT_EXIT_WAIT_MS = 500L
        const val INPUT_TIMEOUT_US = 10_000L
        // A paced frame more than this ahead is treated as a bad mapping and shown at once.
        const val MAX_PACING_AHEAD_NS = 1_000_000_000L
        // Longer input gaps are a still screen, not decoding work.
        const val STILL_GAP_NS = 500_000_000L
        // Consecutive frames this far apart in iPhone time are a pause in its frames: about four frame
        // intervals at 30 fps.
        const val SENDER_PAUSE_NS = 120_000_000L
        // Frames before a pause that wait for it: the decoder holds about two, sometimes three.
        const val PAUSE_HELD_FRAMES = 3
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

/** Use the existing hard backlog age only when a newer frame remains in this control segment. */
internal object VideoRecoveryFrameAge {
    fun isObsolete(nowNs: Long, receivedNs: Long, backlog: VideoDecodeQueue.Backlog): Boolean =
        nowNs >= receivedNs && nowNs - receivedNs >= 1_500_000_000L &&
            backlog.pendingFrames > 0 && backlog.newestPendingReceivedNs?.let {
                it <= nowNs && it - receivedNs >= 100_000_000L
            } == true
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val ambientSinkToken: Long,
    private val speakerphoneCall: Boolean = false,
    /** Receives the played call audio so the call microphone can cancel its echo. */
    private val echoReference: EchoReference? = null,
    voiceFilter: Boolean = false,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val pcmChannels = if (format.channels >= 2) 2 else 1
    private val voiceFilter = if (voiceFilter && format.sampleRate > 0) VoiceFilter(format.sampleRate, pcmChannels) else null

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val navigationPlaybackToken = NavigationPlayback.open()
    private var actualLegacyStreamType: Int? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private val ambientMedia = AmbientMusicActivityPolicy.acceptsAudio(format.audioType, format.payloadType)
    private val ambientEnvelope = AmbientMusicEnvelope()
    private val ambientBass = AmbientMusicBassAnalyzer(format.sampleRate, pcmChannels)
    private var ambientRendererToken = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        AmbientMusicController.closeRenderer(ambientRendererToken)
        running = false
        NavigationPlayback.close(navigationPlaybackToken)
        thread.interrupt()
    }

    private fun run() {
        try {
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        actualLegacyStreamType = streamOverride.takeIf { it != 0 }
        var attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        if (streamOverride == 0) {
            attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    actualLegacyStreamType = null
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = audioAttributesFor(selection)
                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        if (ambientMedia) {
            ambientRendererToken = AmbientMusicController.openRenderer(ambientSinkToken, ambientEnvelope) {
                built.playbackHeadPosition to (running && built.playState == AudioTrack.PLAYSTATE_PLAYING)
            }
        }
        diagnosticStage = "track-attributes"
        trackAttributes = audioTrackAttributesForFocus(built, attributes)
        diagnosticStage = "track-capacity"
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} " +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"}"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        return if (speakerphoneCall) AudioChannelMapper.playCallOnSpeaker(selection) else selection
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance intentionally takes no focus: it overlays media without ducking it.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        // Android 7.x has no assistant usage and would record the track as an unknown usage.
        AudioChannel.ASSISTANT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes.USAGE_ASSISTANT
        } else {
            AudioAttributes.USAGE_MEDIA
        }
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        diagnosticStage = "decoder-input"
        // While a blocking track write holds this thread, decoded output fills up and the decoder
        // stops taking input. Dropping the packet after one 10 ms wait cut ~21 ms of music every
        // 10-20 s on DiLink 3.0 (#381); drain the output and wait again instead. One drain and
        // retry is enough for that, and the pump always makes it. Waiting the video's 500 ms
        // for every packet let a slowed decoder fall ever further behind instead: with the
        // CarPlay screen in the background, the queue grew to 190 packets and the track
        // underran every few seconds (#649).
        val index = DecoderInputPump.acquire(
            running = { running }, drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
            timeoutNs = AUDIO_INPUT_WAIT_NS,
        )
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = MediaCodec.BufferInfo()
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    var copied = false
                    try {
                        if (size > 0) {
                            val output = codec.getOutputBuffer(index)
                            if (output != null) {
                                if (size > pcm.size) pcm = ByteArray(size)
                                output.position(info.offset)
                                output.limit(info.offset + size)
                                output.get(pcm, 0, size)
                                copied = true
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (copied) writePcm(pcm, 0, size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        voiceFilter?.process(data, offset, length)
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            val framesWritten = count / frameBytes
            if (ambientMedia && AmbientMusicController.wantsPcm(ambientRendererToken)) {
                ambientEnvelope.append(totalWrittenFrames, framesWritten.toLong(),
                    AmbientMusicEnvelope.pcmRms(data, offset + written, count),
                    ambientBass.rms(data, offset + written, count))
            }
            written += count
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (playbackStarted) reportNavigationPlayback(track)
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
            echoReference?.let { reference ->
                val pending = if (playbackStarted) {
                    totalWrittenFrames - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                } else {
                    null
                }
                reference.append(data, offset + written - count, count, pcmChannels, pending, System.nanoTime())
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.underrunCount
        track.play()
        playbackStarted = true
        reportNavigationPlayback(track)
    }

    private fun reportNavigationPlayback(track: AudioTrack) {
        if (!running) return
        val priorityVoice = mappedChannel == AudioChannel.PHONE || mappedChannel == AudioChannel.ASSISTANT
        if (mappedChannel != AudioChannel.NAVIGATION && !priorityVoice) return
        // The optional key-routing hint must never interrupt audio on unusual vendor ROMs.
        runCatching {
            val pendingFrames = bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes
            val bufferedMillis = (pendingFrames * 1000L + format.sampleRate - 1L) / format.sampleRate
            NavigationPlayback.played(navigationPlaybackToken, actualLegacyStreamType, bufferedMillis, priorityVoice)
        }
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition,
                startThresholdBytes / 2L)) {
            // The hardware buffer has starved below the recovery floor. Pause without flushing
            // or discarding PCM, then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            // Pausing retains queued PCM. Count it toward the restart threshold so a
            // blocking write cannot fill the paused track before we call play().
            prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                .coerceAtMost(startThresholdBytes.toLong()).toInt()
            lastPcmWriteNs = System.nanoTime()
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.underrunCount ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        // "PHONE " marks the iPhone's own log lines, which the report drops, and it dropped every
        // call's playback line with it (#647).
        val channelName = if (mappedChannel == AudioChannel.PHONE) "CALL" else mappedChannel?.name
        val line = "audio stats audioType=${format.audioType} channel=$channelName " +
            "routeType=${currentTrack?.routedDevice?.type ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        NavigationPlayback.close(navigationPlaybackToken)
        AmbientMusicController.closeRenderer(ambientRendererToken)
        abandonAudioFocus()
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_INPUT_WAIT_NS = 100_000_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}
