package com.shilapi.xcertplay.turncamera.sideview

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.turncamera.sideview.adb.AdbKeys
import com.shilapi.xcertplay.turncamera.sideview.adb.LocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches for the factory camera surface (com.byd.avc) taking the foreground. While it is up the
 * side view must be fully down: the windows would only overlap it, and a camera kept streaming
 * underneath is exactly what makes the 360 stutter on this head unit.
 *
 * Uses the UsageStats event tail (half a second, the latency below which a lag reads as a bug).
 * Needs the usage-stats appop; when ADB is reachable the appop is granted automatically, the
 * same way DiPlay's other BYD tools bootstrap themselves over the car's own ADB.
 */
internal class SideView360(private val context: Context) {
    private val appContext = context.applicationContext
    private val usage: UsageStatsManager =
        appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    @Volatile var foreground: Boolean = false
        private set

    /** False while the appop is still missing — "cannot know", not "not up". */
    @Volatile var readable: Boolean = false
        private set

    private val started = AtomicBoolean(false)
    private var worker: java.util.concurrent.ScheduledExecutorService? = null
    @Volatile private var consumedUpTo = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        worker = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "sideview-360").apply { isDaemon = true }
        }?.also {
            it.scheduleWithFixedDelay(::poll, 0, POLL_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        worker?.shutdownNow()
        worker = null
        foreground = false
        readable = false
        consumedUpTo = 0L
    }

    private fun poll() {
        try {
            val now = System.currentTimeMillis()
            val from = if (consumedUpTo == 0L) now - FIRST_LOOKBACK_MS else consumedUpTo + 1
            val latest = latestResumed(usage, from, now + 1)
            if (latest != null) {
                if (latest.second > consumedUpTo) consumedUpTo = latest.second
                val up = latest.first == CAMERA_PACKAGE
                if (up != foreground) {
                    foreground = up
                    Log.i(TAG, "factory camera foreground=$up")
                }
            }
            readable = true
        } catch (e: Exception) {
            readable = false
        }
    }

    companion object {
        private const val TAG = "SideView360"
        private const val CAMERA_PACKAGE = "com.byd.avc"
        private const val POLL_MS = 500L

        /** Catches a camera activity that resumed before this poller started. */
        private const val FIRST_LOOKBACK_MS = 5L * 60_000L

        /** Newest ACTIVITY_RESUMED (package, timestamp) in the window, by timestamp not order. */
        fun latestResumed(usage: UsageStatsManager, from: Long, to: Long): Pair<String, Long>? {
            val events = usage.queryEvents(from, to)
            val event = UsageEvents.Event()
            var best: Pair<String, Long>? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                    (best == null || event.timeStamp > best.second)
                ) {
                    best = event.packageName to event.timeStamp
                }
            }
            return best
        }

        /** Best-effort `appops set <pkg> android:get_usage_stats allow` over the car's ADB. */
        fun grantAppop(context: Context): Boolean {
            val adb = LocalAdb(AdbKeys.load(context.applicationContext))
            try {
                if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return false
                return adb.shell(
                    "appops set ${context.applicationContext.packageName} android:get_usage_stats allow"
                ) != null
            } finally {
                runCatching { adb.close() }
            }
        }

        fun appopGranted(context: Context): Boolean = runCatching {
            val usage = context.applicationContext
                .getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            usage.queryEvents(0L, 1L)
            true
        }.getOrDefault(false)
    }
}
