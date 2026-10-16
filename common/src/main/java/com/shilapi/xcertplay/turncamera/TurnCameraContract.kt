package com.shilapi.xcertplay.turncamera

/**
 * Contracts for the turn side-camera feature (docs/CLUSTER_TURN_CAMERA_PLAN.md §4).
 *
 * Default operation remains read-only. The experimental vendor/signal/cluster chain is
 * separately opt-in, requires an explicit firmware-bound calibration and occupancy source,
 * and is NOT a claim of real-vehicle verification. No implementation writes vehicle parameters.
 */

/** Physical side of the car. Never flipped by locale, RTL layout or driving side. */
enum class TurnCameraSide { LEFT, RIGHT }

/** Turn-signal state as confirmed facts, never a guess mapped from navigation prompts. */
enum class TurnSignalState { LEFT, RIGHT, OFF, HAZARD, UNKNOWN }

/**
 * How the source encodes its state. A source that only reports raw bulb on/off cannot
 * distinguish a latched lever, a short flick or hazards (plan §3), so the policy refuses
 * to drive automatic display from it until that distinction is proven on the car.
 */
enum class TurnSignalSemantics { LATCHED_LEVER, RAW_BULB_ONLY, UNKNOWN }

/** One read-only source reading with its monotonic timestamp (SystemClock.elapsedRealtime domain). */
data class TurnSignalSample(
    val state: TurnSignalState,
    val semantics: TurnSignalSemantics,
    val atMillis: Long,
)

/**
 * Resource ownership token (plan §4 "CameraLease"). The policy issues a fresh lease for
 * every open request; callbacks carrying an older lease are stale and must be ignored so
 * a released camera or window can never be revived by a late callback.
 */
data class CameraLease(val generation: Long, val requestId: Long)

/** Read-only turn-signal source. Phase B provides the firmware-confirmed implementation. */
interface TurnSignalSource : AutoCloseable {
    val semantics: TurnSignalSemantics

    /** Returns the freshest confirmed state; never fabricates OFF when the value is unknown. */
    fun read(): TurnSignalSample
}

/**
 * OEM reverse/360 pipeline occupancy as a confirmed fact, never a default. A source that
 * has not reported yet is [UNKNOWN]: coexistence with the OEM pipeline is then unproven
 * and the policy must fail closed (plan §3/§4), not treat silence as "not occupied".
 */
enum class NativeCameraState { UNKNOWN, IDLE, ACTIVE }

/**
 * Camera backend with explicit per-side sources. Phase B provides one backend chosen from
 * verified firmware evidence (vendor AVMCamera reflection or the Qualcomm AIS path); the
 * probe in [TurnCameraCapabilities] only records hints about which paths exist.
 */
interface SideCameraBackend : AutoCloseable {
    val leftConfigured: Boolean
    val rightConfigured: Boolean

    /** Async open; the surface stays local to the backend, nothing crosses the network. */
    fun open(lease: CameraLease, side: TurnCameraSide, callback: Callback)

    /** Invalidate callbacks immediately; native cleanup may finish later on its owner worker. */
    fun close(lease: CameraLease)

    interface Callback {
        /** First frame that belongs to [lease] arrived; earlier frames belong to older leases. */
        fun onFirstFrame(lease: CameraLease, atMillis: Long)

        fun onFrame(lease: CameraLease, atMillis: Long)

        fun onOpenFailed(lease: CameraLease, error: String)

        fun onClosed(lease: CameraLease)
    }
}

/** Cluster-side presentation of the card. Phase B implements the real overlay. */
interface TurnCameraPresenter {
    fun show(side: TurnCameraSide, lease: CameraLease)

    fun hide(reason: TurnCameraPolicy.Reason)
}
