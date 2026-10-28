package com.shilapi.xcertplay.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build

internal object UsbPermissionBroadcast {
    /** Signature permission declared in the manifest. Only this app can hold it. */
    fun senderPermission(context: Context): String = "${context.packageName}.USB_PERMISSION_RESULT"

    /**
     * Android 13 adds RECEIVER_NOT_EXPORTED. Before that a receiver registered at run time accepts broadcasts from
     * any app, so require a permission that only this app holds. The USB dialog result is sent through this
     * app's own PendingIntent, so it carries this app's identity.
     */
    fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, senderPermission(context), null)
        }
    }
}
