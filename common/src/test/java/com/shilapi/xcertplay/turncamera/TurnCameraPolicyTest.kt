package com.shilapi.xcertplay.turncamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase A acceptance-matrix coverage for [TurnCameraPolicy] with fakes: no Android, no
 * camera, no cluster surface. All time comes from one fake monotonic clock, so timeouts,
 * cooldowns, frame ages and native-status freshness are deterministic. These tests
 * simulate policy behaviour only; they are NOT evidence about real firmware latency or
 * camera behaviour on the car (plan §6).
 */
class TurnCameraPolicyTest {

    private class FakeTurnSignalSource(override val semantics: TurnSignalSemantics) : TurnSignalSource {
        var next = TurnSignalSample(TurnSignalState.OFF, semantics, 0L)
        override fun read(): TurnSignalSample = next
        override fun close() {}
    }

    private class FakeSideCameraBackend : SideCameraBackend {
        override val leftConfigured = true
        override val rightConfigured = true
        var callback: SideCameraBackend.Callback? = null
        val opened = mutableListOf<Pair<TurnCameraSide, CameraLease>>()
        val closed = mutableListOf<CameraLease>()
        override fun open(lease: CameraLease, side: TurnCameraSide, callback: SideCameraBackend.Callback) {
            opened += side to lease
            this.callback = callback
        }
        override fun close(lease: CameraLease) { closed += lease }
        override fun close() {}
        fun deliverFirstFrame(lease: CameraLease, atMillis: Long) = callback?.onFirstFrame(lease, atMillis)
        fun deliverFrame(lease: CameraLease, atMillis: Long) = callback?.onFrame(lease, atMillis)
        fun deliverOpenFailed(lease: CameraLease, error: String) = callback?.onOpenFailed(lease, error)
        fun deliverClosed(lease: CameraLease) = callback?.onClosed(lease)
    }

    private class FakeTurnCameraPresenter : TurnCameraPresenter {
        val shown = mutableListOf<Pair<TurnCameraSide, CameraLease>>()
        val hidden = mutableListOf<TurnCameraPolicy.Reason>()
        override fun show(side: TurnCameraSide, lease: CameraLease) { shown += side to lease }
        override fun hide(reason: TurnCameraPolicy.Reason) { hidden += reason }
    }

    /** Minimal stand-in for the phase B coordinator: adapts policy output to backend+presenter. */
    private class Harness(val thresholds: TurnCameraPolicy.Thresholds = TurnCameraPolicy.Thresholds()) {
        var now = 0L
        val events = mutableListOf<String>()
        val backend = FakeSideCameraBackend()
        val presenter = FakeTurnCameraPresenter()

        /** A release that throws, or one that synchronously reenters the policy, to test R2/S3. */
        var releaseBehavior: (CameraLease) -> Unit = { lease -> backend.close(lease) }
        var showBehavior: (TurnCameraSide, CameraLease) -> Unit = { side, lease -> presenter.show(side, lease) }
        var hideBehavior: (TurnCameraPolicy.Reason) -> Unit = { reason -> presenter.hide(reason) }

        private val backendCallback: SideCameraBackend.Callback = object : SideCameraBackend.Callback {
            override fun onFirstFrame(lease: CameraLease, atMillis: Long) = policy.onFirstFrame(lease, atMillis)
            override fun onFrame(lease: CameraLease, atMillis: Long) = policy.onFrame(lease, atMillis)
            override fun onOpenFailed(lease: CameraLease, error: String) = policy.onOpenFailed(lease, error)
            override fun onClosed(lease: CameraLease) = policy.onClosed(lease)
        }

        val policy: TurnCameraPolicy = TurnCameraPolicy(
            object : TurnCameraPolicy.Output {
                override fun openRequest(side: TurnCameraSide, lease: CameraLease) {
                    events += "open:${side.name}:${lease.requestId}"
                    backend.open(lease, side, backendCallback)
                }

                override fun cancelRequest(lease: CameraLease, reason: TurnCameraPolicy.Reason) {
                    events += "cancel:${lease.requestId}:${reason.name}"
                    releaseBehavior(lease)
                }

                override fun show(side: TurnCameraSide, lease: CameraLease) {
                    events += "show:${side.name}:${lease.requestId}"
                    showBehavior(side, lease)
                }

                override fun hide(reason: TurnCameraPolicy.Reason) {
                    events += "hide:${reason.name}"
                    hideBehavior(reason)
                }
            },
            generation = 7L,
            thresholds = thresholds,
            clock = { now },
        )

        fun sample(
            state: TurnSignalState,
            semantics: TurnSignalSemantics = TurnSignalSemantics.LATCHED_LEVER,
            atMillis: Long = now,
        ) = policy.onSample(TurnSignalSample(state, semantics, atMillis))

        fun native(state: NativeCameraState) = policy.onNativeCamera(state)

        /** One poll cycle: fresh sample, fresh native report, then watchdog tick. */
        fun step(state: TurnSignalState, semantics: TurnSignalSemantics = TurnSignalSemantics.LATCHED_LEVER) {
            sample(state, semantics)
            native(NativeCameraState.IDLE)
            policy.tick()
        }

        fun visible(side: TurnCameraSide) {
            step(if (side == TurnCameraSide.LEFT) TurnSignalState.LEFT else TurnSignalState.RIGHT)
            backend.opened.lastOrNull()?.let { (openedSide, lease) ->
                check(openedSide == side) { "opened $openedSide for $side" }
                now += 1
                backend.deliverFirstFrame(lease, now)
            }
        }
    }

    @Test fun notStartedIgnoresSamplesAndTicks() {
        val h = Harness()
        h.sample(TurnSignalState.LEFT)
        h.policy.tick()
        assertEquals(emptyList<String>(), h.events)
    }

    @Test fun bulbOnlySourceNeverDrivesDisplay() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT, TurnSignalSemantics.RAW_BULB_ONLY)
        h.now = 5_000
        h.step(TurnSignalState.RIGHT, TurnSignalSemantics.RAW_BULB_ONLY)
        assertEquals(emptyList<String>(), h.events)
    }

    @Test fun leftActivationShowsOnlyAfterItsOwnFirstFrame() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT)
        assertEquals(listOf("open:LEFT:1"), h.events)
        h.now = 120
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1"), h.events)
    }

    @Test fun rightActivationUsesTheRightSide() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.RIGHT)
        assertEquals(listOf("open:RIGHT:1"), h.events)
    }

    @Test fun firstFrameForUnknownLeaseIsIgnored() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT)
        val stranger = CameraLease(generation = 7L, requestId = 99L)
        h.backend.deliverFirstFrame(stranger, h.now)
        h.backend.deliverFrame(stranger, h.now)
        assertEquals(listOf("open:LEFT:1"), h.events)
    }

    @Test fun offHazardAndUnknownEachHideAndRelease() {
        for (state in listOf(TurnSignalState.OFF, TurnSignalState.HAZARD, TurnSignalState.UNKNOWN)) {
            val h = Harness()
            h.policy.start()
            h.visible(TurnCameraSide.LEFT)
            h.now += 10
            h.sample(state)
            assertEquals(1, h.backend.closed.size)
            assertEquals(1, h.presenter.hidden.size)
            assertEquals(1, h.presenter.shown.size)
        }
    }

    @Test fun hazardNeverPicksASide() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.HAZARD)
        assertEquals(emptyList<String>(), h.events)
    }

    @Test fun leftToRightSwitchHidesFirstAndNeedsNewRightFrame() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val leftLease = h.backend.opened.last().second
        h.now += 10
        h.step(TurnSignalState.RIGHT)
        // Invalidation first, hide second, release third — then the new request.
        assertEquals(
            listOf(
                "open:LEFT:1", "show:LEFT:1",
                "hide:SIDE_CHANGED", "cancel:1:SIDE_CHANGED",
                "open:RIGHT:2",
            ),
            h.events,
        )
        val rightLease = h.backend.opened.last().second
        // A late left frame and a stale left first-frame must not display anything new.
        h.backend.deliverFrame(leftLease, h.now)
        h.backend.deliverFirstFrame(leftLease, h.now)
        assertEquals(1, h.presenter.shown.size)
        h.now += 1
        h.backend.deliverFirstFrame(rightLease, h.now)
        assertEquals(2, h.presenter.shown.size)
        assertEquals(TurnCameraSide.RIGHT to rightLease, h.presenter.shown.last())
    }

    @Test fun staleSampleHidesAndRequiresANewActivation() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.now += 1_001
        h.native(NativeCameraState.IDLE)
        h.policy.tick()
        assertEquals(listOf("hide:SAMPLE_STALE", "cancel:1:SAMPLE_STALE"), h.events.drop(2))
        // No retry storm while nothing fresh arrives.
        h.now += 2_000
        h.policy.tick()
        assertEquals(1, h.backend.opened.size)
    }

    @Test fun firstFrameTimeoutReleasesThenRetriesWithCooldownAndCap() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT) // open #1 at 0
        h.now = 2_001
        h.step(TurnSignalState.LEFT) // timeout → release; never shown, so no hide event
        assertEquals(listOf("open:LEFT:1", "cancel:1:FIRST_FRAME_TIMEOUT"), h.events)
        h.now = 2_500 // inside the 1000ms cooldown
        h.step(TurnSignalState.LEFT)
        assertEquals(1, h.backend.opened.size)
        h.now = 3_002 // cooldown elapsed → retry #2
        h.step(TurnSignalState.LEFT)
        assertEquals(2, h.backend.opened.size)
        h.now = 5_100 // retry #2 times out
        h.step(TurnSignalState.LEFT)
        h.now = 6_200 // retry #3 (last)
        h.step(TurnSignalState.LEFT)
        assertEquals(3, h.backend.opened.size)
        h.now = 8_500 // retry #3 times out; attempts exhausted
        h.step(TurnSignalState.LEFT)
        h.now = 9_900
        h.step(TurnSignalState.LEFT)
        assertEquals(3, h.backend.opened.size)
        // A new activation cycle earns a fresh attempt budget.
        h.step(TurnSignalState.OFF)
        h.now += 10
        h.step(TurnSignalState.LEFT)
        assertEquals(4, h.backend.opened.size)
    }

    @Test fun openFailureRetriesAfterCooldownOnly() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT)
        val lease = h.backend.opened.last().second
        h.now = 50
        h.backend.deliverOpenFailed(lease, "simulated failure")
        h.now = 600
        h.step(TurnSignalState.LEFT)
        assertEquals(1, h.backend.opened.size)
        h.now = 1_100
        h.step(TurnSignalState.LEFT)
        assertEquals(2, h.backend.opened.size)
    }

    @Test fun frameStalenessHidesInsteadOfFreezing() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val lease = h.backend.opened.last().second
        h.now += 200
        h.step(TurnSignalState.LEFT)
        h.backend.deliverFrame(lease, h.now)
        h.now += 501
        h.step(TurnSignalState.LEFT) // no frame for 501ms → hide, don't freeze the old picture
        assertEquals(TurnCameraPolicy.Reason.FRAME_STALE, h.presenter.hidden.last())
        assertEquals(1, h.backend.closed.size)
        assertEquals(1, h.presenter.shown.size)
    }

    @Test fun nativeActiveTakesPriorityAndUnknownPausesRetries() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.native(NativeCameraState.ACTIVE)
        assertEquals(TurnCameraPolicy.Reason.NATIVE_CAMERA_ACTIVE, h.presenter.hidden.last())
        // Lever still on, but the OEM pipeline owns the cameras (or its state went stale).
        h.now += 1_500
        h.sample(TurnSignalState.LEFT)
        h.policy.tick()
        assertEquals(1, h.backend.opened.size)
        h.native(NativeCameraState.IDLE) // recovered and fresh again
        h.now += 600
        h.sample(TurnSignalState.LEFT)
        h.policy.tick()
        assertEquals(2, h.backend.opened.size)
    }

    @Test fun stopHidesReleasesAndIgnoresLateCallbacks() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val lease = h.backend.opened.last().second
        h.policy.stop()
        // Hide must precede release (plan §4: make the surface invisible first).
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:STOPPED", "cancel:1:STOPPED"), h.events)
        assertEquals(listOf(lease), h.backend.closed)
        h.now += 10
        h.backend.deliverFirstFrame(lease, h.now)
        h.sample(TurnSignalState.RIGHT)
        h.policy.tick()
        assertEquals(1, h.backend.opened.size)
        assertEquals(1, h.presenter.shown.size)
    }

    @Test fun hundredActivationCyclesAccumulateNoRequests() {
        val h = Harness()
        h.policy.start()
        val ids = mutableSetOf<Long>()
        repeat(100) { cycle ->
            h.now = cycle * 10_000L
            h.step(TurnSignalState.LEFT)
            h.backend.opened.lastOrNull()?.second?.let { lease ->
                ids += lease.requestId
                h.now += 5
                h.backend.deliverFirstFrame(lease, h.now)
            }
            h.policy.stop()
            h.policy.start()
        }
        assertEquals("opens must equal closes", h.backend.opened.size, h.backend.closed.size)
        assertEquals("every show must be hidden", h.presenter.shown.size, h.presenter.hidden.size)
        assertEquals("leases must not be reused", h.backend.opened.size, ids.size)
        assertTrue(h.backend.opened.size >= 100)
    }

    // ---- R1: closed/failed requests must not leave a stale picture or block recovery ----

    @Test fun closedAfterVisibleHidesImmediatelyAndAllowsRecovery() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val lease = h.backend.opened.last().second
        h.now += 10
        h.backend.deliverClosed(lease)
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:REQUEST_CLOSED", "cancel:1:REQUEST_CLOSED"), h.events)
        // Fresh samples may recover after the cooldown; visible no longer locks the gate.
        h.now += 400
        h.step(TurnSignalState.LEFT)
        assertEquals(1, h.backend.opened.size)
        h.now += 1_100
        h.step(TurnSignalState.LEFT)
        assertEquals(2, h.backend.opened.size)
    }

    @Test fun openFailedAfterVisibleHidesImmediately() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val lease = h.backend.opened.last().second
        h.now += 10
        h.backend.deliverOpenFailed(lease, "device busy")
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:OPEN_FAILED", "cancel:1:OPEN_FAILED"), h.events)
    }

    @Test fun oldLeaseClosedOrFailedDoesNotAffectTheNewRequest() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val oldLease = h.backend.opened.last().second
        h.now += 10
        h.step(TurnSignalState.RIGHT)
        val rightLease = h.backend.opened.last().second
        h.now += 1
        h.backend.deliverFirstFrame(rightLease, h.now)
        assertEquals(TurnCameraSide.RIGHT, h.presenter.shown.last().first)
        h.backend.deliverClosed(oldLease)
        h.backend.deliverOpenFailed(oldLease, "late failure")
        assertEquals(2, h.presenter.shown.size) // still showing the right side
        assertEquals(1, h.presenter.hidden.size) // only the side change hid anything
    }

    @Test fun duplicateClosedCallbacksAreIgnored() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val lease = h.backend.opened.last().second
        h.now += 10
        h.backend.deliverClosed(lease)
        h.backend.deliverClosed(lease)
        h.backend.deliverOpenFailed(lease, "duplicate")
        assertEquals(1, h.presenter.hidden.size)
        assertEquals(1, h.backend.closed.size)
    }

    // ---- R2: hide-before-release ordering survives reentrancy and throwing releases ----

    @Test fun synchronousCloseDuringReleaseIsIgnoredNotDoubled() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val lease = h.backend.opened.last().second
        // The release path synchronously reports the close back into the policy.
        h.releaseBehavior = { l -> h.backend.close(l); h.policy.onClosed(l) }
        h.now += 10
        h.sample(TurnSignalState.OFF)
        assertEquals(
            listOf("open:LEFT:1", "show:LEFT:1", "hide:SIGNAL_OFF", "cancel:1:SIGNAL_OFF"),
            h.events,
        )
    }

    @Test fun throwingReleaseKeepsPolicyConsistent() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.releaseBehavior = { throw IllegalStateException("release blocked") }
        h.now += 10
        h.sample(TurnSignalState.OFF)
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:SIGNAL_OFF", "cancel:1:SIGNAL_OFF"), h.events)
        // The policy keeps working after a failed release (once past the cooldown).
        h.releaseBehavior = { l -> h.backend.close(l) }
        h.now += 1_100
        h.step(TurnSignalState.LEFT)
        assertEquals(2, h.backend.opened.size)
    }

    // ---- R3: frame timestamps are validated; future/ancient/buffered frames never show ----

    @Test fun futureFirstFrameIsRejected() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT)
        h.now = 100
        h.backend.deliverFirstFrame(h.backend.opened.last().second, 1_000_000)
        assertEquals(listOf("open:LEFT:1"), h.events)
    }

    @Test fun frameOlderThanTheRequestIsRejected() {
        val h = Harness()
        h.policy.start()
        h.now = 1_500
        h.step(TurnSignalState.LEFT) // request opened at 1500
        h.backend.deliverFirstFrame(h.backend.opened.last().second, 0) // buffered frame
        assertEquals(listOf("open:LEFT:1"), h.events)
    }

    @Test fun staleOnArrivalFrameIsRejected() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT)
        h.now = 600
        h.backend.deliverFirstFrame(h.backend.opened.last().second, 0) // 600ms old in flight
        assertEquals(listOf("open:LEFT:1"), h.events)
    }

    @Test fun outOfOrderAndFutureSubsequentFramesDoNotExtendTheWatchdog() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT) // first frame at now=1
        val lease = h.backend.opened.last().second
        h.now += 200
        h.step(TurnSignalState.LEFT)
        h.backend.deliverFrame(lease, h.now) // accepted: the frame age clock starts here
        h.backend.deliverFrame(lease, 50) // older than the accepted frame: rejected
        h.backend.deliverFrame(lease, 10_000_000) // future: rejected
        h.now += 501
        h.step(TurnSignalState.LEFT)
        assertEquals(TurnCameraPolicy.Reason.FRAME_STALE, h.presenter.hidden.last())
    }

    @Test fun delayedButPlausibleFirstFrameStillShows() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT)
        h.now = 200
        h.backend.deliverFirstFrame(h.backend.opened.last().second, 150) // 50ms in flight: fine
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1"), h.events)
    }

    @Test fun duplicateFirstFrameDoesNotResetTheWatchdog() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT) // first frame at now=1
        val lease = h.backend.opened.last().second
        h.now += 300
        h.step(TurnSignalState.LEFT)
        h.backend.deliverFirstFrame(lease, h.now) // duplicate: ignored, no watchdog reset
        h.now += 201 // 500ms after the real first frame
        h.step(TurnSignalState.LEFT)
        assertEquals(TurnCameraPolicy.Reason.FRAME_STALE, h.presenter.hidden.last())
    }

    @Test fun showRequiresAFreshTurnSignalSample() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT) // sample at 0, request opened at 0
        h.now = 1_500 // sample is now stale, request not timed out yet (1.5s < 2s)
        val lease = h.backend.opened.last().second
        h.backend.deliverFirstFrame(lease, 1_450) // plausible frame, stale sample
        assertEquals("no show while the activation itself is stale", listOf("open:LEFT:1"), h.events)
        h.native(NativeCameraState.IDLE)
        h.policy.tick()
        // Never visible, so the stale sample releases the request without a hide event.
        assertEquals(listOf("open:LEFT:1", "cancel:1:SAMPLE_STALE"), h.events)
        assertEquals(0, h.presenter.hidden.size)
    }

    // ---- R4: native-camera occupancy is tri-state and freshness-gated (fail closed) ----

    @Test fun unknownNativeStateBlocksEveryOpenByDefault() {
        val h = Harness()
        h.policy.start()
        repeat(5) {
            h.now += 500
            h.sample(TurnSignalState.LEFT)
            h.policy.tick()
        }
        assertEquals("no open before a confirmed IDLE report", emptyList<String>(), h.events)
    }

    @Test fun staleIdleReportFailsClosed() {
        val h = Harness()
        h.policy.start()
        h.policy.onNativeCamera(NativeCameraState.IDLE) // at t=0
        h.now = 1_500 // IDLE report older than nativeStaleMillis
        h.sample(TurnSignalState.LEFT)
        h.policy.tick()
        assertEquals(emptyList<String>(), h.events)
    }

    @Test fun nativeGoingUnknownHidesAVisibleCard() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.now += 1_501 // native report goes stale → UNKNOWN
        h.sample(TurnSignalState.LEFT)
        h.policy.tick()
        assertEquals(TurnCameraPolicy.Reason.NATIVE_STATE_UNKNOWN, h.presenter.hidden.single())
    }

    @Test fun stopResetsNativeStateToUnknown() {
        val h = Harness()
        h.policy.start()
        h.policy.onNativeCamera(NativeCameraState.IDLE)
        h.step(TurnSignalState.LEFT)
        assertEquals(1, h.backend.opened.size)
        h.policy.stop()
        h.policy.start()
        h.now += 10
        h.sample(TurnSignalState.LEFT)
        h.policy.tick() // no fresh IDLE since stop → blocked
        assertEquals(1, h.backend.opened.size)
    }

    // ---- S1: the native-camera gate must hold at the show entry itself ----

    @Test fun unknownNativeBeforeAnyTickBlocksTheFirstFrameShow() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT) // IDLE, open
        h.policy.onNativeCamera(NativeCameraState.UNKNOWN)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        // The UNKNOWN report invalidates and releases at once; no show can follow.
        assertEquals(listOf("open:LEFT:1", "cancel:1:NATIVE_STATE_UNKNOWN"), h.events)
    }

    @Test fun unknownNativeReportHidesAVisibleCardImmediately() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.policy.onNativeCamera(NativeCameraState.UNKNOWN)
        assertEquals(TurnCameraPolicy.Reason.NATIVE_STATE_UNKNOWN, h.presenter.hidden.last())
        assertEquals(1, h.backend.closed.size)
    }

    @Test fun expiredIdleReportBlocksTheFirstFrameShowWithoutTick() {
        val h = Harness()
        h.policy.start()
        h.step(TurnSignalState.LEFT) // IDLE@0, request opened@0
        h.now = 1_500
        h.sample(TurnSignalState.LEFT) // fresh activation, IDLE report now expired
        h.backend.deliverFirstFrame(h.backend.opened.last().second, 1_450)
        assertTrue(h.events.none { it.startsWith("show") })
    }

    // ---- S2: broken signal timestamps never fabricate freshness; safe states still act ----

    @Test fun futureSampleCannotKeepTheCardAlive() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT) // last real sample around t=1
        repeat(10) {
            h.now += 1_000
            h.sample(TurnSignalState.LEFT, atMillis = h.now + 100_000) // future-dated
            h.native(NativeCameraState.IDLE)
            h.policy.tick()
        }
        assertEquals(TurnCameraPolicy.Reason.SAMPLE_STALE, h.presenter.hidden.first())
        assertEquals("never shown again from fabricated timestamps", 1, h.presenter.shown.size)
        assertEquals(1, h.backend.opened.size)
    }

    @Test fun outOfOrderSampleDoesNotRefreshFreshness() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.now = 800
        h.sample(TurnSignalState.LEFT, atMillis = 800) // accepted
        h.now = 1_801
        h.sample(TurnSignalState.LEFT, atMillis = 300) // out of order: ignored
        h.native(NativeCameraState.IDLE)
        h.policy.tick() // 1801-800 > 1000 → stale despite the newer-looking sample
        assertEquals(TurnCameraPolicy.Reason.SAMPLE_STALE, h.presenter.hidden.last())
    }

    @Test fun offSignalActsEvenWithABrokenTimestamp() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.now += 10
        h.sample(TurnSignalState.OFF, atMillis = 1_000_000) // future-dated OFF still hides
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:SIGNAL_OFF", "cancel:1:SIGNAL_OFF"), h.events)
    }

    @Test fun thresholdsAreValidatedAtConstruction() {
        val noop = object : TurnCameraPolicy.Output {
            override fun openRequest(side: TurnCameraSide, lease: CameraLease) {}
            override fun cancelRequest(lease: CameraLease, reason: TurnCameraPolicy.Reason) {}
            override fun show(side: TurnCameraSide, lease: CameraLease) {}
            override fun hide(reason: TurnCameraPolicy.Reason) {}
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnCameraPolicy(noop, 0L, TurnCameraPolicy.Thresholds(sampleStaleMillis = 0)) { 0L }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnCameraPolicy(noop, 0L, TurnCameraPolicy.Thresholds(retryCooldownMillis = -1)) { 0L }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TurnCameraPolicy(noop, 0L, TurnCameraPolicy.Thresholds(maxOpenRetries = -1)) { 0L }
        }
    }

    // ---- S3: lifecycle invalidation precedes any external output; reentry is dropped ----

    @Test fun tickReenteringDuringStopReleaseCannotOpenAnything() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        // Codex repro shape: cancelRequest synchronously ticks the policy.
        h.releaseBehavior = { lease ->
            h.backend.close(lease)
            h.policy.tick()
        }
        h.now += 1_000
        h.policy.stop()
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:STOPPED", "cancel:1:STOPPED"), h.events)
        assertEquals("stop must not be reentered into a new open", 1, h.backend.opened.size)
    }

    @Test fun sampleReenteringDuringReleaseIsDropped() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.releaseBehavior = { lease ->
            h.backend.close(lease)
            h.sample(TurnSignalState.OFF)
        }
        h.now += 10
        h.sample(TurnSignalState.OFF)
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:SIGNAL_OFF", "cancel:1:SIGNAL_OFF"), h.events)
    }

    @Test fun throwingShowKeepsThePolicyConsistent() {
        val h = Harness()
        h.policy.start()
        h.showBehavior = { _, _ -> throw IllegalStateException("presenter broken") }
        h.step(TurnSignalState.LEFT)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        assertTrue("show dispatch recorded even though it threw", h.events.any { it.startsWith("show") })
        h.now += 10
        h.sample(TurnSignalState.OFF)
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:SIGNAL_OFF", "cancel:1:SIGNAL_OFF"), h.events)
        h.showBehavior = { side, lease -> h.presenter.show(side, lease) }
        h.now += 1_100
        h.step(TurnSignalState.LEFT)
        assertEquals(2, h.backend.opened.size)
    }

    // ---- T1: safety-direction inputs reentered inside output callbacks are never lost ----

    @Test fun nativeTakeoverInsideShowIsNeverLost() {
        val h = Harness()
        h.policy.start()
        h.showBehavior = { _, _ -> h.policy.onNativeCamera(NativeCameraState.ACTIVE) }
        h.step(TurnSignalState.LEFT)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        // Codex repro: the takeover used to be dropped, leaving visible=true with [open, show].
        assertEquals(
            listOf("open:LEFT:1", "show:LEFT:1", "hide:NATIVE_CAMERA_ACTIVE", "cancel:1:NATIVE_CAMERA_ACTIVE"),
            h.events,
        )
        // Late frames for the released lease cannot resurrect the card while ACTIVE holds.
        h.now += 100
        h.backend.deliverFrame(h.backend.opened.last().second, h.now)
        h.step(TurnSignalState.LEFT) // fresh sample + IDLE attempt while ACTIVE was reported
        assertEquals(4, h.events.size)
    }

    @Test fun unknownInsideShowIsNeverLost() {
        val h = Harness()
        h.policy.start()
        h.showBehavior = { _, _ -> h.policy.onNativeCamera(NativeCameraState.UNKNOWN) }
        h.step(TurnSignalState.LEFT)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        assertEquals(
            listOf("open:LEFT:1", "show:LEFT:1", "hide:NATIVE_STATE_UNKNOWN", "cancel:1:NATIVE_STATE_UNKNOWN"),
            h.events,
        )
    }

    @Test fun currentLeaseClosedInsideShowIsApplied() {
        val h = Harness()
        h.policy.start()
        h.showBehavior = { _, lease -> h.policy.onClosed(lease) }
        h.step(TurnSignalState.LEFT)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        assertEquals(
            listOf("open:LEFT:1", "show:LEFT:1", "hide:REQUEST_CLOSED", "cancel:1:REQUEST_CLOSED"),
            h.events,
        )
    }

    @Test fun stopInsideShowTakesEffect() {
        val h = Harness()
        h.policy.start()
        h.showBehavior = { _, _ -> h.policy.stop() }
        h.step(TurnSignalState.LEFT)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1", "hide:STOPPED", "cancel:1:STOPPED"), h.events)
        h.now += 10
        h.step(TurnSignalState.LEFT) // stopped: nothing new
        assertEquals(4, h.events.size)
    }

    @Test fun offSignalInsideShowIsNeverLost() {
        val h = Harness()
        h.policy.start()
        h.showBehavior = { _, _ -> h.sample(TurnSignalState.OFF) }
        h.step(TurnSignalState.LEFT)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        assertEquals(
            listOf("open:LEFT:1", "show:LEFT:1", "hide:SIGNAL_OFF", "cancel:1:SIGNAL_OFF"),
            h.events,
        )
    }

    @Test fun oldLeaseFailureInsideShowIsIgnored() {
        val h = Harness()
        h.policy.start()
        val stranger = CameraLease(generation = 7L, requestId = 99L)
        h.showBehavior = { _, _ ->
            h.policy.onOpenFailed(stranger, "stale")
            h.policy.onClosed(stranger)
        }
        h.step(TurnSignalState.LEFT)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1"), h.events)
    }

    @Test fun tickInsideShowStillCannotOpenAnything() {
        val h = Harness()
        h.policy.start()
        h.showBehavior = { _, _ -> h.policy.tick() }
        h.step(TurnSignalState.LEFT)
        h.now += 1
        h.backend.deliverFirstFrame(h.backend.opened.last().second, h.now)
        // The opening-direction reentry is dropped (S3 semantics preserved).
        assertEquals(listOf("open:LEFT:1", "show:LEFT:1"), h.events)
        assertEquals(1, h.backend.opened.size)
    }

    // ---- U1: a safety release inside a side-change cancel overrides the outer activation ----

    @Test fun offInsideSideChangeCancelIsNotOverwritten() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        // Codex repro: Output.cancelRequest(SIDE_CHANGED) synchronously delivers OFF.
        h.releaseBehavior = { lease ->
            h.backend.close(lease)
            h.sample(TurnSignalState.OFF)
        }
        h.now = 100
        h.sample(TurnSignalState.RIGHT)
        // The queued OFF is drained at the input boundary and must win over the RIGHT
        // activation the outer sample wrote — no second open may ever follow.
        assertEquals(
            listOf("open:LEFT:1", "show:LEFT:1", "hide:SIDE_CHANGED", "cancel:1:SIDE_CHANGED"),
            h.events,
        )
        repeat(5) {
            h.now += 500
            h.native(NativeCameraState.IDLE)
            h.policy.tick()
        }
        assertEquals("no reopen after the cancelled activation", 1, h.backend.opened.size)
    }

    @Test fun hazardInsideSideChangeCancelIsNotOverwritten() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.releaseBehavior = { lease ->
            h.backend.close(lease)
            h.sample(TurnSignalState.HAZARD)
        }
        h.now = 100
        h.sample(TurnSignalState.RIGHT)
        assertEquals(
            listOf("open:LEFT:1", "show:LEFT:1", "hide:SIDE_CHANGED", "cancel:1:SIDE_CHANGED"),
            h.events,
        )
        h.now += 600
        h.native(NativeCameraState.IDLE)
        h.policy.tick()
        assertEquals(1, h.backend.opened.size)
    }

    @Test fun stopInsideSideChangeCancelIsNotOverwritten() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.releaseBehavior = { lease ->
            h.backend.close(lease)
            h.policy.stop()
        }
        h.now = 100
        h.sample(TurnSignalState.RIGHT)
        h.now += 600
        h.native(NativeCameraState.IDLE)
        h.policy.tick()
        assertEquals(1, h.backend.opened.size)
        assertEquals(4, h.events.size)
    }

    @Test fun takeoverInsideWatchdogReleaseIsNotOverwritten() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        h.releaseBehavior = { lease ->
            h.backend.close(lease)
            h.policy.onNativeCamera(NativeCameraState.ACTIVE)
        }
        // A watchdog expiry (frame stale) releases while ACTIVE is reported inside it.
        h.now += 501
        h.sample(TurnSignalState.LEFT) // keep the sample fresh so only the frame expires
        h.policy.tick()
        assertEquals(TurnCameraPolicy.Reason.FRAME_STALE, h.presenter.hidden.last())
        h.now += 600
        h.native(NativeCameraState.ACTIVE)
        h.policy.tick()
        assertEquals(1, h.backend.opened.size)
    }

    @Test fun repeatingSafetyInputsKeepTheQueueBounded() {
        val h = Harness()
        h.policy.start()
        h.visible(TurnCameraSide.LEFT)
        val lease = h.backend.opened.last().second
        // Hammer safety inputs from inside a release callback: coalescing + cap must hold.
        h.releaseBehavior = { l ->
            h.backend.close(l)
            repeat(50) {
                h.policy.onNativeCamera(NativeCameraState.UNKNOWN)
                h.policy.onSample(TurnSignalSample(TurnSignalState.OFF, TurnSignalSemantics.LATCHED_LEVER, h.now))
                h.policy.onClosed(CameraLease(generation = 7L, requestId = 10_000L + it))
            }
        }
        h.now += 10
        h.sample(TurnSignalState.OFF)
        // The drain must terminate and leave the policy consistent (no throw, bounded queue).
        assertEquals(1, h.presenter.shown.size)
        assertEquals(1, h.backend.closed.size)
        h.now += 100
        h.policy.tick()
        assertEquals(1, h.backend.opened.size)
    }
    @Test fun throwingOpenRequestFailsSafeViaFirstFrameTimeout() {
        var now = 0L
        var opened = 0
        var cancelled = 0
        val policy = TurnCameraPolicy(
            object : TurnCameraPolicy.Output {
                override fun openRequest(side: TurnCameraSide, lease: CameraLease) {
                    opened++
                    throw IllegalStateException("backend open failed")
                }
                override fun cancelRequest(lease: CameraLease, reason: TurnCameraPolicy.Reason) { cancelled++ }
                override fun show(side: TurnCameraSide, lease: CameraLease) {}
                override fun hide(reason: TurnCameraPolicy.Reason) {}
            },
            generation = 9L,
            clock = { now },
        )
        policy.start()
        policy.onNativeCamera(NativeCameraState.IDLE)
        policy.onSample(TurnSignalSample(TurnSignalState.LEFT, TurnSignalSemantics.LATCHED_LEVER, 0))
        policy.tick() // the open dispatch throws inside; the policy must swallow it
        assertEquals(1, opened)
        assertEquals(0, cancelled)
        now = 2_001
        policy.tick() // the phantom request dies at the first-frame timeout
        assertEquals(1, cancelled)
    }
}
