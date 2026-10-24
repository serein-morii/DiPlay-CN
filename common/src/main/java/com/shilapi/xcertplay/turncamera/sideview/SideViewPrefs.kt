package com.shilapi.xcertplay.turncamera.sideview

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.util.Size
import kotlin.math.roundToInt

/**
 * Settings of the turn side-view feature. Every getter re-reads the store so a change lands on
 * the next loop tick without any observer plumbing.
 */
internal class SideViewPrefs(private val context: Context) {

    private val store: SharedPreferences
        get() = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val enabled: Boolean get() = store.getBoolean(ENABLED, false)

    /** Show the side view from this speed up. 0 = always while out of reverse. */
    val thresholdKmh: Int get() = store.getInt(THRESHOLD, DEFAULT_THRESHOLD)

    /** Main-screen window width as a percent of the display width. */
    val windowWidthPct: Int get() = store.getInt(WIDTH, DEFAULT_WIDTH)

    /** Portrait windows with the picture turned a quarter-turn, like a door mirror. */
    val rotate: Boolean get() = store.getBoolean(ROTATE, false)

    /** Per-side horizontal flip for cameras mounted the other way round. */
    val mirrorLeft: Boolean get() = store.getBoolean(MIRROR_LEFT, false)
    val mirrorRight: Boolean get() = store.getBoolean(MIRROR_RIGHT, false)

    /** The two AVM channels landed on the wrong sides: swap them without re-sweeping. */
    val swapSides: Boolean get() = store.getBoolean(SWAP, false)

    /** BSD radar warning border on the shown window. */
    val bsdWarn: Boolean get() = store.getBoolean(BSD, true)

    /** Keep both windows on the main screen even when a projection panel exists. */
    val bothOnMain: Boolean get() = store.getBoolean(BOTH_MAIN, false)

    /** Saved window corners in real display pixels; UNSET until the user moved a window. */
    val rightX: Int get() = store.getInt(RIGHT_X, UNSET)
    val rightY: Int get() = store.getInt(RIGHT_Y, UNSET)
    val leftX: Int get() = store.getInt(LEFT_X, UNSET)
    val leftY: Int get() = store.getInt(LEFT_Y, UNSET)

    /** The preview indexes each side binds, after the swap flag. */
    fun channels(): Pair<Int, Int> =
        if (swapSides) (VERIFIED_RIGHT to VERIFIED_LEFT) else (VERIFIED_LEFT to VERIFIED_RIGHT)

    /** A saved window corner, UNSET while the user never moved it. */
    fun savedInt(key: String): Int = store.getInt(key, UNSET)

    fun edit(): SharedPreferences.Editor = store.edit()

    companion object {
        const val FILE = "side_view"
        const val ENABLED = "enabled"
        const val THRESHOLD = "threshold_kmh"
        const val WIDTH = "width_pct"
        const val ROTATE = "rotate"
        const val MIRROR_LEFT = "mirror_left"
        const val MIRROR_RIGHT = "mirror_right"
        const val SWAP = "swap_sides"
        const val BSD = "bsd_warn"
        const val BOTH_MAIN = "both_on_main"
        const val RIGHT_X = "right_x"
        const val RIGHT_Y = "right_y"
        const val LEFT_X = "left_x"
        const val LEFT_Y = "left_y"

        const val UNSET = -1
        const val DEFAULT_THRESHOLD = 20
        const val DEFAULT_WIDTH = 36
        const val MIN_THRESHOLD = 0
        const val MAX_THRESHOLD = 60
        const val MIN_WIDTH = 20
        const val MAX_WIDTH = 60

        /** AVM preview indexes verified on the Han DM-i: 2 left side, 3 right side. */
        const val VERIFIED_LEFT = 2
        const val VERIFIED_RIGHT = 3

        /** 16:9 of the width percent; an upright window never grows past the display height. */
        fun windowSize(displayW: Int, displayH: Int, widthPct: Int, upright: Boolean): Size {
            val width = (displayW * widthPct / 100f).roundToInt()
            val height = width * 9 / 16
            val size = if (upright) Size(height, width) else Size(width, height)
            if (size.height <= displayH || displayH <= 0) return size
            return Size((size.width * displayH.toFloat() / size.height).roundToInt(), displayH)
        }

        /** Default slot: right edge, vertically centred, clear of the navigation card. */
        fun defaultRect(displayW: Int, displayH: Int, widthPct: Int, upright: Boolean): Rect {
            val size = windowSize(displayW, displayH, widthPct, upright)
            val left = (displayW - size.width).coerceAtLeast(0)
            val top = ((displayH - size.height) / 2).coerceAtLeast(0)
            return Rect(left, top, left + size.width, top + size.height)
        }

        /** Saved corner clamped to the screen, or the default slot while UNSET. */
        fun placedRect(displayW: Int, displayH: Int, widthPct: Int, upright: Boolean, x: Int, y: Int): Rect {
            if (x == UNSET || y == UNSET) return defaultRect(displayW, displayH, widthPct, upright)
            val size = windowSize(displayW, displayH, widthPct, upright)
            val left = x.coerceIn(0, (displayW - size.width).coerceAtLeast(0))
            val top = y.coerceIn(0, (displayH - size.height).coerceAtLeast(0))
            return Rect(left, top, left + size.width, top + size.height)
        }

        /** Mirror of a rect across the screen's vertical axis. */
        fun mirrored(rect: Rect, displayW: Int): Rect {
            val left = (displayW - rect.right).coerceAtLeast(0)
            return Rect(left, rect.top, left + rect.width(), rect.bottom)
        }

        /** Left window: its own corner once placed, the mirror of the right one until then. */
        fun leftRect(displayW: Int, displayH: Int, widthPct: Int, upright: Boolean, x: Int, y: Int, right: Rect): Rect =
            if (x == UNSET || y == UNSET) mirrored(right, displayW)
            else placedRect(displayW, displayH, widthPct, upright, x, y)
    }
}
