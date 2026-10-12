package com.shilapi.xcertplay.hud

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Optional radio pause using already-authorized local ADB; pairing is never changed. */
object BydBluetoothSuspend {
    private const val TAG = "DiPlay-BT-Suspend"
    private const val PREFS = "diplay_bt_suspend"
    private const val KEY_RESTORE_ENABLED = "restore_initially_enabled"
    private const val STATE_TIMEOUT_MILLIS = 8_000L
    private val shell = BydAdbShell(TAG)
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-bt-suspend").apply { isDaemon = true }
    }
    private val gate = Any()
    private var lease: BluetoothSuspendLease? = null
    private var pending: ScheduledFuture<*>? = null

    private fun lease(context: Context): BluetoothSuspendLease = synchronized(gate) {
        lease ?: run {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            BluetoothSuspendLease(
                journal = object : BluetoothRestoreJournal {
                    override fun pending(): Boolean = prefs.getBoolean(KEY_RESTORE_ENABLED, false)
                    override fun write(pending: Boolean): Boolean = prefs.edit()
                        .putBoolean(KEY_RESTORE_ENABLED, pending).commit()
                },
                readEnabled = { radioEnabled(app) },
                requestEnabled = { enabled ->
                    // A non-null shell reply is not evidence that the radio changed state.
                    shell.run(app, if (enabled) "svc bluetooth enable" else "svc bluetooth disable")
                },
                awaitEnabled = { enabled -> awaitRadio(app, enabled) },
            ).also { lease = it }
        }
    }

    /**
     * [stillActive] is checked under the same lock as [resume]: an owner that closes, or a session
     * that ends, before the pause begins never leaves a lease that nothing will release.
     */
    fun suspend(context: Context, owner: Any, delayMillis: Long = 10_000L, stillActive: () -> Boolean = { true }) {
        synchronized(gate) {
            if (!stillActive()) return
            val state = lease(context)
            val ticket = state.begin(owner) ?: return
            pending?.cancel(false)
            pending = worker.schedule({
                if (state.suspend(ticket)) Log.i(TAG, "car Bluetooth pause verified")
                else Log.w(TAG, "car Bluetooth pause cancelled or unavailable; recovery retained if needed")
            }, delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
    }

    /** Retire only this controller's work; a late old-controller close cannot release a new one. */
    fun resume(context: Context, owner: Any) {
        synchronized(gate) {
            val state = lease(context)
            val ticket = state.end(owner) ?: state.recoveryOnAppOpen() ?: return
            pending?.cancel(false)
            pending = null
            worker.execute { restore(state, ticket) }
        }
    }

    fun onAppOpened(context: Context) {
        synchronized(gate) {
            val state = lease(context)
            val ticket = state.recoveryOnAppOpen() ?: return
            pending?.cancel(false)
            pending = null
            worker.execute { restore(state, ticket) }
        }
    }

    /** Bounded recovery before wireless bootstrap; never enables a radio that was already OFF. */
    fun resumeAndWait(context: Context, adapter: BluetoothAdapter?, timeoutMillis: Long = STATE_TIMEOUT_MILLIS): Boolean {
        val task = synchronized(gate) {
            val state = lease(context)
            val ticket = state.beforeHandshake()
            pending?.cancel(false)
            pending = null
            worker.submit<Boolean> { state.restore(ticket) }
        }
        return runCatching {
            task.get(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS) && adapter?.isEnabled == true
        }.getOrDefault(false)
    }

    fun isSuspendedByUs(context: Context): Boolean {
        val app = context.applicationContext
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RESTORE_ENABLED, false) && radioEnabled(app) == false
    }

    private fun restore(state: BluetoothSuspendLease, ticket: Long) {
        if (!state.restore(ticket)) Log.w(TAG, "Bluetooth recovery incomplete or superseded")
    }

    private fun radioEnabled(context: Context): Boolean? = runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: BluetoothAdapter.getDefaultAdapter() ?: return@runCatching null
        when (adapter.state) {
            BluetoothAdapter.STATE_ON -> true
            BluetoothAdapter.STATE_OFF -> false
            else -> null
        }
    }.getOrNull()

    private fun awaitRadio(context: Context, enabled: Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (radioEnabled(context) == enabled) return true
            SystemClock.sleep(100)
        }
        return radioEnabled(context) == enabled
    }
}
