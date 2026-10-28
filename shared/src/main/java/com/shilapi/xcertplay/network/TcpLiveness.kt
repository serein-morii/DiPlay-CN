package com.shilapi.xcertplay.network

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.net.Socket

/** Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure. */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>; Android's SDK omits these three OsConstants fields.
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        if (Build.VERSION.SDK_INT < 26) {
            diagnostic("TCP peer health tuning unavailable: API ${Build.VERSION.SDK_INT}")
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ParcelFileDescriptor.fromSocket(socket).use { duplicate -> tune(duplicate.fileDescriptor) }
            } else {
                // Before 29, fromSocket wraps the socket's own descriptor rather than a duplicate, and closing,
                // detaching or finalizing that wrapper closes or clears it, so tune the descriptor unwrapped.
                tune(socket.javaClass.getMethod("getFileDescriptor\$").invoke(socket) as FileDescriptor)
            }
            diagnostic("TCP peer health enabled idle=10s interval=3s count=3 deadline=20000ms; no video-idle timeout")
        } catch (failure: Exception) {
            diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
        }
    }

    @RequiresApi(26)
    private fun tune(fd: FileDescriptor) {
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 10)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 3)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 3)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, 20_000)
    }
}
