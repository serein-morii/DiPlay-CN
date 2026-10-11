package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Publishes CarPlay arrows and distance to the instrument cluster of FCE head units through
 * [FceCarPropertyWriter], independently of the BYD outputs. Needs both the navigation output switch
 * ([BydOutputSettings.enabled]) and its own opt-in ([BydOutputSettings.fceCluster]).
 */
internal object FceClusterBridge {
    private const val TAG = "DiPlay-FCE-Cluster"

    private val lock = Any()
    private val route = BydHudRouteState()
    private val navigation = FceNavigationState()
    private var output = FceClusterOutput(FceCarPropertyWriter())
    private var context: Context? = null
    private var tickStarted = false
    private var guidanceLogged = false
    private var standbyLogged = false

    fun available(): Boolean = FceCarPropertyWriter.isAvailable()

    fun initialize(appContext: Context) = synchronized(lock) {
        if (context != null) return@synchronized
        context = appContext.applicationContext
        Log.i(TAG, "FCE cluster output ready")
        if (!tickStarted) {
            tickStarted = true
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-fce-cluster").apply { isDaemon = true }
            }.scheduleAtFixedRate(::tick, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun onFrame(frame: Iap2Frame) = synchronized(lock) {
        if (context == null) return@synchronized
        route.accept(frame.messageId, frame.payload)
        navigation.accept(frame.messageId, frame.payload)
        refreshLocked()
    }

    fun clear() = synchronized(lock) {
        route.clear() // The tick must not restore guidance after cleanup.
        navigation.clear()
        output.stop()
    }

    // Guidance can expire without a frame (a list that stays empty), and the switch can be turned
    // off mid-route, so check every second.
    private fun tick() = synchronized(lock) { if (context != null) refreshLocked() }

    private fun enabledLocked(): Boolean =
        context?.let { BydOutputSettings.enabled(it) && BydOutputSettings.fceCluster(it) } == true

    private fun refreshLocked() {
        if (!enabledLocked()) return output.stop()
        val apple = route.currentApple()
        if (apple == null) {
            if (!navigation.active) return output.stop()
            output.standby()
            if (!standbyLogged && output.active) {
                standbyLogged = true
                Log.i(TAG, "cluster navigation view active without a maneuver")
            }
            return
        }
        val guidance = FceClusterGuidance(
            FceManeuverCodes.icon(apple.type, apple.drivingSide),
            FceManeuverCodes.distance(apple.distanceMeters),
        )
        output.update(guidance)
        if (!guidanceLogged && output.active) {
            guidanceLogged = true
            Log.i(TAG, "cluster guidance sent $guidance (apple type ${apple.type})")
        }
    }

    /** Tests replace the Binder writer and reset state. */
    internal fun resetForTest(writer: FcePropertyWriter, appContext: Context?) = synchronized(lock) {
        route.clear()
        output = FceClusterOutput(writer)
        navigation.clear()
        context = appContext
        guidanceLogged = false
        standbyLogged = false
    }
}
