package com.shilapi.xcertplay.turncamera

import org.junit.Assert.*
import org.junit.Test

class TurnCameraCoordinatorTest {
    private class Backend : SideCameraBackend {
        override val leftConfigured = true
        override val rightConfigured = true
        val opens = mutableListOf<Pair<TurnCameraSide, CameraLease>>()
        val callbacks = mutableMapOf<CameraLease, SideCameraBackend.Callback>()
        val events = mutableListOf<String>()
        override fun open(lease: CameraLease, side: TurnCameraSide, callback: SideCameraBackend.Callback) {
            opens += side to lease; callbacks[lease] = callback; events += "open:$side"
        }
        override fun close(lease: CameraLease) { events += "close" }
        override fun close() { events += "close-all" }
    }
    private class Fixture {
        var now = 1000L
        val queue = ArrayDeque<() -> Unit>()
        val backend = Backend()
        val shown = mutableListOf<TurnCameraSide>()
        var permit = true
        val coordinator = TurnCameraCoordinator(backend, object : TurnCameraPresenter {
            override fun show(side: TurnCameraSide, lease: CameraLease) { shown += side; backend.events += "show:$side" }
            override fun hide(reason: TurnCameraPolicy.Reason) { backend.events += "hide:$reason" }
        }, { queue.addLast(it) }, 99, { now }, { permit })
        init { coordinator.start() }
        fun sample(state: TurnSignalState, native: NativeCameraState = NativeCameraState.IDLE) =
            coordinator.reading(TurnSignalSample(state, TurnSignalSemantics.LATCHED_LEVER, now), native)
        fun drain() { while (queue.isNotEmpty()) queue.removeFirst()() }
        fun frame(index: Int) { val lease = backend.opens[index].second; backend.callbacks.getValue(lease).onFirstFrame(lease, now); drain() }
    }
    @Test fun leftThenRightUsesDistinctLeasesAndLateLeftFrameCannotReappear() {
        val f = Fixture(); f.sample(TurnSignalState.LEFT)
        assertTrue(f.shown.isEmpty())
        f.frame(0); assertEquals(listOf(TurnCameraSide.LEFT), f.shown)
        f.sample(TurnSignalState.RIGHT)
        assertEquals(listOf(TurnCameraSide.LEFT, TurnCameraSide.RIGHT), f.backend.opens.map { it.first })
        assertTrue(f.backend.events.indexOf("hide:SIDE_CHANGED") < f.backend.events.indexOf("close"))
        f.frame(0); assertEquals(1, f.shown.size)
        f.frame(1); assertEquals(listOf(TurnCameraSide.LEFT, TurnCameraSide.RIGHT), f.shown)
        f.sample(TurnSignalState.OFF)
        assertTrue(f.backend.events.contains("hide:SIGNAL_OFF"))
    }
    @Test fun hazardsOemTakeoverUnknownAndStaleFramesAlwaysHide() {
        for (native in listOf(NativeCameraState.ACTIVE, NativeCameraState.UNKNOWN)) {
            val f = Fixture(); f.sample(TurnSignalState.LEFT, native)
            assertTrue(f.backend.opens.isEmpty()); f.coordinator.close()
        }
        val f = Fixture(); f.sample(TurnSignalState.LEFT); f.frame(0)
        f.sample(TurnSignalState.HAZARD)
        assertTrue(f.backend.events.contains("hide:SIGNAL_HAZARD"))
        f.sample(TurnSignalState.RIGHT); f.frame(1)
        f.now += 501; f.coordinator.tick()
        assertTrue(f.backend.events.contains("hide:FRAME_STALE"))
    }
    @Test fun queuedCallbacksAndReadingsAfterStopDoNotShowOrOpen() {
        val f = Fixture(); f.sample(TurnSignalState.LEFT)
        val lease = f.backend.opens.single().second
        f.backend.callbacks.getValue(lease).onFirstFrame(lease, f.now)
        f.coordinator.close(); f.drain()
        f.sample(TurnSignalState.RIGHT); f.coordinator.tick()
        assertTrue(f.shown.isEmpty()); assertEquals(1, f.backend.opens.size)
    }
    @Test fun takeoverBeforeQueuedFirstFrameCannotRevealPixelsBeforeNextPollDelivery() {
        val f = Fixture(); f.sample(TurnSignalState.LEFT)
        val lease = f.backend.opens.single().second
        f.backend.callbacks.getValue(lease).onFirstFrame(lease, f.now)
        f.permit = false // Worker has observed takeover; its UI reading is not delivered yet.
        f.drain()
        assertTrue(f.shown.isEmpty())
        assertTrue(f.backend.events.contains("close"))
        f.coordinator.close()
    }
    @Test fun synchronousFirstFrameIsEnqueuedInsteadOfBeingDroppedInsidePolicyOutput() {
        val queue = ArrayDeque<() -> Unit>(); var showed = false
        val backend = object : SideCameraBackend {
            override val leftConfigured = true; override val rightConfigured = true
            override fun open(lease: CameraLease, side: TurnCameraSide, callback: SideCameraBackend.Callback) { callback.onFirstFrame(lease, 1000) }
            override fun close(lease: CameraLease) = Unit
            override fun close() = Unit
        }
        val c = TurnCameraCoordinator(backend, object : TurnCameraPresenter {
            override fun show(side: TurnCameraSide, lease: CameraLease) { showed = true }
            override fun hide(reason: TurnCameraPolicy.Reason) = Unit
        }, { queue.addLast(it) }, 1, { 1000 })
        c.start(); c.reading(TurnSignalSample(TurnSignalState.LEFT, TurnSignalSemantics.LATCHED_LEVER, 1000), NativeCameraState.IDLE)
        assertFalse(showed); while (queue.isNotEmpty()) queue.removeFirst()()
        assertTrue(showed); c.close()
    }
}
