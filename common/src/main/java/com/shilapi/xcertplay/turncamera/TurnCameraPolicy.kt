package com.shilapi.xcertplay.turncamera

/**
 * Pure state machine that decides when a side-camera card may be shown (plan §4). It has
 * no Android dependency: all time arrives from one monotonic [clock], every effect leaves
 * through [Output], so the whole acceptance matrix is testable with fakes and a fake clock.
 *
 * Time-domain contract: [clock] and every sample/frame timestamp are milliseconds from one
 * monotonic source (SystemClock.elapsedRealtime domain). Callbacks report when a frame or
 * sample was captured; receipt time is always read from [clock].
 *
 * Invariants the implementation owes the caller:
 *  - the card is shown only after a first frame that is provably fresh and belongs to the
 *    request that is currently active, while BOTH the turn-signal sample AND a fresh,
 *    confirmed IDLE native-camera report are valid — checked at the show entry itself,
 *    never deferred to the next watchdog tick;
 *  - turn-signal samples from the future, out of order, or stale on arrival never refresh
 *    freshness; OFF/HAZARD/UNKNOWN samples act immediately even with a broken timestamp
 *    (hiding is always the safe direction);
 *  - a new side first invalidates internal state, hides the old card, and only then
 *    releases the old request; backend callbacks firing synchronously during release, or
 *    a release that throws, can never leave the old picture visible;
 *  - lifecycle invalidation happens BEFORE any external output: once [stop] is entered no
 *    input — not even one reentering synchronously from [Output.cancelRequest] — can open
 *    a new request; input methods reentered while an output call is in flight are dropped;
 *  - a closed/failed request invalidates immediately, hides, and lets the normal
 *    cooldown/budget path recover — a stale visible flag can never block that;
 *  - callbacks carrying an old [CameraLease] are ignored;
 *  - the OEM reverse/360 pipeline gates everything: only a confirmed, fresh IDLE report
 *    allows requests; UNKNOWN, ACTIVE and stale reports fail closed, including a report
 *    of UNKNOWN hiding a visible card at once;
 *  - expiry checks run from [tick], so a blocked streaming thread alone cannot stall hiding.
 *
 * Output contract: implementations must be quick and non-blocking — heavy release work
 * belongs on the coordinator's own queue, not inside the callback. Throwing is tolerated
 * (the policy stays consistent) but not useful.
 */
class TurnCameraPolicy(
    private val output: Output,
    /** Distinguishes leases of different policy/surface lifetimes; pass a fresh value per host. */
    val generation: Long = 0L,
    private val thresholds: Thresholds = Thresholds(),
    /** Monotonic millis (SystemClock.elapsedRealtime domain). The single source of time. */
    private val clock: () -> Long,
) {
    /** Initial engineering guards (plan §4); they need on-car measurement before being claims. */
    data class Thresholds(
        val sampleStaleMillis: Long = 1_000L,
        val frameStaleMillis: Long = 500L,
        val firstFrameTimeoutMillis: Long = 2_000L,
        val retryCooldownMillis: Long = 1_000L,
        /** Extra retries after the first failed open; the first attempt is not counted. */
        val maxOpenRetries: Int = 2,
        /** A native-camera status older than this is treated as UNKNOWN (fail closed). */
        val nativeStaleMillis: Long = 1_000L,
    ) {
        init {
            require(sampleStaleMillis > 0 && frameStaleMillis > 0 && firstFrameTimeoutMillis > 0) {
                "staleness and timeout thresholds must be positive"
            }
            require(retryCooldownMillis >= 0 && nativeStaleMillis > 0 && maxOpenRetries >= 0) {
                "cooldown/native thresholds must be non-negative and retries non-negative"
            }
        }
    }

    enum class Reason {
        SIDE_CHANGED, SIGNAL_OFF, SIGNAL_HAZARD, SIGNAL_UNKNOWN, UNSUITABLE_SOURCE,
        SAMPLE_STALE, FRAME_STALE, FIRST_FRAME_TIMEOUT, OPEN_FAILED, REQUEST_CLOSED,
        NATIVE_CAMERA_ACTIVE, NATIVE_STATE_UNKNOWN, STOPPED,
    }

    interface Output {
        /** Ask the backend to open [side]; only this lease's first frame may show the card. */
        fun openRequest(side: TurnCameraSide, lease: CameraLease)

        fun cancelRequest(lease: CameraLease, reason: Reason)

        fun show(side: TurnCameraSide, lease: CameraLease)

        fun hide(reason: Reason)
    }

    private class Active(
        val lease: CameraLease,
        val side: TurnCameraSide,
        val openedAt: Long,
    ) {
        var firstFrameAt: Long? = null
        var lastFrameAt: Long? = null
    }

    private var started = false
    private var requestSeq = 0L
    private var active: Active? = null
    private var visible = false
    private var activatedSide: TurnCameraSide? = null
    private var pendingSide: TurnCameraSide? = null
    private var lastSampleAt = 0L
    private var openAttempts = 0
    private var lastFailureAt = 0L
    private var nativeState = NativeCameraState.UNKNOWN
    private var nativeStateAt = 0L

    /** True while an output callback is in flight; reentered inputs follow [SafeEvent] rules (Rework T1). */
    /** Hard cap for [pendingSafeEvents]; coalescing keeps the real size far below this. */
    private val MAX_PENDING_SAFE_EVENTS = 8

    private var dispatching = false
    private var draining = false
    private val pendingSafeEvents = ArrayDeque<SafeEvent>()

    /**
     * Safety-direction inputs reentered during an output callback are recorded and applied
     * at the end of the OUTERMOST input method (Rework U1) — after that method has finished
     * its own state writes, so a release delivered inside a side-change cancel overrides the
     * activation the outer method just wrote, instead of being overwritten by it. Losing or
     * postponing past the transaction a safety event is never acceptable: an ACTIVE/UNKNOWN
     * takeover, an OFF/HAZARD release, a current-lease failure or a stop arriving inside
     * show/cancel must still take effect before any further input. Opening-direction inputs
     * (tick, activation samples, IDLE reports, frames) reentered during an output call are
     * dropped — the next poll cycle re-delivers those naturally.
     *
     * The queue is explicitly bounded (Rework U1): takeover and release states coalesce to
     * their latest value, lease events deduplicate per lease, a Stop absorbs everything,
     * and a hard cap drops the oldest entries should a pathological callback loop exceed it.
     */
    private sealed interface SafeEvent {
        data class NativeTakenOver(val state: NativeCameraState) : SafeEvent // ACTIVE or UNKNOWN
        data class SignalReleased(val state: TurnSignalState) : SafeEvent // OFF/HAZARD/UNKNOWN
        data class LeaseFailed(val lease: CameraLease) : SafeEvent
        data class LeaseClosed(val lease: CameraLease) : SafeEvent
        object Stop : SafeEvent
    }

    private fun enqueueSafe(event: SafeEvent) {
        when (event) {
            is SafeEvent.Stop -> {
                pendingSafeEvents.clear()
                pendingSafeEvents.addLast(event)
            }
            is SafeEvent.NativeTakenOver -> {
                pendingSafeEvents.removeAll { it is SafeEvent.NativeTakenOver }
                pendingSafeEvents.addLast(event)
            }
            is SafeEvent.SignalReleased -> {
                pendingSafeEvents.removeAll { it is SafeEvent.SignalReleased }
                pendingSafeEvents.addLast(event)
            }
            is SafeEvent.LeaseFailed -> {
                if (pendingSafeEvents.none { it is SafeEvent.LeaseFailed && it.lease == event.lease }) {
                    pendingSafeEvents.addLast(event)
                }
            }
            is SafeEvent.LeaseClosed -> {
                if (pendingSafeEvents.none { it is SafeEvent.LeaseClosed && it.lease == event.lease }) {
                    pendingSafeEvents.addLast(event)
                }
            }
        }
        while (pendingSafeEvents.size > MAX_PENDING_SAFE_EVENTS) {
            pendingSafeEvents.removeFirst()
        }
    }

    private fun drainSafeEvents() {
        if (dispatching || draining) return
        draining = true
        try {
            while (pendingSafeEvents.isNotEmpty()) {
                when (val event = pendingSafeEvents.removeFirst()) {
                    is SafeEvent.NativeTakenOver -> applyNativeCamera(event.state)
                    is SafeEvent.SignalReleased -> applySignalRelease(event.state)
                    is SafeEvent.LeaseFailed -> applyOpenFailed(event.lease, "reentered during output")
                    is SafeEvent.LeaseClosed -> applyClosed(event.lease)
                    is SafeEvent.Stop -> applyStop()
                }
            }
        } finally {
            draining = false
        }
    }

    /** One input transaction: run [body], then drain queued safety events at the boundary. */
    private inline fun transaction(body: () -> Unit) {
        try {
            body()
        } finally {
            drainSafeEvents()
        }
    }

    fun start() {
        started = true
    }

    /**
     * Stops everything. If an output callback is in flight, the stop is queued and runs
     * as the very next thing after that callback returns — before any other input.
     * Lifecycle state is invalidated BEFORE any external call, so a callback reentering
     * synchronously from [Output.cancelRequest] finds the policy stopped.
     */
    fun stop() {
        if (dispatching || draining) {
            enqueueSafe(SafeEvent.Stop)
            return
        }
        transaction { applyStop() }
    }

    private fun applyStop() {
        started = false
        activatedSide = null
        pendingSide = null
        nativeState = NativeCameraState.UNKNOWN
        hideAndRelease(Reason.STOPPED)
    }

    /**
     * A turn-signal reading. Samples with a timestamp in the future, older than the last
     * accepted one, or already stale on arrival are rejected for freshness bookkeeping —
     * they must not fabricate a fresh activation. OFF/HAZARD/UNKNOWN still act immediately
     * whatever their timestamp says, because hiding is the safe direction. Reentered
     * during an output call, only the safe release direction is queued (Rework T1).
     */
    fun onSample(sample: TurnSignalSample) {
        if (!started) return
        if (dispatching || draining) {
            if (sample.state != TurnSignalState.LEFT && sample.state != TurnSignalState.RIGHT) {
                enqueueSafe(SafeEvent.SignalReleased(sample.state))
            }
            return
        }
        transaction { applySample(sample) }
    }

    private fun applySample(sample: TurnSignalSample) {
        val receivedAt = clock()
        val plausible = sample.atMillis in lastSampleAt..receivedAt
        if (plausible) lastSampleAt = sample.atMillis
        val side = when (sample.state) {
            TurnSignalState.LEFT -> TurnCameraSide.LEFT
            TurnSignalState.RIGHT -> TurnCameraSide.RIGHT
            TurnSignalState.OFF, TurnSignalState.HAZARD, TurnSignalState.UNKNOWN -> null
        }
        if (side == null) {
            applySignalRelease(sample.state)
            return
        }
        if (sample.semantics != TurnSignalSemantics.LATCHED_LEVER) {
            // A bulb-only source cannot separate lever latch, short flick and hazards (plan §3).
            hideAndRelease(Reason.UNSUITABLE_SOURCE)
            activatedSide = null; pendingSide = null
            return
        }
        if (!plausible) {
            // A broken-timestamp activation cannot refresh freshness and is simply ignored;
            // the watchdog expires the last real sample (SAMPLE_STALE) on its own schedule.
            return
        }
        if (activatedSide != side) {
            hideAndRelease(Reason.SIDE_CHANGED)
            activatedSide = side
            openAttempts = 0
        }
        pendingSide = side
    }

    private fun applySignalRelease(state: TurnSignalState) {
        hideAndRelease(
            when (state) {
                TurnSignalState.OFF -> Reason.SIGNAL_OFF
                TurnSignalState.HAZARD -> Reason.SIGNAL_HAZARD
                else -> Reason.SIGNAL_UNKNOWN
            }
        )
        activatedSide = null; pendingSide = null
    }

    /**
     * A frame callback for [lease]. [frameAtMillis] is when the frame was captured; frames
     * from the future, older than the request, stale on arrival or out of order are
     * rejected without touching watchdogs. Showing additionally requires a fresh sample
     * AND a fresh, confirmed IDLE native-camera report, checked here — not at the next tick.
     */
    fun onFirstFrame(lease: CameraLease, frameAtMillis: Long) {
        val current = active ?: return
        if (!started || dispatching || draining || current.lease != lease) return
        if (current.firstFrameAt != null) return // duplicate first frame: no watchdog reset
        transaction {
            val receivedAt = clock()
            if (!framePlausible(frameAtMillis, receivedAt, current.openedAt)) return@transaction
            if (!showGateOpen(receivedAt)) return@transaction
            current.firstFrameAt = frameAtMillis
            current.lastFrameAt = frameAtMillis
            visible = true
            dispatch { output.show(current.side, lease) }
        }
    }

    fun onFrame(lease: CameraLease, frameAtMillis: Long) {
        val current = active
        if (!started || dispatching || draining || current?.lease != lease || current.firstFrameAt == null) return
        val last = current.lastFrameAt ?: return
        val receivedAt = clock()
        if (!framePlausible(frameAtMillis, receivedAt, current.openedAt)) return
        if (frameAtMillis < last) return // out of order
        current.lastFrameAt = frameAtMillis
    }

    /**
     * The backend reported this request dead: hide at once and recover via cooldown/budget.
     * Reentered during an output call, a CURRENT-lease failure is queued (Rework T1);
     * failures for old or unknown leases are meaningless then as now.
     */
    fun onOpenFailed(lease: CameraLease, error: String) {
        val current = active ?: return
        if (dispatching || draining) {
            if (started && current.lease == lease) enqueueSafe(SafeEvent.LeaseFailed(lease))
            return
        }
        transaction { applyOpenFailed(lease, error) }
    }

    private fun applyOpenFailed(lease: CameraLease, @Suppress("UNUSED_PARAMETER") error: String) {
        val current = active ?: return
        if (!started || current.lease != lease) return
        hideAndRelease(Reason.OPEN_FAILED)
        lastFailureAt = clock()
    }

    /** The backend closed this request (including right after a successful show). */
    fun onClosed(lease: CameraLease) {
        val current = active ?: return
        if (dispatching || draining) {
            if (started && current.lease == lease) enqueueSafe(SafeEvent.LeaseClosed(lease))
            return
        }
        transaction { applyClosed(lease) }
    }

    private fun applyClosed(lease: CameraLease) {
        val current = active ?: return
        if (!started || current.lease != lease) return
        hideAndRelease(Reason.REQUEST_CLOSED)
        lastFailureAt = clock()
    }

    /**
     * OEM reverse/360 occupancy report. UNKNOWN and ACTIVE hide immediately (fail closed
     * at the report entry, not at the next tick); only a fresh IDLE ever permits showing.
     * Reentered during an output call, a takeover is queued and applied as soon as the
     * call returns — it must never be lost (Rework T1).
     */
    fun onNativeCamera(state: NativeCameraState) {
        if (dispatching || draining) {
            if (state != NativeCameraState.IDLE) enqueueSafe(SafeEvent.NativeTakenOver(state))
            return
        }
        transaction { applyNativeCamera(state) }
    }

    private fun applyNativeCamera(state: NativeCameraState) {
        nativeState = state
        nativeStateAt = clock()
        if (!started) return
        when (state) {
            NativeCameraState.ACTIVE -> hideAndRelease(Reason.NATIVE_CAMERA_ACTIVE)
            NativeCameraState.UNKNOWN -> hideAndRelease(Reason.NATIVE_STATE_UNKNOWN)
            NativeCameraState.IDLE -> Unit
        }
    }

    /** Watchdog tick; call from a lifecycle-owned timer. Time comes from the policy clock. */
    fun tick() {
        if (!started || dispatching || draining) return
        transaction {
            tickBody()
        }
    }

    private fun tickBody() {
        val now = clock()
        if (nativeState != NativeCameraState.UNKNOWN &&
            now - nativeStateAt > thresholds.nativeStaleMillis
        ) {
            nativeState = NativeCameraState.UNKNOWN // stale report fails closed, not open
        }
        if (nativeState == NativeCameraState.UNKNOWN && (active != null || visible)) {
            hideAndRelease(Reason.NATIVE_STATE_UNKNOWN)
        }
        if (activatedSide != null && now - lastSampleAt > thresholds.sampleStaleMillis) {
            hideAndRelease(Reason.SAMPLE_STALE)
            activatedSide = null; pendingSide = null
        }
        val current = active
        if (current != null && current.firstFrameAt == null &&
            now - current.openedAt > thresholds.firstFrameTimeoutMillis
        ) {
            hideAndRelease(Reason.FIRST_FRAME_TIMEOUT)
            lastFailureAt = now
        } else if (current != null && current.firstFrameAt != null &&
            now - (current.lastFrameAt ?: current.firstFrameAt!!) > thresholds.frameStaleMillis
        ) {
            hideAndRelease(Reason.FRAME_STALE)
            lastFailureAt = now
        }
        val side = pendingSide
        if (side != null && active == null && !visible &&
            nativeState == NativeCameraState.IDLE &&
            now - nativeStateAt <= thresholds.nativeStaleMillis && // still fresh, not just IDLE
            openAttempts < 1 + thresholds.maxOpenRetries &&
            (openAttempts == 0 || now - lastFailureAt >= thresholds.retryCooldownMillis)
        ) {
            val lease = CameraLease(generation, ++requestSeq)
            active = Active(lease, side, now)
            openAttempts++
            dispatch { output.openRequest(side, lease) }
        }
    }

    /**
     * True when showing is currently allowed: a fresh activation sample AND a confirmed,
     * fresh IDLE native-camera report, evaluated at [now] (Rework S1).
     */
    private fun showGateOpen(now: Long): Boolean =
        activatedSide != null &&
            now - lastSampleAt <= thresholds.sampleStaleMillis &&
            nativeState == NativeCameraState.IDLE &&
            now - nativeStateAt <= thresholds.nativeStaleMillis

    /**
     * A frame timestamp is only usable when it was captured after this request existed,
     * is not from the future, and is not already older than the staleness threshold when
     * it arrives. Anything else is a stale buffer or a broken clock and is rejected.
     */
    private fun framePlausible(frameAt: Long, receivedAt: Long, openedAt: Long): Boolean =
        frameAt >= openedAt &&
            frameAt <= receivedAt &&
            receivedAt - frameAt <= thresholds.frameStaleMillis

    /**
     * One safe exit path for stop, side switch, watchdog expiry and backend failure:
     * invalidate the token first, hide the card, release the request last. Runs with the
     * dispatch guard raised, so callbacks reentering synchronously (a close arriving
     * inside release, a tick inside cancel) are dropped rather than acted on; a release
     * that throws cannot resurrect the hidden card.
     */
    private fun hideAndRelease(reason: Reason) {
        val request = active
        active = null
        if (visible) {
            visible = false
            dispatch { output.hide(reason) }
        }
        if (request != null) {
            dispatch { output.cancelRequest(request.lease, reason) }
        }
    }

    /**
     * Runs [block] with the reentrancy guard raised and exceptions contained. Safety
     * events reentered from inside [block] stay queued; the enclosing input method's
     * [transaction] drains them after its own writes, so they always win (Rework U1).
     */
    private inline fun dispatch(block: () -> Unit) {
        dispatching = true
        try {
            runCatching(block)
        } finally {
            dispatching = false
        }
    }
}
