package com.shilapi.xcertplay

/**
 * Decides when DiPlay has to ask for the Geely cluster projection again.
 *
 * The DIM projection switch is one head-unit-wide state that DiPlay and the stock map share. Waking the
 * map with the steering-wheel key moves the projection to the map; when that session ends the head unit
 * turns the switch off, and DiPlay's `Presentation` never stops, so `onStart` never asserts it again and
 * the cluster stays dark (measured: `DimNaviService: notifyDimProjectScreenSwitchFun turnId(false)`, with
 * no callback reaching DiPlay).
 *
 * The watchdog never acts while the projection reads on: that is the stock map legitimately owning the
 * cluster, and re-asserting then would fight it. A single off reading is not enough either, because the
 * map passes through off while it takes the switch over, so [FALSE_READINGS] consecutive off readings
 * are required before DiPlay claims the cluster back.
 *
 * The switch carries no owner, so it also tracks whose assertion the head unit is holding: see
 * [holdsProjection] and [shouldHandBack]. While the stock map owns the cluster an on reading looks
 * exactly like DiPlay's own, so only an off reading is treated as evidence.
 */
internal class ClusterProjectionWatchdog(
    private val falseReadings: Int = FALSE_READINGS,
) {
    private var consecutiveOff = 0

    /**
     * Whether the projection the head unit reports is the one DiPlay asked for. It is true from the
     * assertion DiPlay last made ([assertedProjection]) and false once a reading shows the switch
     * closed: whoever closed it holds the projection then, and a re-assert is a fresh claim.
     */
    var holdsProjection = false
        private set

    /** Records that DiPlay asked for the projection: on start, or the re-assert [onStatus] asked for. */
    fun assertedProjection() {
        holdsProjection = true
    }

    /** Feeds one reading of the projection state; true when DiPlay must ask for the projection again. */
    fun onStatus(projecting: Boolean?): Boolean {
        // An unreadable state is not evidence of anything, and it clears partial evidence.
        if (projecting != false) {
            consecutiveOff = 0
            return false
        }
        // The switch closed while DiPlay's presentation was up, so it left DiPlay's hands: the stock
        // map took it over, or its own session end turned it off. Either way DiPlay no longer holds
        // it, and the re-assert below is what claims it again.
        holdsProjection = false
        consecutiveOff++
        if (consecutiveOff < falseReadings) return false
        // Start over, so a re-assert that did not take effect is not retried on every reading.
        consecutiveOff = 0
        return true
    }

    /**
     * The last check before DiPlay's presentation stops: true only when the switch reads on and
     * DiPlay's own assertion is the one holding it. Handing a switch the stock map owns back to the
     * head unit would take the map off the cluster, and the map's own projection reads on too, so
     * [holdsProjection] decides it. An off reading retires the claim; an unreadable one does not.
     */
    fun shouldHandBack(projecting: Boolean?): Boolean {
        if (projecting == false) holdsProjection = false
        return projecting == true && holdsProjection
    }

    fun reset() {
        consecutiveOff = 0
        holdsProjection = false
    }

    companion object {
        /** Two readings, so a map switching through off does not hand the cluster back mid-switch. */
        const val FALSE_READINGS = 2
    }
}
