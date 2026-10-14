package com.shilapi.xcertplay.airplay

/**
 * Where DiPlay draws the instruction card on top of the dashboard map.
 *
 * Placement is a percent of the full 1920×720 panel so Left/Right can reach the
 * cluster edges. Size still follows the measured centre navi window so Large
 * does not cover half the cluster.
 */
object ClusterTurnCardOverlay {
    data class CardRect(val left: Int, val top: Int, val width: Int, val height: Int)

    const val STEP_PERCENT = 1
    const val DEFAULT_X_PERCENT = 76
    const val DEFAULT_Y_PERCENT = 30
    val xPercents = (8..92 step 1).toList()
    /** Opacity choices for the slider: 20..100 in steps of 5, default 85. */
    val opacityPercents = listOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100)
    const val DEFAULT_OPACITY_PERCENT = 85

    val yPercents = (12..72 step 1).toList()

    /** Card width as percent of the visible navi window; one slider step = 5 %. */
    val sizePercents = listOf(30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95)

    /** Small-window card placement (percent of the panel), 1 % steps. */
    val smallWindowXPercents = (10..90 step 1).toList()
    val smallWindowYPercents = (15..70 step 1).toList()

    const val DEFAULT_SIZE_PERCENT = 55


    fun card(
        panelWidth: Int,
        panelHeight: Int,
        xPercent: Int,
        yPercent: Int,
        sizePercent: Int,
    ): CardRect {
        require(panelWidth > 0 && panelHeight > 0)
        val window = visibleWindow(panelWidth, panelHeight)
        val size = snap(sizePercent, sizePercents)
        // The card keeps its ~2.6:1 banner aspect at any size.
        val width = (window.width * size / 100).coerceAtLeast(150).coerceAtMost(panelWidth)
        val height = (width / 2.64f).toInt().coerceAtLeast(58).coerceAtMost((window.height * 0.55f).toInt())
        val x = snap(xPercent, xPercents)
        val y = snap(yPercent, yPercents)
        val left = (panelWidth * x / 100 - width / 2).coerceIn(0, panelWidth - width)
        val top = (panelHeight * y / 100 - height / 2).coerceIn(0, panelHeight - height)
        return CardRect(left, top, width, height)
    }

    fun snap(value: Int, choices: List<Int>): Int =
        choices.minByOrNull { kotlin.math.abs(it - value) } ?: choices.first()

    internal fun visibleWindow(panelWidth: Int, panelHeight: Int): CardRect {
        val area = CarPlayClusterDisplay.SAFE_AREA_PERCENT
        val left = panelWidth * area.left / 100
        val top = panelHeight * area.top / 100
        val width = panelWidth * (100 - area.left - area.right) / 100
        val height = panelHeight * (100 - area.top - area.bottom) / 100
        return CardRect(left, top, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
}
