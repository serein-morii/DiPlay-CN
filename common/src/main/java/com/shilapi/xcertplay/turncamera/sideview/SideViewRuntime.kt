package com.shilapi.xcertplay.turncamera.sideview

import android.content.Context
import android.graphics.Rect

/**
 * Process-wide holder for the side-view engine so the settings switch, the activity startup hook
 * and the loop all share one instance. Threads are daemons; the engine stops itself on the switch.
 */
internal object SideViewRuntime {
    @Volatile private var engine: SideViewEngine? = null

    fun prefs(context: Context): SideViewPrefs = SideViewPrefs(context.applicationContext)

    fun maybeStart(context: Context) {
        if (!prefs(context).enabled) return
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            engine = SideViewEngine(context.applicationContext).also { it.start() }
        }
    }

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(SideViewPrefs.ENABLED, on).apply()
        if (on) {
            maybeStart(context)
        } else {
            val current = engine ?: return
            engine = null
            current.stop()
        }
    }

    fun status(context: Context): String = engine?.statusLine() ?: "idle"

    fun cameraJournal(): List<String> = engine?.cameraJournal() ?: emptyList()

    // ── Window position nudging (settings arrows) ───────────────────────────────

    private fun screen(context: Context): Pair<Int, Int> {
        val metrics = context.applicationContext.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    fun nudgeRight(context: Context, dx: Int, dy: Int) {
        move(context, SideViewPrefs.RIGHT_X, SideViewPrefs.RIGHT_Y, dx, dy)
    }

    fun nudgeLeft(context: Context, dx: Int, dy: Int) {
        move(context, SideViewPrefs.LEFT_X, SideViewPrefs.LEFT_Y, dx, dy)
    }

    fun resetRight(context: Context) {
        prefs(context).edit().remove(SideViewPrefs.RIGHT_X).remove(SideViewPrefs.RIGHT_Y).apply()
    }

    fun resetLeft(context: Context) {
        prefs(context).edit().remove(SideViewPrefs.LEFT_X).remove(SideViewPrefs.LEFT_Y).apply()
    }

    private fun move(context: Context, keyX: String, keyY: String, dx: Int, dy: Int) {
        val prefs = prefs(context)
        val (w, h) = screen(context)
        val current = SideViewPrefs.placedRect(
            w, h, prefs.windowWidthPct, prefs.rotate,
            prefs.savedInt(keyX), prefs.savedInt(keyY))
        prefs.edit().putInt(keyX, current.left + dx).putInt(keyY, current.top + dy).apply()
    }

    // ── One-shot permission helper for the settings row ─────────────────────────

    data class Readiness(val adbReachable: Boolean, val usageAppop: Boolean, val overlayAllowed: Boolean)

    fun readiness(context: Context): Readiness {
        val app = context.applicationContext
        val adbOk = runCatching {
            com.shilapi.xcertplay.turncamera.sideview.adb.LocalAdb(
                com.shilapi.xcertplay.turncamera.sideview.adb.AdbKeys.load(app))
                .use { client -> client.connect(mayAsk = true) ==
                    com.shilapi.xcertplay.turncamera.sideview.adb.LocalAdb.Access.READY }
        }.getOrDefault(false)
        return Readiness(adbOk, SideView360.appopGranted(app), android.provider.Settings.canDrawOverlays(app))
    }

    /** Grants the usage appop over ADB when possible; returns whether the command went out. */
    fun grantUsageAppop(context: Context): Boolean = SideView360.grantAppop(context)
}
