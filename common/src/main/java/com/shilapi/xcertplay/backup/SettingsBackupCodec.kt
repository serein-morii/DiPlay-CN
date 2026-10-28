package com.shilapi.xcertplay.backup

import android.content.Context
import android.content.SharedPreferences.Editor
import com.shilapi.xcertplay.CarPlayPicture
import org.json.JSONArray
import org.json.JSONObject

data class SettingsImportOutcome(val applied: Int, val skipped: Int, val invalid: Boolean)

/** Reads and writes the settings documents exchanged through the backup web page. */
object SettingsBackupCodec {
    const val FORMAT = "diplay-settings"
    const val VERSION = 1

    /**
     * The settings of the main file that travel, by name. That file also holds the iPhones' pairing keys
     * (`pairing.<id>`), lockdown records, the authentication target and the car's hotspot name and
     * passphrase. An imported file must never set those: a pairing key from someone else's file would make
     * DiPlay trust their device. So a key travels only once it is listed here; car-specific values (the
     * detected screen size, the car's hotspot) and consents (sharing the map with other apps) stay out too.
     */
    private val AIRPLAY_KEYS = setOf(
        "display_scale_tenths", "ui_scale_percent", "hevc_enabled", "hevc_software_decoder",
        // The resolution percentage; display_scale_tenths is its older key, still read when this one is missing.
        "display_scale_percent",
        "advanced_audio_channel_mapping", "audio_focus_enabled", "audio_focus_auto_yield",
        "navigation_prompt_audio_focus", "disable_media_key_audio_focus", "media_audio_channel", "navigation_audio_channel",
        "navigation_stream_type", "wireless_enabled", "wireless_hotspot_mode", "wifi_p2p_preferred_channel", "wifi_direct_scan_pause",
        "navigation_audio_attenuation_db",
        "manual_hotspot_band", "manual_hotspot_channel", "manual_hotspot_security", "app_appearance",
        "ambient_lux_threshold", "display_fps", "media_buffer_ms", "wired_media_buffer_ms", "main_buffered_audio",
        "ambient_delay_seconds",
        "speech_without_8_16_khz", "car_bluetooth_audio", "call_echo_cancellation", "call_voice_filter",
        "call_speakerphone",
        "smooth_video", "direct_video_output", "low_latency_decoder", "mtk_decoder_tuning", "fps_counter",
        "adb_cluster_activity_enabled", "platform21_cluster_enabled", "center_map_overlay", "center_map_auto_hide",
        "center_map_follows_dashboard", "settings_gesture_fingers", "display_width_physical_mm",
        "display_physical_size_basis", "right_hand_drive", "hide_top_bar", "hide_bottom_bar",
        "temporary_system_bars",
        "harman_fullscreen", "carplay_same_task", "safe_area_draw_outside", "main_display_disable_safe_area",
        "black_corner_radius_dp",
        "adapt_pip_resolution", "auto_start_on_boot", "bt_suspend_during_carplay", "hide_byd_call_popup",
        "launcher_bubble", "launcher_bubble_size",
        "bt_suspend_delay_seconds", "location_reporting_enabled", "external_controller", "usb_charging_current",
    )

    /** Families of layout settings with many keys and nothing sensitive in them. */
    private val AIRPLAY_PREFIXES = listOf("cluster_", "carplay_night_")
    private val DIPLAY_KEYS = setOf(
        "auto_connect",
        "connect_on_phone_bluetooth",
        "default_connection_mode",
        "settings_force_full_layout",
        "app_appearance_button",
        "byd_vehicle_advanced",
        "picture_controls",
        "settings_gesture_fingers",
    )
    private val PICTURE_KEYS = CarPlayPicture.keys.toSet()
    // The driver's choices only. "ignore_off_report" records what one car's firmware reports, so it stays out.
    private val CAR_HOTSPOT_KEYS = setOf("auto_enable", "config_write")
    private val FILES = mapOf(
        "xcertplay_airplay" to Policy.Allow(AIRPLAY_KEYS, AIRPLAY_PREFIXES),
        "diplay" to Policy.Allow(DIPLAY_KEYS),
        // These readers call getInt and getBoolean, which throw on any other stored type. A key that is not stored yet
        // has no type to compare with, so the type is declared here.
        "carplay_picture" to Policy.Allow(PICTURE_KEYS, types = PICTURE_KEYS.associateWith { KeyType.INT }),
        "diplay_car_hotspot" to Policy.Allow(CAR_HOTSPOT_KEYS, types = CAR_HOTSPOT_KEYS.associateWith { KeyType.BOOLEAN }),
    )

    private enum class KeyType { BOOLEAN, INT }

    private sealed interface Policy {
        fun includes(key: String): Boolean
        fun typeOf(key: String): KeyType?

        data class Allow(
            val keys: Set<String>,
            val prefixes: List<String> = emptyList(),
            val types: Map<String, KeyType> = emptyMap(),
        ) : Policy {
            override fun includes(key: String) = key in keys || prefixes.any(key::startsWith)
            override fun typeOf(key: String) = types[key]
        }
    }

    fun export(context: Context, appVersion: String): ByteArray {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("app", appVersion)
        root.put("exportedAt", System.currentTimeMillis())
        val files = JSONObject()
        for ((name, policy) in FILES) {
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .filterKeys { policy.includes(it) }
                .filterValues { it !is ByteArray }
                .mapValues { it.value!! }
            if (values.isEmpty()) continue
            val file = JSONObject()
            for ((key, value) in values) file.put(key, jsonValue(value))
            files.put(name, file)
        }
        root.put("settings", files)
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun import(context: Context, document: ByteArray): SettingsImportOutcome {
        val root = runCatching { JSONObject(document.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return SettingsImportOutcome(0, 0, invalid = true)
        if (root.optString("format") != FORMAT || root.optInt("version", Int.MIN_VALUE) !in 1..VERSION) {
            return SettingsImportOutcome(0, 0, invalid = true)
        }
        val files = root.optJSONObject("settings")
            ?: return SettingsImportOutcome(0, 0, invalid = true)
        var applied = 0
        var skipped = 0
        for (name in files.keys()) {
            val policy = FILES[name]
            val file = files.optJSONObject(name)
            if (policy == null || file == null) {
                skipped += files.optJSONObject(name)?.length() ?: 1
                continue
            }
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val current = prefs.all
            val editor = prefs.edit()
            for (key in file.keys()) {
                val value = file.opt(key)
                if (!policy.includes(key) || value === JSONObject.NULL) {
                    skipped++
                    continue
                }
                val declared = policy.typeOf(key)
                val stored = if (declared != null) storeTyped(editor, key, value, declared) else when (val target = current[key]) {
                    is Boolean -> if (value is Boolean) { editor.putBoolean(key, value); true } else false
                    is Int -> integral(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { editor.putInt(key, it.toInt()); true } ?: false
                    is Long -> integral(value)?.let { editor.putLong(key, it); true } ?: false
                    is Float -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
                    is String -> if (value is String) { editor.putString(key, value); true } else false
                    is Set<*> -> stringSet(value)?.let { editor.putStringSet(key, it); true } ?: false
                    null -> inferred(editor, key, value)
                    else -> false
                }
                if (stored) applied++ else skipped++
            }
            editor.apply()
        }
        return SettingsImportOutcome(applied, skipped, invalid = false)
    }

    private fun storeTyped(editor: Editor, key: String, value: Any?, type: KeyType): Boolean = when (type) {
        KeyType.BOOLEAN -> if (value is Boolean) { editor.putBoolean(key, value); true } else false
        KeyType.INT -> integral(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { editor.putInt(key, it.toInt()); true } ?: false
    }

    private fun inferred(editor: Editor, key: String, value: Any?): Boolean = when (value) {
        is Boolean -> { editor.putBoolean(key, value); true }
        is String -> { editor.putString(key, value); true }
        else -> {
            val integral = integral(value)
            when {
                integral != null && integral in Int.MIN_VALUE..Int.MAX_VALUE -> { editor.putInt(key, integral.toInt()); true }
                integral != null -> { editor.putLong(key, integral); true }
                else -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
            }
        }
    }

    private fun integral(value: Any?): Long? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < Long.MIN_VALUE || double > Long.MAX_VALUE) return null
        return double.toLong()
    }

    private fun decimal(value: Any?): Float? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite()) return null
        val float = double.toFloat()
        return float.takeIf { it.isFinite() }
    }

    private fun stringSet(value: Any?): Set<String>? {
        val array = value as? JSONArray ?: return null
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val element = array.opt(index) as? String ?: return null
            result.add(element)
        }
        return result
    }

    private fun jsonValue(value: Any): Any = when (value) {
        is Set<*> -> JSONArray().apply { value.forEach { put(it.toString()) } }
        is Float -> value.toDouble()
        else -> value
    }
}
