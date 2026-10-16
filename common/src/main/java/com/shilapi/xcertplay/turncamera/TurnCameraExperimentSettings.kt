package com.shilapi.xcertplay.turncamera

import android.content.Context
import org.json.JSONObject

/** Explicit experimental opt-in, separate from portable backups. No reference-car value
 * is silently installed as a default for this car. A complete profile is firmware-bound. */
internal object TurnCameraExperimentSettings {
    const val PREFS = "diplay_turn_camera_experiment"
    const val KEY_ENABLED = "enabled"
    const val KEY_PROFILE = "profile"
    private const val KEY_FIRMWARE = "firmware"
    const val TEMPLATE = """{
  "version": 1,
  "turnOff": null, "turnLeft": null, "turnRight": null,
  "hazardOff": null, "hazardOn": null,
  "reverseOff": null, "reverseOn": null,
  "oemClass": "", "oemGetter": "", "oemIdle": null, "oemActive": null,
  "leftChannel": null, "rightChannel": null,
  "leftWidth": null, "leftHeight": null, "rightWidth": null, "rightHeight": null,
  "timestampTimebase": "UNCONFIRMED",
  "safeArea": [0.0, 0.0, 1.0, 1.0],
  "safeAreaConfirmed": false, "latchedSignalConfirmed": false
}"""
    data class Profile(
        val turnOff: Int, val turnLeft: Int, val turnRight: Int,
        val hazardOff: Int, val hazardOn: Int, val reverseOff: Int, val reverseOn: Int,
        val oemClass: String, val oemGetter: String, val oemIdle: Int, val oemActive: Int,
        val routes: Map<TurnCameraSide, TurnCameraRoute>,
        val safeArea: List<Float>,
    )

    fun text(context: Context): String = context.getSharedPreferences(PREFS, 0).getString(KEY_PROFILE, null) ?: TEMPLATE
    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, false)
    fun saveText(context: Context, text: String) {
        require(text.toByteArray().size <= 16_384)
        JSONObject(text) // Partial drafts are allowed, but editing always revokes consent.
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY_PROFILE, text)
            .putBoolean(KEY_ENABLED, false).remove(KEY_FIRMWARE).apply()
    }
    fun setEnabled(context: Context, enabled: Boolean) {
        val stamp = if (enabled) {
            requireNotNull(profile(context, requireEnabled = false)) { "incomplete experimental camera profile" }
            requireNotNull(TurnCameraSettings.firmwareKey()) { "unknown firmware" }
        } else null
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_FIRMWARE, stamp).apply()
    }
    fun profile(context: Context, requireEnabled: Boolean = true): Profile? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, 0)
        if (requireEnabled) {
            check(prefs.getBoolean(KEY_ENABLED, false))
            val stamp = requireNotNull(TurnCameraSettings.firmwareKey())
            check(stamp == prefs.getString(KEY_FIRMWARE, null))
        }
        val raw = text(context)
        require(raw.toByteArray().size <= 16_384)
        val j = JSONObject(raw)
        require(j.getInt("version") == 1 && j.get("safeAreaConfirmed") == true && j.get("latchedSignalConfirmed") == true)
        fun integer(key: String): Int {
            val n = j.get(key)
            require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toInt().toDouble() && n.toInt() >= 0)
            return n.toInt()
        }
        val off = integer("turnOff"); val left = integer("turnLeft"); val right = integer("turnRight")
        val hzOff = integer("hazardOff"); val hzOn = integer("hazardOn")
        val revOff = integer("reverseOff"); val revOn = integer("reverseOn")
        val idle = integer("oemIdle"); val active = integer("oemActive")
        require(setOf(off, left, right).size == 3 && hzOff != hzOn && revOff != revOn && idle != active)
        val type = j.getString("oemClass"); val getter = j.getString("oemGetter")
        require(validOemGetter(type, getter))
        val timebase = TurnCameraFrameTimebase.valueOf(j.getString("timestampTimebase"))
        val area = j.getJSONArray("safeArea")
        require(area.length() == 4)
        val bounds = (0..3).map { area.getDouble(it).toFloat() }
        require(bounds.all { it.isFinite() && it in 0f..1f } && bounds[2] > bounds[0] && bounds[3] > bounds[1])
        val routes = TurnCameraSide.entries.associateWith { side ->
            val p = TurnCameraCalibrationStore.load(context, side)
            require(p.source == TurnCameraCalibration.Source.VENDOR_AVM && p.cameraId.isNotBlank())
            val prefix = if (side == TurnCameraSide.LEFT) "left" else "right"
            TurnCameraRoute(p.cameraId.toInt(), integer(prefix + "Channel"), integer(prefix + "Width"),
                integer(prefix + "Height"), p, timebase)
        }
        require(routes.getValue(TurnCameraSide.LEFT).id != routes.getValue(TurnCameraSide.RIGHT).id)
        Profile(off, left, right, hzOff, hzOn, revOff, revOn, type, getter, idle, active, routes, bounds)
    }.getOrNull()

    fun validOemGetter(type: String, getter: String) =
        type.matches(Regex("android\\.hardware\\.bydauto\\.[A-Za-z0-9_.]{1,160}")) &&
            getter.matches(Regex("get[A-Z][A-Za-z0-9]{0,60}"))
}
