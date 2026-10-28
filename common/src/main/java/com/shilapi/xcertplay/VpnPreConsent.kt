package com.shilapi.xcertplay

import android.content.Context
import androidx.annotation.StringRes
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.host.R

/**
 * Some firmware ships no VPN consent dialog (Geely Galaxy E8, #604), so wired CarPlay could never
 * start. Android counts an allowed ACTIVATE_VPN app op as that consent, and the car's own adb can
 * set it. Only an adb key the driver already approved is used; this never asks for approval.
 */
internal object VpnPreConsent {
    fun grant(context: Context, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access = adb.use {
        val access = it.connect(mayAsk = false)
        if (access == LocalAdb.Access.READY) it.shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        access
    }

    /** Why [grant] left the VPN without consent, for the CarPlay screen. */
    @StringRes
    fun stage(access: LocalAdb.Access): Int = when (access) {
        LocalAdb.Access.READY -> R.string.vpn_authorization_adb_did_not_allow
        LocalAdb.Access.NOT_APPROVED -> R.string.vpn_authorization_adb_not_approved
        LocalAdb.Access.UNREACHABLE, LocalAdb.Access.UNSUPPORTED -> R.string.vpn_authorization_adb_unusable
    }
}
