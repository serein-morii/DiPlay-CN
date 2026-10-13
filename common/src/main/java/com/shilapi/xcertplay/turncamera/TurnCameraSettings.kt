package com.shilapi.xcertplay.turncamera

import android.content.Context

/**
 * Persistence for the turn side-camera feature. Phase A stores only the last read-only
 * probe outcome so the Advanced card can show an honest status; there is no enable flag
 * yet because streaming is not implemented and a dead switch must not exist (plan §5).
 */
internal object TurnCameraSettings {
    private const val PREFS = "diplay"
    private const val KEY_AT = "turn_camera_last_probe_at"
    private const val KEY_SUMMARY = "turn_camera_last_probe_summary"

    data class LastProbe(val atMillis: Long, val summary: String)

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
            .apply()
    }
}
