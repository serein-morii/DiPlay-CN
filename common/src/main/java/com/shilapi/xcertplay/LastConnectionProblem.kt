package com.shilapi.xcertplay

import android.content.Context
import android.content.res.Configuration
import android.text.format.DateUtils
import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal sealed interface ConnectionProblem {
    data class Cause(val cause: ConnectionFailureCause) : ConnectionProblem
    data class Startup(val failure: WirelessStartupFailure) : ConnectionProblem
    data class UsbNotListed(val otherDevice: Boolean) : ConnectionProblem

    @StringRes
    fun line(): Int = when (this) {
        is Cause -> ConnectionFailureGuidance.stage(cause)
        is Startup -> when (failure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY,
            WirelessStartupFailure.HOTSPOT_CONFIGURATION -> R.string.hotspot_network_not_ready
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> R.string.first_tcp_timeout
            WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT -> R.string.wifi_direct_refused_beside_hotspot
            WirelessStartupFailure.BLUETOOTH_UNUSABLE -> R.string.connection_help_bluetooth_unusable
        }
        is UsbNotListed -> if (otherDevice) R.string.car_sees_usb_device_not_iphone else R.string.car_sees_no_usb_device
    }

    val token: String get() = when (this) {
        is Cause -> "cause:${cause.name}"
        is Startup -> "startup:${failure.name}"
        is UsbNotListed -> if (otherDevice) "usb:other" else "usb:none"
    }
}

internal object LastConnectionProblem {
    data class Record(val problem: ConnectionProblem, val atMillis: Long)

    private const val PREFS = "diplay_last_problem"
    private const val KEY_PROBLEM = "problem"
    private const val KEY_AT = "at"

    fun follow(context: Context, status: CarPlayStatus) {
        val problem = when (status) {
            is CarPlayStatus.Failed -> status.knownCause?.let(ConnectionProblem::Cause)
                ?: status.startupFailure?.let(ConnectionProblem::Startup)
                ?: return clear(context)
            is CarPlayStatus.IphoneNotListed -> ConnectionProblem.UsbNotListed(status.nonHubDeviceListed)
            else -> return
        }
        prefs(context).edit().putString(KEY_PROBLEM, problem.token).putLong(KEY_AT, System.currentTimeMillis()).apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    fun load(context: Context): Record? {
        val prefs = prefs(context)
        val token = prefs.getString(KEY_PROBLEM, null) ?: return null
        val problem = parse(token)
        val at = prefs.getLong(KEY_AT, -1)
        if (problem == null || at < 0) {
            clear(context)
            return null
        }
        return Record(problem, at)
    }

    fun homeLine(context: Context): String? {
        val record = load(context) ?: return null
        val time = DateUtils.formatSameDayTime(record.atMillis, System.currentTimeMillis(), DateFormat.SHORT, DateFormat.SHORT)
        return context.getString(R.string.last_connection_attempt, time, context.getString(record.problem.line()))
    }

    fun report(context: Context): String {
        val record = load(context) ?: return "Last problem: none recorded"
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        ).resources
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(record.atMillis))
        return "Last problem: ${english.getString(record.problem.line())} (${record.problem.token}) at $at"
    }

    private fun parse(token: String): ConnectionProblem? {
        val value = token.substringAfter(':')
        return when (token.substringBefore(':')) {
            "cause" -> ConnectionFailureCause.entries.firstOrNull { it.name == value }?.let(ConnectionProblem::Cause)
            "startup" -> WirelessStartupFailure.entries.firstOrNull { it.name == value }?.let(ConnectionProblem::Startup)
            "usb" -> when (value) {
                "none" -> ConnectionProblem.UsbNotListed(false)
                "other" -> ConnectionProblem.UsbNotListed(true)
                else -> null
            }
            else -> null
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
