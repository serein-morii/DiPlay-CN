package com.shilapi.xcertplay.hud

/** One instrument-cluster frame in the AMap broadcast vocabulary accepted by the stock AMap adapter. */
internal data class BydClusterFrame(
    val icon: Int,
    val roundaboutExit: Int,
    val distanceMeters: Int,
    val road: String = "",
    val routeRemainingMeters: Int = -1,
    val routeRemainingSeconds: Int = -1,
) {
    companion object {
        private const val LEFT = 2
        private const val RIGHT = 3
        private const val SLIGHT_LEFT = 4
        private const val SLIGHT_RIGHT = 5
        private const val SHARP_LEFT = 6
        private const val SHARP_RIGHT = 7
        private const val U_TURN_LEFT = 8
        private const val STRAIGHT = 9
        private const val ROUNDABOUT_ENTER = 11
        private const val ROUNDABOUT_EXIT = 12
        private const val DESTINATION = 15
        private const val ROUNDABOUT_ENTER_CLOCKWISE = 17
        private const val ROUNDABOUT_EXIT_CLOCKWISE = 18
        private const val U_TURN_RIGHT = 19

        /** Apple iAP2 RouteGuidanceManeuverType -> AMap NEW_ICON (+ ROUNG_ABOUT_NUM). */
        fun from(maneuver: BydAppleManeuver): BydClusterFrame {
            val leftHandTraffic = maneuver.drivingSide == 1
            val distance = maneuver.distanceMeters.coerceAtLeast(0)
            val type = maneuver.type
            val road = maneuver.road
            val remainingMeters = maneuver.remainingMeters?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: -1
            val remainingSeconds = maneuver.remainingSeconds?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: -1
            if (type in 28..46) {
                val icon = if (leftHandTraffic) ROUNDABOUT_ENTER_CLOCKWISE else ROUNDABOUT_ENTER
                return BydClusterFrame(icon, type - 27, distance, road, remainingMeters, remainingSeconds)
            }
            return BydClusterFrame(iconFor(type, leftHandTraffic), 0, distance, road, remainingMeters, remainingSeconds)
        }

        /**
         * Icon for one Apple maneuver type, for previews that carry no distance of their own
         * (the turn card's "then" block). 0 for an unknown type — callers must not draw it.
         */
        fun iconFor(type: Int, leftHandTraffic: Boolean): Int = when (type) {
            in 28..46 -> if (leftHandTraffic) ROUNDABOUT_ENTER_CLOCKWISE else ROUNDABOUT_ENTER
            1, 20 -> LEFT
            2, 21 -> RIGHT
            47 -> SHARP_LEFT
            48 -> SHARP_RIGHT
            13, 22, 49, 52 -> SLIGHT_LEFT
            14, 23, 50, 53 -> SLIGHT_RIGHT
            4, 18, 19, 26 -> if (leftHandTraffic) U_TURN_RIGHT else U_TURN_LEFT
            6 -> if (leftHandTraffic) ROUNDABOUT_ENTER_CLOCKWISE else ROUNDABOUT_ENTER
            7 -> if (leftHandTraffic) ROUNDABOUT_EXIT_CLOCKWISE else ROUNDABOUT_EXIT
            10, 12, 24, 25, 27 -> DESTINATION
            3, 5, 8, 9, 11, 51 -> STRAIGHT
            else -> 0 // Unknown/no maneuver must not become a false straight arrow.
        }
    }
}
