package com.shilapi.xcertplay.update

import android.content.Context

/** Durable metadata only. APK downloads always remain an explicit foreground action. */
internal object UpdateAvailability {
    private const val PREFERENCES = "diplay_update_availability"
    private const val TAG = "tag"
    private const val APK_NAME = "apk_name"
    private const val APK_URL = "apk_url"
    private const val CHECKSUMS_URL = "checksums_url"
    private const val LAST_ATTEMPT_MILLIS = "last_attempt_millis"
    private const val BACKGROUND_CHECKS = "background_checks"
    private const val LAST_RESULT = "last_result"
    private const val LAST_RESULT_MILLIS = "last_result_millis"
    private const val LAST_INSTALL = "last_install"
    private const val LAST_INSTALL_MILLIS = "last_install_millis"

    internal const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun backgroundChecksEnabled(context: Context): Boolean =
        preferences(context).getBoolean(BACKGROUND_CHECKS, true)

    fun saveBackgroundChecksEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(BACKGROUND_CHECKS, enabled).apply()
    }

    fun available(context: Context, installedVersion: String): UpdateRelease? {
        val values = preferences(context)
        val release = UpdateRelease(
            tagName = values.getString(TAG, null) ?: return null,
            apkName = values.getString(APK_NAME, null) ?: return null,
            apkUrl = values.getString(APK_URL, null) ?: return null,
            checksumsUrl = values.getString(CHECKSUMS_URL, null) ?: return null,
        )
        // Discard metadata cached by the upstream updater before CN-specific sources were wired.
        if (release.apkName != "DiPlay-cn-${release.tagName}.apk") {
            clear(context)
            return null
        }
        if (UpdateVersion.isNewer(release.tagName, installedVersion)) return release
        clear(context)
        return null
    }

    fun save(context: Context, release: UpdateRelease) {
        preferences(context).edit()
            .putString(TAG, release.tagName)
            .putString(APK_NAME, release.apkName)
            .putString(APK_URL, release.apkUrl)
            .putString(CHECKSUMS_URL, release.checksumsUrl)
            .apply()
    }

    fun clear(context: Context) {
        preferences(context).edit()
            .remove(TAG)
            .remove(APK_NAME)
            .remove(APK_URL)
            .remove(CHECKSUMS_URL)
            .apply()
    }

    fun shouldCheck(context: Context, nowMillis: Long): Boolean {
        val lastAttempt = preferences(context).getLong(LAST_ATTEMPT_MILLIS, 0L)
        return lastAttempt <= 0L || nowMillis < lastAttempt || nowMillis - lastAttempt >= CHECK_INTERVAL_MILLIS
    }

    fun recordAttempt(context: Context, nowMillis: Long) {
        preferences(context).edit().putLong(LAST_ATTEMPT_MILLIS, nowMillis).apply()
    }

    fun recordResult(context: Context, nowMillis: Long, result: String) {
        preferences(context).edit().putString(LAST_RESULT, result).putLong(LAST_RESULT_MILLIS, nowMillis).apply()
    }

    fun recordInstall(context: Context, nowMillis: Long, outcome: String) {
        preferences(context).edit().putString(LAST_INSTALL, outcome).putLong(LAST_INSTALL_MILLIS, nowMillis).apply()
    }

    /** One diagnostic line. Ages are relative so the report needs no clock or locale. */
    fun report(context: Context, nowMillis: Long): String {
        val values = preferences(context)
        fun age(key: String) = values.getLong(key, 0L).takeIf { it > 0L }
            ?.let { "${(nowMillis - it).coerceAtLeast(0L) / 60_000} min ago" } ?: "never"
        return "Update check: background=${if (backgroundChecksEnabled(context)) "on" else "off"}; " +
            "lastSuccess=${age(LAST_ATTEMPT_MILLIS)}; cachedRelease=${values.getString(TAG, null) ?: "none"}; " +
            "lastResult=${values.getString(LAST_RESULT, null) ?: "none"} (${age(LAST_RESULT_MILLIS)}); " +
            "lastInstall=${values.getString(LAST_INSTALL, null) ?: "none"} (${age(LAST_INSTALL_MILLIS)})"
    }

    internal fun clearAllForTest(context: Context) {
        preferences(context).edit().clear().commit()
    }
}
