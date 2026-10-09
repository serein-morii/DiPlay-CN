package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Renames the launcher entry by switching between pre-declared activity-alias components —
 * Android reads the app label from the APK, so free text is impossible, but each alias carries
 * its own label. Samples and other packages without the aliases simply see no feature here.
 */
object AppRenamer {
    private val ALIASES = listOf(
        "LauncherAliasCn",
        "LauncherAliasDiplayCn",
        "LauncherAliasCarplay",
        "LauncherAliasNavi",
        "LauncherAliasMap",
        "LauncherAliasLink",
        "LauncherAliasMedia",
    )

    /**
     * Disabled aliases are invisible to plain queries on Android — without this flag only the
     * currently enabled name resolves and the picker collapses to one entry.
     */
    private fun aliasesInfo(context: Context): List<android.content.pm.ActivityInfo> {
        val manager = context.packageManager
        return ALIASES
            .map { ComponentName(context.packageName, "com.shilapi.xcertplay.$it") }
            .mapNotNull { component ->
                runCatching {
                    manager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
                }.getOrNull()
            }
    }

    fun available(context: Context): Boolean = aliasesInfo(context).isNotEmpty()

    fun labels(context: Context): List<String> =
        aliasesInfo(context).map { it.loadLabel(context.packageManager).toString() }

    /** The chosen index lives beside the component states so both stay in sync. */
    fun current(context: Context): Int {
        migrateRemovedDiplayAlias(context)
        val prefs = context.getSharedPreferences(RENAME_PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_NAME_INDEX, 0).coerceIn(0, (aliasesInfo(context).size - 1).coerceAtLeast(0))
    }

    fun apply(context: Context, index: Int) {
        migrateRemovedDiplayAlias(context)
        val app = context.applicationContext
        val list = ALIASES
            .map { ComponentName(app.packageName, "com.shilapi.xcertplay.$it") }
            .filter { component ->
                runCatching {
                    app.packageManager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
                }.isSuccess
            }
        if (list.isEmpty()) return
        val chosen = index.coerceIn(0, list.lastIndex)
        list.forEachIndexed { i, component ->
            app.packageManager.setComponentEnabledSetting(
                component,
                if (i == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        app.getSharedPreferences(RENAME_PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAME_INDEX, chosen).apply()
    }

    /**
     * The plain "DiPlay" preset was removed (it looked identical to "DiPlay CN" on the car,
     * whose list truncates at the first space). Its old index was 2; later indexes shift down
     * by one. A saved 2 falls back to the default alias, which is also re-enabled because the
     * removed component no longer exists and the launcher needs a live entry point.
     */
    private fun migrateRemovedDiplayAlias(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(RENAME_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DIPLAY_REMOVED, false)) return
        val saved = prefs.getInt(KEY_NAME_INDEX, 0)
        val migrated = when {
            saved == 2 -> 0
            saved > 2 -> saved - 1
            else -> saved
        }
        if (saved == 2) {
            val default = ComponentName(app.packageName, "com.shilapi.xcertplay.LauncherAliasCn")
            app.packageManager.setComponentEnabledSetting(
                default,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
        prefs.edit()
            .putInt(KEY_NAME_INDEX, migrated)
            .putBoolean(KEY_DIPLAY_REMOVED, true)
            .apply()
    }

    private const val RENAME_PREFS = "diplay_rename"
    private const val KEY_NAME_INDEX = "name_index"
    private const val KEY_DIPLAY_REMOVED = "diplay_alias_removed"
}
