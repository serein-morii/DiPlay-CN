package com.diplay.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.Gravity
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The live CarPlay map from DiPlay, embedded with DiPlay's map service (see DiPlay's
 * docs/LAUNCHER_INTEGRATION.md). Call [start] and [stop] with the screen. A tap on the map opens
 * CarPlay (DiPlay handles it); while there is no map, a tap opens DiPlay.
 */
class DiPlayMapPanel(context: Context) : FrameLayout(context) {
    private val map = SurfaceView(context)
    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        setPadding(48, 48, 48, 48)
    }
    private var service: Messenger? = null
    private var bound = false
    private var surfaceReady = false
    private var attached = false

    /** DiPlay's package, once found; null when DiPlay is not installed. */
    var diPlayPackage: String? = null
        private set

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message -> onReply(message); true })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            requestMap()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            attached = false
            show(context.getString(R.string.home_map_stopped))
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(map, LayoutParams(-1, -1))
        addView(status, LayoutParams(-1, -1))
        status.setOnClickListener { openDiPlay() }
        map.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                requestMap()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (attached) send(MSG_RESIZE) { putInt(KEY_WIDTH, width); putInt(KEY_HEIGHT, height) }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                attached = false
            }
        })
    }

    fun start() {
        val intent = Intent(ACTION)
        val info = context.packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo
        diPlayPackage = info?.packageName
        if (info == null) {
            show(context.getString(R.string.home_map_not_installed))
            return
        }
        intent.setClassName(info.packageName, info.name)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        show(context.getString(if (bound) R.string.home_map_connecting else R.string.home_map_refused))
    }

    fun stop() {
        if (attached) send(MSG_DETACH) {}
        attached = false
        if (bound) context.unbindService(connection)
        bound = false
        service = null
    }

    /** Opens CarPlay in DiPlay, or DiPlay itself when that fails. */
    fun openCarPlay() {
        val pkg = diPlayPackage ?: return
        val carPlay = Intent().setClassName(pkg, CARPLAY_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(carPlay) }.isFailure) openDiPlay()
    }

    private fun openDiPlay() {
        val pkg = diPlayPackage ?: return
        context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun requestMap() {
        if (service == null || !surfaceReady || attached) return
        val token = map.hostToken ?: return
        send(MSG_ATTACH) {
            putBinder(KEY_HOST_TOKEN, token)
            putInt(KEY_DISPLAY_ID, map.display.displayId)
            putInt(KEY_WIDTH, map.width)
            putInt(KEY_HEIGHT, map.height)
        }
    }

    private fun onReply(message: Message) {
        when (message.what) {
            MSG_ATTACHED -> {
                @Suppress("DEPRECATION")
                val surfacePackage = message.data.getParcelable<SurfaceControlViewHost.SurfacePackage>(KEY_SURFACE_PACKAGE)
                    ?: return show(context.getString(R.string.home_map_missing))
                map.setChildSurfacePackage(surfacePackage)
                attached = true
                // DiPlay draws its own "waiting" text until the map streams.
                status.visibility = GONE
            }
            MSG_ERROR -> {
                attached = false
                show(
                when (message.data.getString(KEY_ERROR)) {
                    ERROR_DISABLED -> context.getString(R.string.home_map_disabled)
                    ERROR_UNSUPPORTED -> context.getString(R.string.home_map_unsupported)
                    else -> context.getString(R.string.home_map_error)
                },
                )
            }
        }
    }

    private fun send(what: Int, fill: Bundle.() -> Unit) {
        val target = service ?: return
        try {
            target.send(Message.obtain(null, what).apply {
                data = Bundle().apply(fill)
                replyTo = replies
            })
        } catch (_: RemoteException) {
            service = null
        }
    }

    private fun show(text: String) {
        status.text = text
        status.visibility = VISIBLE
    }

    private companion object {
        const val CARPLAY_ACTIVITY = "com.shilapi.xcertplay.CarPlayHostActivity"
        // DiPlay's map protocol.
        const val ACTION = "com.shihab.diplay.action.EMBED_MAP"
        const val MSG_ATTACH = 1
        const val MSG_RESIZE = 2
        const val MSG_DETACH = 3
        const val MSG_ATTACHED = 101
        const val MSG_ERROR = 199
        const val KEY_HOST_TOKEN = "hostToken"
        const val KEY_DISPLAY_ID = "displayId"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_SURFACE_PACKAGE = "surfacePackage"
        const val KEY_ERROR = "error"
        const val ERROR_DISABLED = "disabled"
        const val ERROR_UNSUPPORTED = "unsupported"
    }
}
