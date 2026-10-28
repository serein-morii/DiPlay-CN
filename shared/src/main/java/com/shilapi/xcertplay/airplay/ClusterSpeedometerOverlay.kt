package com.shilapi.xcertplay.airplay

/**
 * Placement and size of DiPlay's optional speedometer drawn over the dashboard map.
 *
 * Coordinates are percentages of the full cluster panel, matching the custom turn-card
 * placement model so both overlays can be positioned independently.
 */
object ClusterSpeedometerOverlay {
    data class Rect(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    const val DEFAULT_X_PERCENT = 82
    const val DEFAULT_Y_PERCENT = 30
    const val DEFAULT_SIZE_PERCENT = 50
    const val DEFAULT_OPACITY_PERCENT = 85

    val xPercents = (8..92 step 1).toList()
    val yPercents = (12..72 step 1).toList()
    val sizePercents = listOf(30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95)
    val opacityPercents = listOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100)

    fun rect(
        panelWidth: Int,
        panelHeight: Int,
        xPercent: Int,
        yPercent: Int,
        sizePercent: Int,
    ): Rect {
        require(panelWidth > 0 && panelHeight > 0)

        val size = snap(sizePercent, sizePercents)

        // Square card, with the same height-based sizing and corner proportions as album art.
        // Clamp both dimensions together so narrow projection areas remain square too.
        val side = (panelHeight * 0.30f * size / DEFAULT_SIZE_PERCENT)
            .toInt()
            .coerceAtLeast(80)
            .coerceAtMost(minOf(panelWidth, panelHeight))
        val width = side
        val height = side

        val x = snap(xPercent, xPercents)
        val y = snap(yPercent, yPercents)

        val left = (panelWidth * x / 100 - width / 2)
            .coerceIn(0, panelWidth - width)

        val top = (panelHeight * y / 100 - height / 2)
            .coerceIn(0, panelHeight - height)

        return Rect(left, top, width, height)
    }

    fun snap(value: Int, choices: List<Int>): Int =
        choices.minByOrNull { kotlin.math.abs(it - value) } ?: choices.first()
}
