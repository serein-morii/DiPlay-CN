package com.shilapi.xcertplay.turncamera

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal data class TurnCameraRoute(
    val id: Int, val channel: Int, val width: Int, val height: Int,
    val calibration: TurnCameraCalibration,
    val timebase: TurnCameraFrameTimebase,
) {
    init { require(id >= 0 && channel >= 0 && width in 1..4096 && height in 1..4096) }
}

/** All methods/callbacks belong to the same UI owner. Hide leaves native resources
 * alive until the vendor worker confirms release. Disposal with confirmed=false must
 * retain them in quarantine, not destroy a surface still owned by vendor code. */
internal interface TurnCameraSurfacePort<S : Any> {
    fun prepare(lease: CameraLease, route: TurnCameraRoute, ready: (S) -> Unit,
                frame: (Long) -> Unit, lost: () -> Unit)
    fun hide(lease: CameraLease)
    fun dispose(lease: CameraLease, confirmed: Boolean)
}

/** One slot, one bounded vendor queue, no speculative opens. Busy side changes enter
 * the policy's existing cooldown; they never allocate another preview while close blocks. */
internal class QueuedSideCameraBackend<S : Any>(
    private val routes: Map<TurnCameraSide, TurnCameraRoute>,
    private val port: TurnCameraSurfacePort<S>,
    private val worker: Executor,
    private val post: (() -> Unit) -> Unit,
    private val allowed: () -> Boolean,
    private val elapsed: () -> Long,
    private val nanos: () -> Long,
    private val openVendor: (TurnCameraRoute, S, () -> Boolean) -> AutoCloseable,
) : SideCameraBackend {
    override val leftConfigured get() = routes.containsKey(TurnCameraSide.LEFT)
    override val rightConfigured get() = routes.containsKey(TurnCameraSide.RIGHT)
    private class Request(val lease: CameraLease, val callback: SideCameraBackend.Callback, val route: TurnCameraRoute) {
        val live = AtomicBoolean(true)
        var submitted = false // UI-owner only
        var ready = false
        var first = true
        var session: AutoCloseable? = null // worker-only
        var releaseUnconfirmed = false // worker-only, sticky across duplicate release tasks
        val frameClock = TurnCameraFrameClock(route.timebase)
    }
    private var active: Request? = null
    private var closed = false
    private var quarantined = false

    override fun open(lease: CameraLease, side: TurnCameraSide, callback: SideCameraBackend.Callback) {
        val route = routes[side]
        if (closed || quarantined || active != null || route == null || !allowed()) {
            callback.onOpenFailed(lease, "experimental route unavailable or still releasing")
            return
        }
        val r = Request(lease, callback, route)
        active = r
        try {
            port.prepare(lease, route, ready = { surface ->
                if (active === r && r.live.get() && !r.submitted) {
                    r.submitted = true
                    try {
                        worker.execute {
                            try {
                                if (r.live.get() && allowed()) {
                                    r.session = openVendor(route, surface) { r.live.get() && allowed() }
                                    post { if (active === r && r.live.get()) r.ready = true }
                                } else post { if (active === r && r.live.get()) fail(r, "permission revoked") }
                            } catch (_: Throwable) {
                                // The adapter may have failed after acquiring a native handle.
                                // Without a returned owner we cannot prove surface detachment.
                                r.releaseUnconfirmed = true
                                post { if (active === r && r.live.get()) fail(r, "vendor open failed") }
                            } finally {
                                // A cancellation while open blocks must close the eventual
                                // handle on THIS worker, before freeing any UI-owned surface.
                                if (!r.live.get()) release(r)
                            }
                        }
                    } catch (_: Exception) {
                        r.submitted = false
                        fail(r, "vendor queue unavailable")
                    }
                }
            }, frame = { timestamp ->
                if (active === r && r.live.get() && r.ready && allowed()) {
                    r.frameClock.frameMillis(timestamp, elapsed(), nanos())?.let { at ->
                        if (r.first) { r.first = false; callback.onFirstFrame(lease, at) }
                        else callback.onFrame(lease, at)
                    }
                }
            }, lost = {
                if (active === r && r.live.get()) fail(r, "cluster surface lost")
            })
        } catch (_: Exception) { fail(r, "cluster preview unavailable") }
    }

    private fun fail(r: Request, error: String) {
        try { r.callback.onOpenFailed(r.lease, error) } finally { close(r.lease) }
    }

    override fun close(lease: CameraLease) {
        val r = active?.takeIf { it.lease == lease } ?: return
        if (!r.live.getAndSet(false)) return
        port.hide(lease) // pixels first; no vendor calls on this thread
        if (!r.submitted) finish(r, true)
        else try { worker.execute { release(r) } }
        catch (_: Exception) { quarantined = true; finish(r, false) }
    }

    // Worker tasks are strictly serial. Both the open-finally path and queued close may
    // arrive here; removing the handle makes native close exactly-once.
    private fun release(r: Request) {
        val session = r.session
        r.session = null
        try { session?.close() } catch (_: Throwable) { r.releaseUnconfirmed = true }
        val confirmed = !r.releaseUnconfirmed
        post { finish(r, confirmed) }
    }

    private fun finish(r: Request, confirmed: Boolean) {
        if (active !== r) return
        if (!confirmed) quarantined = true
        port.dispose(r.lease, confirmed)
        active = null
    }

    override fun close() {
        if (closed) return
        closed = true
        active?.let { close(it.lease) }
    }
}
