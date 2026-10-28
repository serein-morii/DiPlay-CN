package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** DiLink 3 cluster modes have their own recovery journal; OEM package holds are independent. */
internal object BydDiLink3ClusterOutput {
    private const val TAG = "DiPlay-BYD-DiLink3"
    private const val JOURNAL = "restore_stock_mode"
    private val shell = BydAdbShell(TAG)
    private val worker = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "diplay-byd-cluster-mode").apply { isDaemon = true }
    }
    private val retryStarted = AtomicBoolean()
    private val applyQueued = AtomicBoolean()
    @Volatile private var context: Context? = null
    @Volatile private var desiredMode: BydDiLink3ClusterMode.Mode? = null
    private var session: DiLink3ClusterModeSession? = null

    /** Also called when the setting is off, so an interrupted output is always recoverable. */
    fun restoreIfNeeded(appContext: Context) {
        initialize(appContext)
        requestApply()
    }

    fun setDesired(appContext: Context, mapShown: Boolean, guidanceActive: Boolean) {
        desiredMode = when {
            mapShown -> if (BydOutputSettings.clusterProjectionShape(appContext) == BydClusterProjectionShape.FULL) {
                BydDiLink3ClusterMode.Mode.FULL_PROJECTION
            } else {
                BydDiLink3ClusterMode.Mode.PROJECTION
            }
            guidanceActive -> BydDiLink3ClusterMode.Mode.SIMPLE_NAVIGATION
            else -> null
        }
        initialize(appContext)
        requestApply()
    }

    fun prepareDisplay(appContext: Context, displayPresent: () -> Boolean) {
        if (adbClusterRouteSelected(appContext)) return
        initialize(appContext)
        worker.execute {
            val app = context ?: return@execute
            if (adbClusterRouteSelected(app)) return@execute
            val prepared = runCatching {
                state(app).prepareDisplay(displayPresent, { desiredMode },
                    { BydOutputSettings.enabled(app) }, { Thread.sleep(3_000L) })
            }.onFailure { Log.w(TAG, "Cluster preparation will recover", it) }.getOrDefault(false)
            Log.i(TAG, "DiLink 3 cluster display prepared=$prepared")
        }
    }

    private fun initialize(appContext: Context) {
        context = appContext.applicationContext
        if (retryStarted.compareAndSet(false, true)) {
            worker.scheduleWithFixedDelay({ applyLatest() }, 30, 30, TimeUnit.SECONDS)
        }
    }

    private fun requestApply() {
        if (!applyQueued.compareAndSet(false, true)) return
        worker.execute {
            val attempted = desiredMode
            try { applyLatest() }
            finally {
                applyQueued.set(false)
                // A request that changed during a blocking ADB call must still be applied.
                if (attempted != desiredMode) requestApply()
            }
        }
    }

    private fun applyLatest() {
        val app = context ?: return
        // The ADB route uses the native casting mode selected by the driver. The AMap
        // package name alone does not prove this is a DiLink 3 head unit. Keep any
        // old recovery journal pending rather than closing DiLink 4 casting.
        if (adbClusterRouteSelected(app)) return
        runCatching { state(app).apply(desiredMode) }
            .onFailure { Log.w(TAG, "Cluster mode will retry", it) }
            .onSuccess { accepted -> if (!accepted) Log.w(TAG, "Cluster mode pending recovery/retry") }
    }

    private fun state(app: Context): DiLink3ClusterModeSession {
        session?.let { return it }
        val journal = DiLink3ClusterRecoveryJournal(prefs(app).getBoolean(JOURNAL, false)) { pending ->
            val edit = prefs(app).edit()
            if (pending) edit.putBoolean(JOURNAL, true) else edit.remove(JOURNAL)
            edit.commit()
        }
        return DiLink3ClusterModeSession(
            run = { command ->
                if (adbClusterRouteSelected(app)) null else shell.run(app, command)
            },
            loadRecovery = { journal.pending },
            saveRecovery = journal::save,
            projectionStillWanted = {
                desiredMode?.showsMap == true && !adbClusterRouteSelected(app)
            },
        ).also { session = it }
    }

    internal fun adbClusterRouteSelected(context: Context): Boolean =
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).let {
            it.getBoolean("adb_cluster_activity_enabled", false) ||
                it.getBoolean("platform21_cluster_enabled", false)
        }

    private fun prefs(context: Context) = context.getSharedPreferences("diplay_dilink3_cluster", Context.MODE_PRIVATE)
}
