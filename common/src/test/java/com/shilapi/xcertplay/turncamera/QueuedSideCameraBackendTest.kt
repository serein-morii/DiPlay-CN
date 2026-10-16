package com.shilapi.xcertplay.turncamera

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class QueuedSideCameraBackendTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Port : TurnCameraSurfacePort<Any> {
        lateinit var ready: (Any) -> Unit
        lateinit var frame: (Long) -> Unit
        lateinit var lost: () -> Unit
        val events = mutableListOf<String>()
        override fun prepare(lease: CameraLease, route: TurnCameraRoute, ready: (Any) -> Unit, frame: (Long) -> Unit, lost: () -> Unit) {
            this.ready = ready; this.frame = frame; this.lost = lost; events += "prepare"
        }
        override fun hide(lease: CameraLease) { events += "hide" }
        override fun dispose(lease: CameraLease, confirmed: Boolean) { events += "dispose:$confirmed" }
    }
    private class Fixture {
        val port = Port(); val worker = Queue(); val ui = Queue()
        var allowed = true; var opens = 0; var closes = 0; var closeThrows = false
        var firsts = 0; var frames = 0; var errors = 0
        val lease = CameraLease(1, 1)
        val callback = object : SideCameraBackend.Callback {
            override fun onFirstFrame(lease: CameraLease, atMillis: Long) { firsts++ }
            override fun onFrame(lease: CameraLease, atMillis: Long) { frames++ }
            override fun onOpenFailed(lease: CameraLease, error: String) { errors++ }
            override fun onClosed(lease: CameraLease) = Unit
        }
        var factory: (() -> Boolean) -> AutoCloseable = { guard ->
            check(guard()); opens++
            AutoCloseable { closes++; if (closeThrows) error("unconfirmed close") }
        }
        val route = TurnCameraRoute(7, 0, 640, 480, TurnCameraCalibration(), TurnCameraFrameTimebase.ELAPSED_REALTIME_NANOS)
        val backend = QueuedSideCameraBackend(mapOf(TurnCameraSide.LEFT to route, TurnCameraSide.RIGHT to route.copy(id = 8)),
            port, worker, { ui.execute(it) }, { allowed }, { 1000 }, { 1_000_000_000 }) { _, _, guard -> factory(guard) }
        fun open() { backend.open(lease, TurnCameraSide.LEFT, callback); port.ready(Any()) }
        fun drain() { worker.drain(); ui.drain(); worker.drain(); ui.drain() }
    }
    @Test fun onlyRealFreshFramesAfterOpenCompletionCanShow() {
        val f = Fixture(); f.open()
        f.port.frame(1_000_000_000); assertEquals(0, f.firsts)
        f.drain(); assertEquals(0, f.firsts)
        f.port.frame(0); f.port.frame(1_001_000_000); f.port.frame(400_000_000)
        assertEquals(0, f.firsts)
        f.port.frame(999_000_000); assertEquals(1, f.firsts)
        f.port.frame(999_000_000); assertEquals(0, f.frames)
        f.port.frame(1_000_000_000); assertEquals(1, f.frames)
        f.backend.close(f.lease); f.port.frame(1_000_000_000)
        assertEquals(1, f.frames); f.drain(); assertEquals(1, f.closes)
    }
    @Test fun cancelledBeforeWorkerRunsNeverOpensAndHidesBeforeDisposal() {
        val f = Fixture(); f.open(); f.backend.close(f.lease)
        assertEquals(listOf("prepare", "hide"), f.port.events)
        f.drain(); assertEquals(0, f.opens); assertEquals("dispose:true", f.port.events.last())
    }
    @Test fun rapidSideChangesCannotAllocateMoreSurfacesWhileCloseIsPending() {
        val f = Fixture(); f.open(); f.drain(); f.backend.close(f.lease)
        repeat(50) { f.backend.open(CameraLease(1, it + 2L), TurnCameraSide.RIGHT, f.callback) }
        assertEquals(1, f.port.events.count { it == "prepare" })
        assertEquals(50, f.errors)
        f.drain(); assertEquals(1, f.closes)
    }
    @Test fun cancellationDuringOpenClosesLateHandleOnWorkerAndCannotReportFirstFrame() {
        val f = Fixture()
        f.factory = { _ -> f.opens++; f.backend.close(f.lease); AutoCloseable { f.closes++ } }
        f.open(); f.drain()
        f.port.frame(1_000_000_000)
        assertEquals(1, f.closes); assertEquals(0, f.firsts)
        assertEquals(1, f.port.events.count { it == "dispose:true" })
    }
    @Test fun failedCloseRetainsSurfaceAndRetiresBackendRatherThanPretendingRelease() {
        val f = Fixture(); f.open(); f.drain(); f.closeThrows = true
        f.backend.close(f.lease); f.drain()
        assertEquals("dispose:false", f.port.events.last())
        f.backend.open(CameraLease(2, 1), TurnCameraSide.RIGHT, f.callback)
        assertEquals(1, f.opens); assertEquals(1, f.errors)
    }
    @Test fun surfaceLossIsCancelledEvenWhenClientDoesNotCloseOnFailure() {
        val f = Fixture(); f.open(); f.drain(); f.port.lost(); f.drain()
        assertEquals(1, f.errors); assertEquals(1, f.closes)
        assertEquals("dispose:true", f.port.events.last())
    }
    @Test fun vendorExceptionKeepsUnconfirmedSurfaceQuarantined() {
        val f = Fixture(); f.factory = { error("partial native open") }
        f.open(); f.drain()
        assertEquals(1, f.errors); assertEquals("dispose:false", f.port.events.last())
    }
    @Test fun monotonicAndElapsedClocksCannotBeGuessedOrRefreshedByDuplicates() {
        val clock = TurnCameraFrameClock(TurnCameraFrameTimebase.MONOTONIC_NANOS)
        assertEquals(1990L, clock.frameMillis(990_000_000, 2000, 1_000_000_000))
        assertNull(clock.frameMillis(990_000_000, 2100, 1_100_000_000))
        assertNull(clock.frameMillis(1_200_000_000, 2100, 1_100_000_000))
        assertNull(TurnCameraFrameClock(TurnCameraFrameTimebase.ELAPSED_REALTIME_NANOS).frameMillis(1, 2000, 1_000_000_000))
    }
}
