package com.shilapi.xcertplay.transport

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Checks Android 7.1 through 9's explicit USB permission activity without opening it. */
internal object UsbPermissionActivityProbe {
    internal const val COMPONENT = "com.android.systemui/com.android.systemui.usb.UsbPermissionActivity"

    enum class State { AVAILABLE, UNAVAILABLE, UNKNOWN }

    data class Result(val state: State) {
        fun diagnostic(): String = "USB permission UI state=$state component=$COMPONENT"
    }

    fun inspect(context: Context): Result {
        // Android 10+ uses overlay-configurable components and package visibility restrictions.
        if (!supportsProbe()) return Result(State.UNKNOWN)
        return Result(probe(context.applicationContext.packageManager))
    }

    internal fun supportsProbe(sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk in Build.VERSION_CODES.N_MR1..Build.VERSION_CODES.P

    internal fun probe(manager: PackageManager): State = try {
        val component = ComponentName.unflattenFromString(COMPONENT)!!
        // system_server opens this activity, so it need not be exported to applications.
        val info = manager.getActivityInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS)
        val activityEnabled = enabled(manager.getComponentEnabledSetting(component), info.enabled)
        val appEnabled = enabled(
            manager.getApplicationEnabledSetting(component.packageName),
            info.applicationInfo.enabled,
        )
        if (activityEnabled && appEnabled) State.AVAILABLE else State.UNAVAILABLE
    } catch (_: PackageManager.NameNotFoundException) {
        State.UNAVAILABLE
    } catch (_: Exception) {
        State.UNKNOWN
    }

    private fun enabled(setting: Int, manifestEnabled: Boolean): Boolean = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifestEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }
}
