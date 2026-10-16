package com.shilapi.xcertplay.turncamera

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VendorAvmCameraDriverTest {
    class Target
    class FakeAvm {
        fun addPreviewSurface(surface: Target, channel: Int): Boolean { events += "bind:$channel"; bindHook(); return bindOk }
        fun startPreview(): Boolean { events += "start"; return startOk }
        fun stopPreview(): Boolean { events += "stop"; return stopOk }
        fun close() { events += "close"; closeHook(); if (closeFails) error("close failed") }
        companion object {
            val events = mutableListOf<String>()
            var bindOk = true
            var startOk = true
            var stopOk = true
            var closeFails = false
            var openHook: () -> Unit = {}
            var bindHook: () -> Unit = {}
            var closeHook: () -> Unit = {}
            @JvmStatic fun open(id: Int): FakeAvm { events += "open:$id"; openHook(); return FakeAvm() }
        }
    }
    class MissingClose {
        fun addPreviewSurface(surface: Target, channel: Int) = true
        fun startPreview() = true
        fun stopPreview() = true
        companion object { @JvmStatic fun open(id: Int): MissingClose { error("must not open") } }
    }
    private fun driver() = ReflectionAvmSessions(Target::class.java) { FakeAvm::class.java }
    @Before fun reset() {
        FakeAvm.events.clear(); FakeAvm.bindOk = true; FakeAvm.startOk = true; FakeAvm.stopOk = true; FakeAvm.closeFails = false
        FakeAvm.openHook = {}; FakeAvm.bindHook = {}; FakeAvm.closeHook = {}
    }

    @Test fun constructionAndDeniedGuardDoNotEvenResolveVendorClass() {
        var resolutions = 0
        val driver = ReflectionAvmSessions(Target::class.java) { resolutions++; FakeAvm::class.java }
        assertEquals(0, resolutions)
        assertThrows(IllegalStateException::class.java) { driver.open(1, Target(), 0) { false } }
        assertEquals(0, resolutions); assertTrue(FakeAvm.events.isEmpty())
    }
    @Test fun fullAbiIsResolvedBeforeOpening() {
        val driver = ReflectionAvmSessions(Target::class.java) { MissingClose::class.java }
        assertThrows(NoSuchMethodException::class.java) { driver.open(1, Target(), 0) { true } }
        assertTrue(FakeAvm.events.isEmpty())
    }
    @Test fun successfulSessionHasExplicitIdChannelAndIdempotentClose() {
        val driver = driver()
        val session = driver.open(7, Target(), 3) { true }
        assertEquals(listOf("open:7", "bind:3", "start"), FakeAvm.events)
        assertThrows(IllegalStateException::class.java) { driver.open(8, Target(), 0) { true } }
        session.close(); session.close()
        assertEquals(listOf("open:7", "bind:3", "start", "stop", "close"), FakeAvm.events)
        driver.open(8, Target(), 0) { true }.close()
    }
    @Test fun failedBindClosesExactlyOnceAndNeverStarts() {
        FakeAvm.bindOk = false
        assertThrows(IllegalStateException::class.java) { driver().open(1, Target(), 0) { true } }
        assertEquals(listOf("open:1", "bind:0", "close"), FakeAvm.events)
    }
    @Test fun targetExceptionDoesNotFallbackToAnotherOverloadOrLeakTheHandle() {
        FakeAvm.bindHook = { error("partial bind") }
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) { driver().open(1, Target(), 0) { true } }
        assertEquals(listOf("open:1", "bind:0", "close"), FakeAvm.events)
    }
    @Test fun failedStartStillAttemptsStopAndClose() {
        FakeAvm.startOk = false
        assertThrows(IllegalStateException::class.java) { driver().open(1, Target(), 0) { true } }
        assertEquals(listOf("open:1", "bind:0", "start", "stop", "close"), FakeAvm.events)
    }
    @Test fun failedCloseQuarantinesAndDoesNotClaimTheCameraWasReleased() {
        val driver = driver()
        val session = driver.open(1, Target(), 0) { true }
        FakeAvm.closeFails = true
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) { session.close() }
        assertThrows(IllegalStateException::class.java) { driver.open(2, Target(), 0) { true } }
        assertEquals(1, FakeAvm.events.count { it.startsWith("open:") })
    }
    @Test fun cancellationWhileVendorOpenBlocksClosesLateResultWithoutBinding() {
        val driver = driver()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
        FakeAvm.openHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        var failure: Throwable? = null
        val worker = Thread {
            try { driver.open(4, Target(), 0, allowed::get); error("late result escaped") }
            catch (t: Throwable) { failure = t }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); allowed.set(false)
            assertThrows(IllegalStateException::class.java) { driver.open(5, Target(), 0) { true } }
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive); assertTrue(failure is IllegalStateException)
        assertEquals(listOf("open:4", "close"), FakeAvm.events)
    }
    @Test fun revokeAfterBindingCleansUpBeforeStarting() {
        var allowed = true
        FakeAvm.bindHook = { allowed = false }
        assertThrows(IllegalStateException::class.java) { driver().open(1, Target(), 0) { allowed } }
        assertEquals(listOf("open:1", "bind:0", "close"), FakeAvm.events)
    }
    @Test fun anOpenExceptionWithNoRecoverableHandleRetiresTheAdapter() {
        val driver = driver()
        FakeAvm.openHook = { error("vendor may have partially opened") }
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) { driver.open(1, Target(), 0) { true } }
        assertThrows(IllegalStateException::class.java) { driver.open(2, Target(), 0) { true } }
        assertEquals(listOf("open:1"), FakeAvm.events)
    }
    @Test fun failedStopStillClosesButQuarantinesConservatively() {
        val driver = driver(); val session = driver.open(1, Target(), 0) { true }
        FakeAvm.stopOk = false
        assertThrows(IllegalStateException::class.java) { session.close() }
        assertEquals(listOf("open:1", "bind:0", "start", "stop", "close"), FakeAvm.events)
        assertThrows(IllegalStateException::class.java) { driver.open(2, Target(), 0) { true } }
    }
    @Test fun blockedCloseKeepsItsSlotUntilTheOwningWorkerActuallyFinishes() {
        val driver = driver(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var failure: Throwable? = null
        FakeAvm.closeHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val worker = Thread {
            try { driver.open(1, Target(), 0) { true }.close() } catch (t: Throwable) { failure = t }
        }.apply { start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { driver.open(2, Target(), 0) { true } }
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive); assertNull(failure)
        FakeAvm.closeHook = {}
        driver.open(2, Target(), 0) { true }.close()
    }
    @Test fun aDifferentThreadCannotCloseOrReleaseTheOwnerSlot() {
        val driver = driver(); val session = driver.open(1, Target(), 0) { true }
        var error: Throwable? = null
        Thread { try { session.close() } catch (t: Throwable) { error = t } }.apply { start(); join(5000) }
        assertTrue(error is IllegalStateException)
        assertThrows(IllegalStateException::class.java) { driver.open(2, Target(), 0) { true } }
        session.close()
    }
}
