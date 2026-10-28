package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences

internal object ClusterMusicSettings {
    private const val PREFIX = "cluster_music_"
    private fun prefs(context: Context) = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(PREFIX + "enabled", false)
    fun permanent(context: Context) = prefs(context).getBoolean(PREFIX + "permanent", false)
    fun x(context: Context) = prefs(context).getInt(PREFIX + "x", 50).coerceIn(5, 95)
    fun y(context: Context) = prefs(context).getInt(PREFIX + "y", 75).coerceIn(5, 95)
    fun size(context: Context) = prefs(context).getInt(PREFIX + "size", 100).coerceIn(50, 300)
    fun saveEnabled(context: Context, value: Boolean) { prefs(context).edit().putBoolean(PREFIX + "enabled", value).apply() }
    fun savePermanent(context: Context, value: Boolean) { prefs(context).edit().putBoolean(PREFIX + "permanent", value).apply() }
    fun save(context: Context, key: String, value: Int) {
        require(key in setOf("x", "y", "size"))
        prefs(context).edit().putInt(PREFIX + key, value.coerceIn(if (key == "size") 50..300 else 5..95)).apply()
    }
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(PREFIX)) changed()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
