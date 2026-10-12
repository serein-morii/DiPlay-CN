package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.media.CarPlayNowPlaying

/** Main-thread snapshots reuse the bounded media artwork cache; they never own/recycle its bitmap. */
internal object ClusterMusicState {
    data class Snapshot(val info: CarPlayNowPlaying = CarPlayNowPlaying(), val artwork: Bitmap? = null,
        val changedAt: Long = 0L, val revision: Long = 0L)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()
    private var state = Snapshot()

    fun publish(info: CarPlayNowPlaying, artwork: Bitmap?) {
        main.post {
            val changed = trackChanged(state.info, info)
            state = Snapshot(info, artwork, if (changed) SystemClock.elapsedRealtime() else state.changedAt, state.revision + if (changed) 1 else 0)
            listeners.toList().forEach { it(state) }
        }
    }

    fun observe(listener: (Snapshot) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        listeners.add(listener)
        listener(state)
        return { listeners.remove(listener) }
    }

    fun trackChanged(old: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        old.title != next.title || old.artist != next.artist || old.album != next.album ||
            old.sourceApp != next.sourceApp ||
            (old.durationMillis != null && next.durationMillis != null && old.durationMillis != next.durationMillis)
}
