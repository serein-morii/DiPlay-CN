package com.shilapi.xcertplay.turncamera

/** SurfaceTexture's time origin depends on its producer, NOT on TextureView. An
 * explicit firmware-specific timebase is mandatory; no receipt-time-as-capture-time fallback. */
internal enum class TurnCameraFrameTimebase { ELAPSED_REALTIME_NANOS, MONOTONIC_NANOS }

internal class TurnCameraFrameClock(private val timebase: TurnCameraFrameTimebase) {
    private var last = 0L
    fun frameMillis(timestampNanos: Long, elapsedMillis: Long, monotonicNanos: Long): Long? {
        if (timestampNanos <= last || timestampNanos <= 0 || elapsedMillis < 0) return null
        val at = when (timebase) {
            TurnCameraFrameTimebase.ELAPSED_REALTIME_NANOS -> timestampNanos / 1_000_000
            TurnCameraFrameTimebase.MONOTONIC_NANOS -> {
                if (timestampNanos > monotonicNanos || monotonicNanos < 0) return null
                elapsedMillis - (monotonicNanos - timestampNanos) / 1_000_000
            }
        }
        if (at < 0 || at > elapsedMillis || elapsedMillis - at > 500) return null
        last = timestampNanos
        return at
    }
}
