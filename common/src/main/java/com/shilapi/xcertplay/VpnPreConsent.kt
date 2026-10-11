package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

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
}
