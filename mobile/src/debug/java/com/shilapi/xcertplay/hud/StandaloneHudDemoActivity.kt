package com.shilapi.xcertplay.hud

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/** Parked, finite probe of stock IPC. No shell, socket, SDK privilege or helper. */
class StandaloneHudDemoActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private var showing = false
    private var started = 0L
    private var lastTurn = 0
    private var running = false
    private val target = ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN")
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed >= 16_000) { clear("demo complete"); return }
                val turn = if (elapsed < 8_000) 1 else 2
                val distance = if (turn == 1) 500 else 800
                val road = if (turn == 1) "Muscat Road" else "Sultan Qaboos Street"
                transmit(BydStandalonePackets.guidance(if (turn == 1) 2 else 3, 0, distance, road)!!)
                if (turn != lastTurn) Log.i(TAG, "APP_GUIDANCE uid=${Process.myUid()} turn=$turn distance=$distance")
                lastTurn = turn
                status.text = (if (turn == 1) "LEFT — 500 m" else "RIGHT — 800 m") + "\n" + road
                handler.postDelayed(this, 1_000)
            } catch (error: Exception) {
                Log.e(TAG, "Demo failed", error)
                clear("send failed")
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { textSize = 32f; text = "Standalone HUD test ready" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            addView(status)
            addView(TextView(this@StandaloneHudDemoActivity).apply {
                text = "Parked test: Muscat Road / left 500 m, then Sultan Qaboos Street / right 800 m; 8 seconds each, then clear."
                textSize = 22f
            })
            addView(Button(this@StandaloneHudDemoActivity).apply {
                text = "Start 16-second test"
                setOnClickListener { startDemo() }
            })
            addView(Button(this@StandaloneHudDemoActivity).apply {
                text = "Clear HUD"
                setOnClickListener {
                    try { validateTarget(); showing = true; clear("button") }
                    catch (error: Exception) { status.text = "Clear unavailable: ${error.message}" }
                }
            })
        })
        try {
            validateTarget()
            Log.i(TAG, "PREFLIGHT_OK uid=${Process.myUid()} stock receiver verified")
            if (intent.getBooleanExtra("run", false) && state == null) handler.post { startDemo() }
        } catch (error: Exception) {
            status.text = "Test unavailable: ${error.message}"
            Log.e(TAG, "Preflight failed", error)
        }
    }

    private fun validateTarget() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) error("This test is restricted to the inspected firmware")
        check(packageName == "com.shihab.diplay.cn" && Process.myUid() >= 10000)
        check(Build.FINGERPRINT == "BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155:user/release-keys") {
            "This test is restricted to the inspected firmware"
        }
        val info = packageManager.getPackageInfo(target.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.longVersionCode == 10601004L) { "Different stock receiver version" }
        check(info.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        val certs = info.signingInfo!!.apkContentsSigners
        check(certs.size == 1 && MessageDigest.getInstance("SHA-256").digest(certs[0].toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) } ==
            "efe3ca8ada0d10c655c3df9910ad2ebc121a47d9a6358434eb24074309933efc")
        val receiver = packageManager.getReceiverInfo(target, 0)
        check(receiver.enabled && receiver.exported && receiver.permission.isNullOrEmpty())
    }

    private fun startDemo() {
        if (running) return
        try {
            validateTarget()

            BydNavigationOutputs.setDiagnosticHold(true)
            showing = true // Preserve cleanup even if a later operation fails.
            transmit(StandaloneHudPackets.start())
            started = SystemClock.elapsedRealtime()
            running = true
            lastTurn = 0
            Log.i(TAG, "APP_START uid=${Process.myUid()} helper=none")
            // Start and subsequent records target the same manifest receiver.
            handler.postDelayed(tick, 250)
        } catch (error: Exception) {
            Log.e(TAG, "Standalone preflight/start failed", error)
            status.text = "Test unavailable: ${error.message}"
            clear("start failed")
        }
    }

    private fun transmit(packet: String) {
        sendBroadcast(Intent("byd.hud.NAVIGATION_DEMO").setComponent(target)
            .putExtra("normal", packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
    }

    private fun clear(reason: String) {
        running = false
        handler.removeCallbacksAndMessages(null)
        if (showing) {
            try {
                transmit(StandaloneHudPackets.clear())
                // Broadcast delivery is not a hardware acknowledgement.
                Log.i(TAG, "APP_CLEAR_SENT uid=${Process.myUid()} reason=$reason")
                showing = false
                status.text = "Clear sent — check windshield"
            } catch (error: Exception) {
                Log.e(TAG, "Clear failed; retry with Clear HUD", error)
                status.text = "Clear failed — tap Clear HUD"
            }
        }

    }

    override fun onStop() { clear("activity stopped"); BydNavigationOutputs.setDiagnosticHold(false); super.onStop() }
    override fun onDestroy() { clear("activity destroyed"); super.onDestroy() }
    companion object { private const val TAG = "BYD-Standalone" }
}
