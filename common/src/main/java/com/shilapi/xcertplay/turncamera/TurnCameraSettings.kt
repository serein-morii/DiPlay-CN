package com.shilapi.xcertplay.turncamera

import android.content.Context
import android.os.Build
import java.security.MessageDigest

/**
 * Persistence for the turn side-camera feature. Phase A stores only the last read-only
 * probe outcome so the Advanced card can show an honest status; there is no enable flag
 * yet because streaming is not implemented and a dead switch must not exist (plan §5).
 */
internal object TurnCameraSettings {
    private const val PREFS = "diplay"
    private const val KEY_AT = "turn_camera_last_probe_at"
    private const val KEY_SUMMARY = "turn_camera_last_probe_summary"
    private const val KEY_REPORT = "turn_camera_last_probe_report"
    private const val KEY_FIRMWARE = "turn_camera_probe_firmware"
    private const val MAX_REPORT_CHARS = 24_000

    data class LastProbe(val atMillis: Long, val summary: String)

    data class SavedReport(val probe: LastProbe, val text: String, val firmwareMatches: Boolean)

    /** Local-only evidence, not a support flag. Unknown/changed firmware always invalidates it. */
    fun savedReport(context: Context): SavedReport? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val probe = lastProbe(context) ?: return null
        val text = prefs.getString(KEY_REPORT, null)?.takeIf { it.length <= MAX_REPORT_CHARS } ?: return null
        val current = firmwareKey()
        return SavedReport(probe, text, current != null && prefs.getString(KEY_FIRMWARE, null) == current)
    }

    fun saveReport(context: Context, report: TurnCameraCapabilities.Report) {
        // Busy/cancelled runs must never destroy the last useful report.
        if (report.isBusy || report.cancelled) return
        val safe = report.copy(items = report.items.take(32).map {
            it.copy(key = TurnCameraCapabilities.redact(it.key), detail = TurnCameraCapabilities.redactDetail(it.key, it.detail))
        })
        val text = safe.toReportText()
        if (text.length > MAX_REPORT_CHARS) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AT, safe.startedAtWallMillis)
            .putString(KEY_SUMMARY, safe.summaryLine())
            .putString(KEY_REPORT, text)
            .putString(KEY_FIRMWARE, firmwareKey())
            .apply()
    }

    internal fun firmwareKey(): String? {
        val fingerprint = Build.FINGERPRINT?.takeUnless { it.isBlank() || it == Build.UNKNOWN } ?: return null
        // Store a digest, never an additional raw device identifier. A controller OTA can
        // change vendor APIs without changing Android's build fingerprint.
        val controller = TurnCameraCapabilities.readSystemProperty("apps.setting.product.outswver") ?: return null
        val identity = "$fingerprint\n${Build.DISPLAY}\n${Build.VERSION.SDK_INT}\n$controller"
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun lastProbe(context: Context): LastProbe? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_AT, 0L)
        val summary = prefs.getString(KEY_SUMMARY, null) ?: return null
        return LastProbe(at, summary)
    }

    fun saveLastProbe(context: Context, probe: LastProbe) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AT, probe.atMillis)
            .putString(KEY_SUMMARY, probe.summary)
            .remove(KEY_REPORT)
            .remove(KEY_FIRMWARE)
            .apply()
    }
}
