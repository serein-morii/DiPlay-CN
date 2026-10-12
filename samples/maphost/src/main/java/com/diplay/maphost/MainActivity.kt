package com.diplay.maphost

import android.app.Activity
import android.content.ComponentName
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A minimal "launcher" that shows DiPlay's live dashboard map in its own layout.
 *
 * 1. Find and bind DiPlay's service by its action.
 * 2. When both the service and the SurfaceView are ready, send ATTACH with the SurfaceView's host
 *    token, display and size.
 * 3. Put the SurfacePackage DiPlay sends back into the SurfaceView.
 * 4. Send RESIZE when the view changes size and DETACH when the screen goes away.
 */
class MainActivity : Activity() {
    private lateinit var mapView: SurfaceView
    private lateinit var status: TextView
    private var service: Messenger? = null
    private var bound = false
    private var surfaceReady = false
    private var attached = false
    private var mapWidth = 1200

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message -> onReply(message); true })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            requestMap()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            attached = false
            show(getString(R.string.maphost_stopped))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 18f }
        mapView = SurfaceView(this)
        mapView.holder.addCallback(object : SurfaceHolder.Callback {
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
        fun sizeButton(label: String, step: Int) = Button(this).apply {
            text = label
            setOnClickListener {
                mapWidth = (mapWidth + step).coerceIn(400, 2400)
                mapView.layoutParams = mapParams()
            }
        }
        val buttons = LinearLayout(this).apply {
            addView(sizeButton(getString(R.string.maphost_smaller), -200))
            addView(sizeButton(getString(R.string.maphost_larger), 200))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 32))
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply {
                text = getString(R.string.maphost_title); setTextColor(Color.WHITE); textSize = 32f
            })
            addView(status)
            addView(buttons)
            addView(FrameLayout(context).apply { addView(mapView, mapParams()) })
        })
    }

    // The dashboard map is 8:3; other shapes are filled and cropped by DiPlay.
    private fun mapParams() = FrameLayout.LayoutParams(mapWidth, mapWidth * 3 / 8, Gravity.START)

    override fun onStart() {
        super.onStart()
        val intent = Intent(ACTION)
        val info = packageManager.queryIntentServices(intent, 0).firstOrNull()?.serviceInfo
        if (info == null) {
            show(getString(R.string.maphost_not_installed))
            return
        }
        intent.setClassName(info.packageName, info.name)
        bound = bindService(intent, connection, BIND_AUTO_CREATE)
        show(if (bound) getString(R.string.maphost_connecting, info.packageName) else getString(R.string.maphost_refused))
    }

    override fun onStop() {
        if (attached) send(MSG_DETACH) {}
        attached = false
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onStop()
    }

    private fun requestMap() {
        if (service == null || !surfaceReady || attached) return
        val token = mapView.hostToken ?: return
        send(MSG_ATTACH) {
            putBinder(KEY_HOST_TOKEN, token)
            putInt(KEY_DISPLAY_ID, mapView.display.displayId)
            putInt(KEY_WIDTH, mapView.width)
            putInt(KEY_HEIGHT, mapView.height)
        }
        show(getString(R.string.maphost_requesting))
    }

    private fun onReply(message: Message) {
        when (message.what) {
            MSG_ATTACHED -> {
                @Suppress("DEPRECATION")
                val surfacePackage = message.data.getParcelable<SurfaceControlViewHost.SurfacePackage>(KEY_SURFACE_PACKAGE)
                if (surfacePackage == null) {
                    show(getString(R.string.maphost_missing))
                    return
                }
                mapView.setChildSurfacePackage(surfacePackage)
                attached = true
                show(streamText(message.data.getBoolean(KEY_STREAM_ACTIVE)))
            }
            MSG_STREAM_STATE -> show(streamText(message.data.getBoolean(KEY_STREAM_ACTIVE)))
            MSG_ERROR -> {
                attached = false
                show(
                when (message.data.getString(KEY_ERROR)) {
                    ERROR_DISABLED -> getString(R.string.maphost_disabled)
                    ERROR_UNSUPPORTED -> getString(R.string.maphost_unsupported)
                    else -> getString(R.string.maphost_error, message.data.getString(KEY_ERROR))
                },
                )
            }
        }
    }

    private fun streamText(active: Boolean) =
        getString(if (active) R.string.maphost_live else R.string.maphost_waiting)

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
    }

    private companion object {
        // DiPlay's protocol (MapEmbedService); copy these into your launcher.
        const val ACTION = "com.shihab.diplay.action.EMBED_MAP"
        const val MSG_ATTACH = 1
        const val MSG_RESIZE = 2
        const val MSG_DETACH = 3
        const val MSG_ATTACHED = 101
        const val MSG_STREAM_STATE = 102
        const val MSG_ERROR = 199
        const val KEY_HOST_TOKEN = "hostToken"
        const val KEY_DISPLAY_ID = "displayId"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_SURFACE_PACKAGE = "surfacePackage"
        const val KEY_STREAM_ACTIVE = "streamActive"
        const val KEY_ERROR = "error"
        const val ERROR_DISABLED = "disabled"
        const val ERROR_UNSUPPORTED = "unsupported"
    }
}
