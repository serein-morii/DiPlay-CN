package com.shilapi.xcertplay

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Turns the instrument panel's projection into DiPlay's display.
 *
 * <p>On `geely_fx11` the cluster keeps its own layer on top of Android's display 2 until the
 * head unit is told to project to it. The stock map does this through the DIM service
 * (`com.autolink.diminteraction`); a normal app can call the same binder directly: the
 * `DimNaviService` binder, `notifyDimProjectScreenSwitch` (transaction 9, an int). Without it a
 * `Presentation` on the cluster display stays invisible behind the factory dials.
 *
 * <p>Only the Geely device takes this route; everywhere else the cluster projection is driven by
 * the car's own cluster support. The switch is head-unit-wide and shared with the stock map, so the
 * state is read back as well ([status]) and re-asserted when the map hands it back.
 */
internal object GeelyClusterProjection {

    private const val TAG = "DiPlay-Cluster"
    private const val SERVICE = "DimNaviService"
    private const val DESCRIPTOR = "ecarx.dimprotocol.custom.service.IDimNaviService"
    private const val NOTIFY_PROJECT_SCREEN_SWITCH = 9
    private const val GET_PROJECT_SCREEN_STATUS = 10

    /** Whether this process asked for the projection and has not handed it back. */
    @Volatile private var heldInProcess = false

    /**
     * Reads the head unit's projection state, or null when the answer cannot be obtained (service not
     * registered, transaction refused). The state is head-unit-wide: the stock map drives the same
     * switch, so an off reading while DiPlay's presentation is up means the map took it away and the
     * cluster is dark (see [ClusterProjectionWatchdog]).
     */
    fun status(): Boolean? {
        val binder = service() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(GET_PROJECT_SCREEN_STATUS, data, reply, 0)
            reply.readException()
            reply.readInt() != 0
        } catch (error: Throwable) {
            // Polled, so a failure is logged but never treated as "off": the watchdog acts on evidence.
            Log.w(TAG, "dim projection: getDimProjectScreenStatus() failed", error)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Asks the head unit to show (or stop showing) the projected cluster. Returns whether the
     * call reached the service; a missing binder is not an error worth failing over.
     *
     * When [context] is given, the claim is also written to disk while the switch is on: the process
     * can be killed without running `onStop`, and the next run hands the switch back then
     * ([releaseStale]).
     */
    fun set(on: Boolean, context: Context? = null): Boolean {
        val binder = service() ?: run {
            Log.i(TAG, "dim projection: DimNaviService is not registered")
            return false
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(if (on) 1 else 0)
            binder.transact(NOTIFY_PROJECT_SCREEN_SWITCH, data, reply, 0)
            reply.readException()
            Log.i(TAG, "dim projection: notifyDimProjectScreenSwitch($on) ok")
            if (context != null) AirPlayPersistence.saveClusterProjectionHeld(context, on)
            heldInProcess = on
            true
        } catch (error: Throwable) {
            Log.w(TAG, "dim projection: notifyDimProjectScreenSwitch($on) failed", error)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * Handles a projection a previous run may have left on. A kill that skipped `onStop` leaves the
     * cluster on DiPlay's last frame (measured: the car's SystemUI killed DiPlay about 5 s after
     * Home), and the DIM switch carries no owner, so the claim is recorded while DiPlay holds the
     * switch and this decides on the next start whether the switch can still be handed back.
     */
    fun releaseStale(context: Context) {
        val action = staleProjectionAction(
            heldInProcess = heldInProcess,
            claimed = AirPlayPersistence.loadClusterProjectionHeld(context),
            mapEnabled = AirPlayPersistence.loadClusterMapEnabled(context),
            supported = runCatching { GeelyClusterDisplay.supported() }.getOrDefault(false),
            projecting = ::status,
        )
        when (action) {
            StaleProjectionAction.NONE -> Unit
            StaleProjectionAction.FORGET ->
                AirPlayPersistence.saveClusterProjectionHeld(context, false)
            StaleProjectionAction.HAND_BACK -> {
                Log.i(TAG, "dim projection: handing back a projection left on by a previous run")
                set(false, context)
            }
        }
    }

    /** {@code android.os.ServiceManager.getService(String)} is a hidden API. */
    private fun service(): IBinder? = try {
        val serviceManager = Class.forName("android.os.ServiceManager")
        serviceManager.getMethod("getService", String::class.java).invoke(null, SERVICE) as? IBinder
    } catch (error: Throwable) {
        Log.w(TAG, "dim projection: ServiceManager.getService($SERVICE) failed", error)
        null
    }
}

/** What [GeelyClusterProjection.releaseStale] does with the claim a previous run may have left. */
internal enum class StaleProjectionAction {
    /** Leave the switch and the claim alone: DiPlay never claimed it, or it is about to project again. */
    NONE,
    /** The claim no longer describes the switch: forget it without touching the head unit. */
    FORGET,
    /** The switch still reads on and is DiPlay's leftover: hand it back to the head unit. */
    HAND_BACK,
}

/**
 * The next-start decision for a projection a killed run left on.
 *
 * The DIM switch carries no owner, so an on reading cannot be told apart from the stock map's own
 * projection. The on-disk claim is the only evidence that the leftover is DiPlay's, and even then the
 * switch is only handed back while DiPlay is not going to project: as soon as a session is up DiPlay
 * asserts the projection itself, and its new frame replaces the leftover, while handing the switch
 * back first would take the wheel-key map off the cluster. A reading that cannot be taken keeps the
 * claim for the next run ([GeelyClusterProjection.status] returns null then).
 */
internal fun staleProjectionAction(
    heldInProcess: Boolean,
    claimed: Boolean,
    mapEnabled: Boolean,
    supported: Boolean,
    projecting: () -> Boolean?,
): StaleProjectionAction {
    if (heldInProcess || !claimed || mapEnabled) return StaleProjectionAction.NONE
    if (!supported) return StaleProjectionAction.FORGET
    return when (projecting()) {
        true -> StaleProjectionAction.HAND_BACK
        false -> StaleProjectionAction.FORGET
        null -> StaleProjectionAction.NONE
    }
}
