package com.shilapi.xcertplay

import com.shilapi.xcertplay.adb.LocalAdb
import org.junit.Assert.assertEquals
import org.junit.Test

/** At boot the car's adb can come up after DiPlay; one early restore left the keys unbound (#606, #619). */
class WheelKeyRestoreRetryTest {
    @Test fun unreachableAdbAtBootIsTriedAgainUntilTheServiceBinds() {
        val sleeps = mutableListOf<Long>()
        val results = mutableListOf<Pair<Int, LocalAdb.Access>>()
        var bound = false
        var restores = 0
        WheelKeyService.restoreWithRetries(
            needsRestore = { !bound },
            restore = { (if (++restores < 3) LocalAdb.Access.UNREACHABLE else LocalAdb.Access.READY).also { if (restores == 3) bound = true } },
            sleep = { sleeps += it },
        ) { attempt, access -> results += attempt to access }

        assertEquals(listOf(1 to LocalAdb.Access.UNREACHABLE, 2 to LocalAdb.Access.UNREACHABLE, 3 to LocalAdb.Access.READY), results)
        // The fourth wake finds the service bound and stops.
        assertEquals(listOf(4_000L, 10_000L, 10_000L, 10_000L), sleeps)
    }

    @Test fun anUnapprovedKeyIsNotRetriedAndAttemptsAreBounded() {
        val results = mutableListOf<LocalAdb.Access>()
        WheelKeyService.restoreWithRetries({ true }, { LocalAdb.Access.NOT_APPROVED }, {}) { _, access -> results += access }
        assertEquals(listOf(LocalAdb.Access.NOT_APPROVED), results)

        results.clear()
        WheelKeyService.restoreWithRetries({ true }, { LocalAdb.Access.UNREACHABLE }, {}, attempts = 6) { _, access -> results += access }
        assertEquals(6, results.size)
    }

    @Test fun aServiceThatBoundDuringTheGraceIsLeftAlone() {
        var restores = 0
        WheelKeyService.restoreWithRetries({ false }, { restores++; LocalAdb.Access.READY }, {}) { _, _ -> }
        assertEquals(0, restores)
    }
}
