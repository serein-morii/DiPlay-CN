package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; some head units pick the session of the
 * audio-focus owner. DiPlay normally holds focus and an active session until the CarPlay session
 * ends, but focus can be disabled on head units that otherwise route physical volume buttons to
 * DiPlay. Keys go to the iPhone as CarPlay media HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeAmbientArtwork,
        publish = ::onArtworkDecoded,
        discard = { it.bitmap.recycle() },
    )
    private var artworkOwner: Any? = null
    private val ambientColorCache = LinkedHashMap<Int, Int?>()
    private var initialAlbumRecovery = InitialAlbumRecovery()
    private var initialAlbumRetry: Runnable? = null
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var focusOwner: Any? = null
    private var focusEventRevision = 0L
    private var focusHeld = false
    /** Some car firmware never sends a gain callback after the phone call ends. */
    private var transientFocusInterrupted = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        GeelyWheelKeys.start(context)
        com.shilapi.xcertplay.media.AmbientMusicController.claimPlaybackOwner(next)
        next.playbackListener = { playing ->
            com.shilapi.xcertplay.media.AmbientMusicController.phonePlaybackChanged(next, playing)
            onIphonePlaying(next, playing)
        }
        next.nowPlayingListener = { _ -> onNowPlayingChanged(next) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
        val retained = next.nowPlayingSnapshot()
        onNowPlayingChanged(next)
        next.matchingArtworkSnapshot(retained.artworkTransferId)?.let { onArtworkChanged(next, it.id, it.bytes) }
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        com.shilapi.xcertplay.media.AmbientMusicController.releasePlaybackOwner(expected)
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        GeelyWheelKeys.stop()
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post {
            synchronized(this) {
                if (controller === expected) regainFocusLocked(onIphonePlaybackResume = true)
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                // Resolve retained state at delivery: a queued callback cannot roll a replay back.
                val update = expected.nowPlayingSnapshot()
                val previousArtwork = artwork
                if (albumTrackChanged(nowPlaying, update)) {
                    val previousId = nowPlaying.artworkTransferId
                    if (previousId != null) {
                        ambientColorCache.remove(previousId)
                        artworkCache.remove(previousId)
                    }
                    if (previousId == update.artworkTransferId) {
                        artworkOwner = artworkQueue.newSession()
                        initialAlbumRecovery = InitialAlbumRecovery()
                    }
                    com.shilapi.xcertplay.media.AmbientMusicController.albumColorChanged(expected,
                        update.artworkTransferId?.let { ambientColorCache[it] })
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                if (metadataChanged) ClusterMusicState.publish(update, update.artworkTransferId?.let { artworkCache[it] })
                updateInitialAlbumRecovery(expected)
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, and on a DiLink 5.0 Tang that exhausted memory within
                // minutes. The position goes in the playback state.
                if (metadataChanged) session?.setMetadata(androidMetadata(update, shownArtworkLocked()))
                publishPlaybackStateLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, result: AmbientArtwork?) {
        val decoded = result?.bitmap
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        ambientColorCache.remove(id)
        ambientColorCache[id] = result?.color
        while (ambientColorCache.size > MAX_CACHED_ARTWORK) ambientColorCache.remove(ambientColorCache.keys.first())
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            ClusterMusicState.publish(nowPlaying, artwork)
            controller?.let { com.shilapi.xcertplay.media.AmbientMusicController.albumColorChanged(it, result?.color) }
            controller?.let(::updateInitialAlbumRecovery)
            session?.setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
        }
    }

    private fun hasCurrentAlbumColor(): Boolean =
        nowPlaying.artworkTransferId?.let { ambientColorCache.containsKey(it) } == true

    private fun albumRecoveryEnabled(): Boolean {
        val settings = appContext?.let(com.shilapi.xcertplay.media.AmbientMusicSettings::load) ?: return false
        return settings.enabled && settings.colorSource == com.shilapi.xcertplay.media.AmbientColorSource.ALBUM
    }

    private fun logAlbumRecoveryEvents(recovery: InitialAlbumRecovery) {
        recovery.takeEvents().forEach { Log.i(TAG, "album recovery=$it") }
        if (!recovery.timerPending) {
            initialAlbumRetry?.let(mainHandler::removeCallbacks)
            initialAlbumRetry = null
        }
    }

    private fun updateInitialAlbumRecovery(expected: CarPlayController) {
        val recovery = initialAlbumRecovery
        val schedule = recovery.observe(nowPlaying, hasCurrentAlbumColor(), ::albumRecoveryEnabled)
        logAlbumRecoveryEvents(recovery)
        if (!schedule) return
        initialAlbumRetry?.let(mainHandler::removeCallbacks)
        initialAlbumRetry = null
        val token = recovery.pendingToken
        Log.i(TAG, "album recovery=scheduled reference=${nowPlaying.artworkTransferId}")
        val retry = Runnable {
            synchronized(this) {
                if (controller !== expected || initialAlbumRecovery !== recovery) return@Runnable
                val begin = recovery.beginRequest(token, nowPlaying, hasCurrentAlbumColor(), ::albumRecoveryEnabled)
                logAlbumRecoveryEvents(recovery)
                if (!begin) return@Runnable
                artworkQueue.execute(Runnable { expected.refreshInitialNowPlaying(
                    stillNeeded = {
                        synchronized(this) {
                            val needed = controller === expected && initialAlbumRecovery === recovery &&
                                recovery.stillNeeded(token, nowPlaying, hasCurrentAlbumColor(), ::albumRecoveryEnabled)
                            if (initialAlbumRecovery === recovery) logAlbumRecoveryEvents(recovery)
                            needed
                        }
                    },
                    beforeSend = {
                        synchronized(this) {
                            val claimed = controller === expected && initialAlbumRecovery === recovery &&
                                recovery.claimSend(token, nowPlaying, hasCurrentAlbumColor(), ::albumRecoveryEnabled)
                            if (initialAlbumRecovery === recovery) logAlbumRecoveryEvents(recovery)
                            if (claimed) Log.i(TAG, "album recovery=requested reference=${nowPlaying.artworkTransferId}")
                            claimed
                        }
                    },
                    onFinished = { result ->
                        synchronized(this) {
                            if (controller === expected && initialAlbumRecovery === recovery) {
                                recovery.finished(token)
                                val state = when (result) {
                                    CarPlayController.InitialAlbumRefreshResult.SENT -> "sent"
                                    CarPlayController.InitialAlbumRefreshResult.FAILED -> "failed"
                                    else -> "skipped reason=${result.name.lowercase()}"
                                }
                                Log.i(TAG, "album recovery=$state")
                                logAlbumRecoveryEvents(recovery)
                            }
                        }
                    },
                ) })
            }
        }
        initialAlbumRetry = retry
        mainHandler.postDelayed(retry, 2000)
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() = regainFocusLocked(onIphonePlaybackResume = false)

    private fun regainFocusLocked(onIphonePlaybackResume: Boolean) {
        val request = focusRequest ?: return
        // Do not compete with an ongoing call. Only a fresh iPhone play transition may
        // retry a transient loss for which the head unit never sent AUDIOFOCUS_GAIN.
        if (transientFocusInterrupted && !onIphonePlaybackResume) return
        if (focusHeld && !transientFocusInterrupted) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        focusHeld = request.request(audio) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) {
            transientFocusInterrupted = false
            forwardGrantedFocusLocked()
        }
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        publishPlaybackStateLocked()
    }

    private fun start(context: Context) {
        val expectedController = controller ?: return
        val focusDisabled = AirPlayPersistence.loadDisableMediaKeyAudioFocus(context)
        transientFocusInterrupted = false
        val granted = if (focusDisabled) {
            // Some OEMs route physical volume buttons to the focus owner. Keep an active
            // MediaSession without permanent focus so those buttons remain vehicle-owned.
            focusOwner = null
            focusRequest = null
            focusHeld = false
            false
        } else {
            val owner = Any().also { focusOwner = it }
            focusEventRevision = 0L
            val audio = context.getSystemService(AudioManager::class.java)
            val request = AudioFocusRequestCompat(
                AudioManager.AUDIOFOCUS_GAIN,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
                { change -> onFocusChanged(expectedController, owner, change) },
                mainHandler,
            )
            focusRequest = request
            (audio?.let(request::request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also {
                focusHeld = it
                if (it) forwardGrantedFocusLocked()
            }
        }
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(callback, mainHandler)
            setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
            isActive = true
        }
        Log.i(TAG, "media keys active focusDisabled=$focusDisabled focusGranted=$granted")
        WheelKeyLog.note("media keys active focusDisabled=$focusDisabled focusGranted=$granted")
    }

    private fun forwardGrantedFocusLocked() {
        val expectedController = controller ?: return
        val owner = focusOwner ?: return
        val revision = focusEventRevision
        // Immediate grants do not promise a later focus callback. Defer dispatch until the caller
        // releases the media-key monitor. A newer real focus event invalidates this observation,
        // as do a controller or request replacement while the queued work waits.
        mainHandler.post { onFocusChanged(expectedController, owner, AudioManager.AUDIOFOCUS_GAIN, revision) }
    }

    private fun onFocusChanged(expectedController: CarPlayController, owner: Any, change: Int,
        grantedRevision: Long? = null) {
        val current = synchronized(this) {
            if (controller !== expectedController || focusOwner !== owner ||
                (grantedRevision != null && grantedRevision != focusEventRevision)) false
            else {
                focusEventRevision += 1
                // Only permanent loss moves media keys elsewhere; transient losses come back.
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        focusHeld = false
                        transientFocusInterrupted = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> transientFocusInterrupted = true
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusHeld = true
                        transientFocusInterrupted = false
                    }
                }
                true
            }
        }
        if (!current) return
        Log.i(TAG, "audio focus change=$change")
        WheelKeyLog.note("media keys audio focus change=$change")
        // Resolve the matching sink and invoke it outside the media-key monitor. An abandoned
        // request must never mute a newer controller, and these owners must not nest locks.
        val background = CarPlayBackgroundSession.snapshot()
        if (background?.controller === expectedController) background.sink.onMediaAudioFocusChanged(change)
    }

    private fun releaseLocked() {
        focusOwner = null
        controller?.let(com.shilapi.xcertplay.media.AmbientMusicController::releasePlaybackOwner)
        artworkOwner = null
        initialAlbumRetry?.let(mainHandler::removeCallbacks)
        initialAlbumRetry = null
        initialAlbumRecovery = InitialAlbumRecovery()
        ambientColorCache.clear()
        artworkQueue.clear()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        mediaAudioActive = false
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        ClusterMusicState.publish(CarPlayNowPlaying(), null)
        focusRequest?.let { request -> appContext?.getSystemService(AudioManager::class.java)?.let(request::abandon) }
        focusRequest = null
        focusHeld = false
        transientFocusInterrupted = false
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // The iPhone sends elapsed time only on play, pause or seek, so Android must
                    // extrapolate from when it arrived, not from this republish.
                    elapsedUpdatedAt,
                )
                .build(),
        )
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            WheelKeyLog.note("media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
        WheelKeyLog.note("media key $source -> CarPlay $index sent=$sent")
    }

    /** A steering-wheel media key captured outside the Android key stream (Geely/Lynk). */
    internal fun onExternalMediaKey(index: Int) = send(index, "geely-wheel")

    /** The steering-wheel microphone key captured outside the Android key stream (Geely/Lynk). */
    internal fun onExternalSiriKey(): Boolean {
        val active = synchronized(this) { controller } ?: return false
        return runCatching { active.requestSiri() }.getOrDefault(false)
    }

    private val callback = CarPlayMediaCallback(
        experimentalDiLink3Keys = { appContext?.let(BydOutputSettings::carPlayCallControls) == true },
        send = ::send,
    )

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    // Without art the car draws DiPlay's bright launcher icon instead.
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? = context
        .getDrawable(R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    /**
     * The art to show once the iPhone names transfer [id]. A pending transfer keeps [current], so the
     * placeholder does not flash between tracks.
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private data class AmbientArtwork(val bitmap: Bitmap, val color: Int?)

    private fun decodeAmbientArtwork(bytes: ByteArray): AmbientArtwork? {
        val bitmap = decodeArtwork(bytes) ?: return null
        // Runs once per decoded cover on the existing bounded artwork worker (576 pixels).
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        val color = try {
            small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            com.shilapi.xcertplay.media.AmbientAlbumPalette.albumColor(pixels)
        } finally { if (small !== bitmap) small.recycle() }
        return AmbientArtwork(bitmap, color)
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return squareArtwork(decoded).also { square -> if (square !== decoded) decoded.recycle() }
    }

    /**
     * Fits [source] inside a transparent square of at most [MAX_ARTWORK_DIMENSION]. Car clusters draw
     * art in a square box and stretch it, so 16:9 video thumbnails looked squashed.
     */
    internal fun squareArtwork(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (source.width == source.height && largest <= MAX_ARTWORK_DIMENSION) return source
        val side = minOf(largest, MAX_ARTWORK_DIMENSION)
        val scale = side.toFloat() / largest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val left = (side - width) / 2
        val top = (side - height) / 2
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { square ->
            Canvas(square).drawBitmap(
                source,
                null,
                Rect(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
internal class CarPlayMediaCallback(
    private val experimentalDiLink3Keys: () -> Boolean = { false },
    private val send: (index: Int, source: String) -> Unit,
) : MediaSession.Callback() {
    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val index = CarPlayMediaButton.forKeyCode(event.keyCode, experimentalDiLink3Keys())
            ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}
