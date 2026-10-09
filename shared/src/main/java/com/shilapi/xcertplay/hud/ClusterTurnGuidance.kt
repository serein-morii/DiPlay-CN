package com.shilapi.xcertplay.hud

/** Next-turn instruction for the dashboard overlay. Icons use the AMap NEW_ICON vocabulary. */
data class ClusterTurnGuidance(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String,
    /** Route-level arrival/duration/distance for the info strip below the card. */
    val arrivalEpochSeconds: Long? = null,
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    /** Highlighted lane index 0..n-1, or -1 when Apple did not send a highlight. */
    val laneHighlight: Int = -1,
    /** One entry per visible lane: 0 unused, 1 straight, 2 left, 3 right. */
    val lanes: List<Int> = emptyList(),
) {
    companion object {
        internal fun from(frame: BydClusterFrame): ClusterTurnGuidance {
            val icon = if (frame.icon == 0) 9 else frame.icon
            return ClusterTurnGuidance(icon, frame.roundaboutExit, frame.distanceMeters, frame.road)
        }
    }
}
