package com.shilapi.xcertplay

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the home-screen bubble goes and what a touch on it means, as plain arithmetic: no window,
 * no context, so it can be tested on its own ([LauncherBubble] owns the overlay itself).
 */
internal object LauncherBubbleLayout {

    /** Bubble height as a share of the screen height; the width follows the icon's own ratio. */
    val sizePercents: List<Int> = listOf(8, 10, 12, 16)
    const val DEFAULT_SIZE_PERCENT = 10

    /** Keeps the bubble clear of the screen edge. */
    const val MARGIN_DP = 24

    /** How far a touch has to move before it is a drag rather than a hold or a tap. */
    const val DRAG_SLOP_DP = 6

    /** Used when the icon does not report its own ratio. */
    const val DEFAULT_ICON_RATIO = 1.75f
    private const val MIN_HEIGHT_DP = 36
    private const val DEFAULT_Y_SHARE = 0.45f

    /** A pixel box in the overlay: the bubble and its hold prompt are both placed with one. */
    data class Box(val width: Int, val height: Int, val x: Int, val y: Int) {
        val bottom: Int get() = y + height
    }

    /** Keeps a saved or hand-edited size on the list the settings offer. */
    fun snapSizePercent(percent: Int): Int =
        sizePercents.minByOrNull { abs(it - percent) } ?: DEFAULT_SIZE_PERCENT

    /**
     * The bubble's box: [sizePercent] of the screen height, the width following the icon's own
     * ratio, on the right edge at 45% down until the driver moves it.
     */
    fun initialBox(
        screenWidthPx: Int,
        screenHeightPx: Int,
        sizePercent: Int,
        iconRatio: Float,
        density: Float,
    ): Box {
        val height = (screenHeightPx * sizePercent / 100).coerceAtLeast((MIN_HEIGHT_DP * density).roundToInt())
        val width = (height * iconRatio).roundToInt().coerceAtLeast(1)
        val margin = (MARGIN_DP * density).roundToInt()
        return clamp(
            Box(width, height, (screenWidthPx - width - margin).coerceAtLeast(0), (screenHeightPx * DEFAULT_Y_SHARE).roundToInt()),
            screenWidthPx,
            screenHeightPx,
        )
    }

    /** Keeps a remembered or hand-edited place on the screen. */
    fun clamp(box: Box, screenWidthPx: Int, screenHeightPx: Int): Box = box.copy(
        x = box.x.coerceIn(0, (screenWidthPx - box.width).coerceAtLeast(0)),
        y = box.y.coerceIn(0, (screenHeightPx - box.height).coerceAtLeast(0)),
    )

    /** The hold prompt centred over the bubble, above it when it fits and below it when it does not. */
    fun labelBox(bubble: Box, labelWidthPx: Int, labelHeightPx: Int, gapPx: Int, screenWidthPx: Int): Box = Box(
        width = labelWidthPx,
        height = labelHeightPx,
        x = (bubble.x + (bubble.width - labelWidthPx) / 2).coerceIn(0, (screenWidthPx - labelWidthPx).coerceAtLeast(0)),
        y = (bubble.y - labelHeightPx - gapPx).takeIf { it >= 0 } ?: bubble.bottom + gapPx,
    )

    /** Whether a touch that moved by ([dx], [dy]) is a drag, not a hold or a tap. */
    fun isDrag(dx: Int, dy: Int, slopPx: Int): Boolean = abs(dx) > slopPx || abs(dy) > slopPx
}
