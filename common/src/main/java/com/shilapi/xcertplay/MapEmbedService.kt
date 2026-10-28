package com.shilapi.xcertplay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceControlViewHost
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.host.R

/**
 * Lets another app, such as a car launcher, show the live dashboard map (CarPlay stream 111)
 * inside its own screen. The app binds with [ACTION], sends [MSG_ATTACH] with its SurfaceView's
 * host token and size, and gets back a SurfaceControlViewHost.SurfacePackage to put into that
 * SurfaceView; the map then lives in the launcher's layout. Each attached view gets its own decoder.
 * Off until the driver allows it in DiPlay. Android 11+. See docs/LAUNCHER_INTEGRATION.md.
 */
class MapEmbedService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })
    private val embeds = HashMap<IBinder, Embed>()
    private var nextId = 0
    private var destroyed = false
    private var stopObservingSharing: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        stopObservingSharing = AirPlayPersistence.observeLauncherMapSharing(this) { enabled ->
            if (!enabled) {
                if (Looper.myLooper() == main.looper) revokeSharing()
                else main.post { revokeSharing() }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        destroyed = true
        stopObservingSharing?.invoke()
        stopObservingSharing = null
        embeds.values.toList().forEach { it.release() }
        embeds.clear()
        super.onDestroy()
    }

    private fun revokeSharing() {
        if (destroyed) return
        val attached = embeds.values.toList()
        embeds.clear()
        attached.forEach { it.sharingDisabled() }
    }

    private fun handle(message: Message) {
        val client = message.replyTo ?: return
        val caller = packageManager.getNameForUid(message.sendingUid) ?: "uid ${message.sendingUid}"
        when (message.what) {
            MSG_ATTACH -> attach(client, caller, message.sendingUid, message.data)
            MSG_RESIZE -> embeds[client.binder]?.resize(message.data.getInt(KEY_WIDTH), message.data.getInt(KEY_HEIGHT))
            MSG_DETACH -> embeds.remove(client.binder)?.release()
        }
    }

    private fun attach(client: Messenger, caller: String, uid: Int, data: Bundle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            refuse(client, caller, ERROR_UNSUPPORTED)
            return
        }
        val error = when {
            !AirPlayPersistence.loadLauncherMapSharing(this) -> ERROR_DISABLED
            data.getBinder(KEY_HOST_TOKEN) == null -> ERROR_BAD_REQUEST
            else -> null
        }
        if (error != null) {
            refuse(client, caller, error)
            return
        }
        embeds.remove(client.binder)?.release()
        if (embeds.size >= MAX_EMBEDS || embeds.values.count { it.uid == uid } >= MAX_EMBEDS_PER_UID) {
            refuse(client, caller, ERROR_TOO_MANY)
            return
        }
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(data.getInt(KEY_DISPLAY_ID))
        if (display == null) {
            send(client, MSG_ERROR, Bundle().apply { putString(KEY_ERROR, ERROR_BAD_REQUEST) })
            return
        }
        val embed = Embed(
            createDisplayContext(display), client, uid, "launcher:${nextId++}",
            data.getBinder(KEY_HOST_TOKEN)!!, display, data.getInt(KEY_WIDTH), data.getInt(KEY_HEIGHT),
        )
        embeds[client.binder] = embed
        runCatching { client.binder.linkToDeath({ main.post { embeds.remove(client.binder)?.release() } }, 0) }
        Log.i(TAG, "$caller shows the map ${data.getInt(KEY_WIDTH)}x${data.getInt(KEY_HEIGHT)}")
        send(client, MSG_ATTACHED, Bundle().apply {
            putParcelable(KEY_SURFACE_PACKAGE, embed.surfacePackage)
            putBoolean(KEY_STREAM_ACTIVE, MapMirrors.streamActive)
        })
    }

    private fun refuse(client: Messenger, caller: String, error: String) {
        Log.w(TAG, "$caller asked for the map: $error")
        send(client, MSG_ERROR, Bundle().apply { putString(KEY_ERROR, error) })
    }

    private fun send(client: Messenger, what: Int, data: Bundle) {
        try {
            client.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            embeds.remove(client.binder)?.release()
        }
    }

    /** One map in one launcher view. Main thread. */
    @RequiresApi(Build.VERSION_CODES.R)
    private inner class Embed(
        context: Context,
        private val client: Messenger,
        val uid: Int,
        private val key: String,
        hostToken: IBinder,
        display: android.view.Display,
        width: Int,
        height: Int,
    ) {
        private val host = SurfaceControlViewHost(context, display, hostToken)
        private var released = false
        private var surface: Surface? = null
        private val waiting = TextView(context).apply {
            text = context.getString(R.string.cluster_waiting_for_map)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        private val video = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                    if (released) return
                    cropToFill(this@apply, w, h)
                    surface = Surface(texture).also { MapMirrors.set(key, it) }
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) = cropToFill(this@apply, w, h)
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    MapMirrors.set(key, null)
                    val old = surface
                    surface = null
                    main.postDelayed({ old?.release(); texture.release() }, RELEASE_DELAY_MILLIS)
                    return false
                }
            }
        }
        private val aspectListener: () -> Unit = {
            if (!released) cropToFill(video, video.width, video.height)
        }
        private val streamListener: (Boolean) -> Unit = { active ->
            waiting.visibility = if (active) TextView.GONE else TextView.VISIBLE
            send(client, MSG_STREAM_STATE, Bundle().apply { putBoolean(KEY_STREAM_ACTIVE, active) })
        }
        private val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(-1, -1))
            addView(waiting, FrameLayout.LayoutParams(-1, -1))
            // A tap opens CarPlay, as a tap on the dashboard card does.
            setOnClickListener {
                context.startActivity(CarPlayScreen.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        val surfacePackage: SurfaceControlViewHost.SurfacePackage? get() = host.surfacePackage

        init {
            waiting.visibility = if (MapMirrors.streamActive) TextView.GONE else TextView.VISIBLE
            MapMirrors.addStreamListener(streamListener)
            MapMirrors.addAspectListener(aspectListener)
            host.setView(root, width.coerceAtLeast(1), height.coerceAtLeast(1))
        }

        fun resize(width: Int, height: Int) {
            if (!released && width > 0 && height > 0) host.relayout(width, height)
        }

        fun release() {
            if (released) return
            released = true
            MapMirrors.removeStreamListener(streamListener)
            MapMirrors.removeAspectListener(aspectListener)
            MapMirrors.set(key, null)
            host.release()
        }

        fun sharingDisabled() {
            release()
            send(client, MSG_ERROR, Bundle().apply { putString(KEY_ERROR, ERROR_DISABLED) })
        }
    }

    companion object {
        private const val TAG = "DiPlay-MapEmbed"
        private const val RELEASE_DELAY_MILLIS = 1_000L

        // Each embed holds a decoder surface and a view host. Any app can bind once the driver allows sharing,
        // and an app can bind with many Messengers; a launcher uses one view (docs/LAUNCHER_INTEGRATION.md).
        private const val MAX_EMBEDS_PER_UID = 4
        private const val MAX_EMBEDS = 8

        /** Bind to DiPlay's service with this action (the package differs between builds). */
        const val ACTION = "com.shihab.diplay.action.EMBED_MAP"

        // Launcher -> DiPlay. Every message needs replyTo.
        const val MSG_ATTACH = 1 // KEY_HOST_TOKEN, KEY_DISPLAY_ID, KEY_WIDTH, KEY_HEIGHT
        const val MSG_RESIZE = 2 // KEY_WIDTH, KEY_HEIGHT
        const val MSG_DETACH = 3

        // DiPlay -> launcher.
        const val MSG_ATTACHED = 101 // KEY_SURFACE_PACKAGE, KEY_STREAM_ACTIVE
        const val MSG_STREAM_STATE = 102 // KEY_STREAM_ACTIVE
        const val MSG_ERROR = 199 // KEY_ERROR

        const val KEY_HOST_TOKEN = "hostToken"
        const val KEY_DISPLAY_ID = "displayId"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_SURFACE_PACKAGE = "surfacePackage"
        const val KEY_STREAM_ACTIVE = "streamActive"
        const val KEY_ERROR = "error"

        const val ERROR_DISABLED = "disabled" // the driver has not allowed sharing in DiPlay
        const val ERROR_UNSUPPORTED = "unsupported" // Android 10 or older
        const val ERROR_BAD_REQUEST = "bad_request"
        const val ERROR_TOO_MANY = "too_many" // this app or all apps already show the most maps allowed

        /** Keeps the stream's shape (8:3 or 16:9) and fills the view, cutting the edges that do not fit. */
        internal fun cropToFill(view: TextureView, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val viewAspect = width.toFloat() / height
            val stream = MapMirrors.streamAspect.toFloat()
            val scaleX = if (viewAspect < stream) stream / viewAspect else 1f
            val scaleY = if (viewAspect > stream) viewAspect / stream else 1f
            view.setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
        }
    }
}
