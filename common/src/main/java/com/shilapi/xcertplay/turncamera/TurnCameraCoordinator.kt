package com.shilapi.xcertplay.turncamera

/** Serial owner of the turn -> camera -> cluster chain. [post] MUST enqueue on the
 * presentation/UI owner, even for callbacks that arrive synchronously during open.
 * Polling and vendor calls run elsewhere; a stuck worker never blocks [tick] or hiding. */
internal class TurnCameraCoordinator(
    private val backend: SideCameraBackend,
    private val presenter: TurnCameraPresenter,
    private val post: (() -> Unit) -> Unit,
    generation: Long,
    clock: () -> Long,
    private val mayShow: (TurnCameraSide) -> Boolean = { true },
) : AutoCloseable {
    private var closed = false
    private val policy: TurnCameraPolicy = TurnCameraPolicy(object : TurnCameraPolicy.Output {
        override fun openRequest(side: TurnCameraSide, lease: CameraLease) {
            if (if (side == TurnCameraSide.LEFT) backend.leftConfigured else backend.rightConfigured) {
                try { backend.open(lease, side, callback) }
                catch (_: Exception) { post { if (!closed) policy.onOpenFailed(lease, "backend exception") } }
            } else post { if (!closed) policy.onOpenFailed(lease, "side is not configured") }
        }
        override fun cancelRequest(lease: CameraLease, reason: TurnCameraPolicy.Reason) = backend.close(lease)
        override fun show(side: TurnCameraSide, lease: CameraLease) {
            // Recheck the producer's latest atomic state at the last pixel boundary.
            // A frame queued before a worker reports takeover/side change is not consent.
            if (runCatching { mayShow(side) }.getOrDefault(false)) presenter.show(side, lease)
            else {
                presenter.hide(TurnCameraPolicy.Reason.NATIVE_STATE_UNKNOWN)
                post { if (!closed) policy.onNativeCamera(NativeCameraState.UNKNOWN) }
            }
        }
        override fun hide(reason: TurnCameraPolicy.Reason) = presenter.hide(reason)
    }, generation = generation, clock = clock)

    private val callback = object : SideCameraBackend.Callback {
        override fun onFirstFrame(lease: CameraLease, atMillis: Long) = post {
            if (!closed) policy.onFirstFrame(lease, atMillis)
        }
        override fun onFrame(lease: CameraLease, atMillis: Long) = post {
            if (!closed) policy.onFrame(lease, atMillis)
        }
        override fun onOpenFailed(lease: CameraLease, error: String) = post {
            if (!closed) policy.onOpenFailed(lease, error)
        }
        override fun onClosed(lease: CameraLease) = post { if (!closed) policy.onClosed(lease) }
    }

    fun start() { check(!closed); policy.start() }
    fun reading(sample: TurnSignalSample, native: NativeCameraState) {
        if (closed) return
        policy.onNativeCamera(native)
        policy.onSample(sample)
        policy.tick()
    }
    fun tick() { if (!closed) policy.tick() }
    fun blocked() { if (!closed) policy.onNativeCamera(NativeCameraState.UNKNOWN) }
    override fun close() {
        if (closed) return
        closed = true
        try { policy.stop() } finally {
            try { presenter.hide(TurnCameraPolicy.Reason.STOPPED) } finally { backend.close() }
        }
    }
}
