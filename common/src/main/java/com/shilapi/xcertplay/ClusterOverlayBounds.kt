package com.shilapi.xcertplay

import android.graphics.RectF

/** Per-window occupied pixels; no global state shared between cluster displays. */
internal interface ClusterOverlayBounds {
    fun occupiedBounds(): RectF?
}
