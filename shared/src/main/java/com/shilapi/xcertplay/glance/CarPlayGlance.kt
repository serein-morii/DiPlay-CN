package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.BydHudRouteState
import com.shilapi.xcertplay.hud.ClusterSongState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/**
 * What CarPlay is doing, at a glance, for widgets and other screens outside CarPlay: the next
 * maneuver from iAP2 route guidance (0x5201/0x5202) and the song from NowPlayingUpdate (0x5001).
 * Fed by every CarPlay session, whatever the BYD output settings.
 */
object CarPlayGlance {
    /** One state of the glance; [maneuverType] is Apple's RouteGuidanceManeuverType, null without a route. */
    data class Snapshot(
        val connected: Boolean = false,
        val maneuverType: Int? = null,
        val drivingSide: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        val arrivalEpochSeconds: Long? = null,
        val remainingSeconds: Long? = null,
        val remainingMeters: Long? = null,
        /** The turn after this one, when the iPhone sent its detail; null otherwise. */
        val secondManeuverType: Int? = null,
        val secondDrivingSide: Int = 0,
        val song: String? = null,
        val playing: Boolean = false,
    )

    private val route = BydHudRouteState()
    private val song = ClusterSongState()
    private var connected = false
    private var last = Snapshot()

    /** Called with each new snapshot, on the thread that changed it. */
    @Volatile var listener: ((Snapshot) -> Unit)? = null

    /** Refresh time-dependent guidance even when no new metadata frame has arrived. */
    fun snapshot(): Snapshot {
        val (changed, current) = synchronized(this) { publishLocked() to last }
        changed?.let { listener?.invoke(it) }
        return current
    }

    fun onFrame(frame: Iap2Frame) {
        val changed = synchronized(this) {
            when (frame.messageId) {
                BydHudRouteState.ROUTE_GUIDANCE_UPDATE, BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE ->
                    runCatching { route.accept(frame.messageId, frame.payload) }
                ClusterSongState.NOW_PLAYING_UPDATE -> runCatching { song.accept(frame) }
                else -> return
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
    }

    fun setConnected(next: Boolean) {
        val changed = synchronized(this) {
            if (connected == next) return
            connected = next
            if (!next) {
                route.clear()
                song.clear()
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
    }

    private fun publishLocked(): Snapshot? {
        val maneuver = route.currentApple()
        val current = song.current()
        val next = Snapshot(
            connected = connected,
            maneuverType = maneuver?.type,
            drivingSide = maneuver?.drivingSide ?: 0,
            distanceMeters = maneuver?.distanceMeters ?: 0,
            road = maneuver?.road.orEmpty(),
            arrivalEpochSeconds = maneuver?.arrivalEpochSeconds,
            remainingSeconds = maneuver?.remainingSeconds,
            remainingMeters = maneuver?.remainingMeters,
            secondManeuverType = maneuver?.second?.type,
            secondDrivingSide = maneuver?.second?.drivingSide ?: 0,
            song = current?.text,
            playing = current?.playing ?: false,
        )
        if (next == last) return null
        last = next
        return next
    }
}
