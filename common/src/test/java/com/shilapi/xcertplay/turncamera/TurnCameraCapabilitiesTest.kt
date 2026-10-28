package com.shilapi.xcertplay.turncamera

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase A probe behaviour: whitelisted read-only commands, strict structured shell
 * parsing with integrity checks, a real monotonic deadline that bounds even a
 * permanently blocking call, cooperative cancellation, a process-wide worker budget
 * (busy/quarantine), true sanitization of report text and honest "unconfirmed"
 * semantics. Robolectric here stands in for the Android APIs only; these results say
 * nothing about any real head unit (plan §6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TurnCameraCapabilitiesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun cameraMetadataIncludesStaticOrientationAndRangesWithoutInferringPhysicalSides() {
        val manager = context.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val c = org.robolectric.shadows.ShadowCameraCharacteristics.newCameraCharacteristics()
        val metadata = org.robolectric.Shadows.shadowOf(c)
        metadata.set(android.hardware.camera2.CameraCharacteristics.LENS_FACING, android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK)
        metadata.set(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION, 90)
        metadata.set(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE, android.util.Size(1920, 1080))
        metadata.set(android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM, 4f)
        metadata.set(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES, arrayOf(android.util.Range(15, 30)))
        metadata.set(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES, intArrayOf())
        org.robolectric.Shadows.shadowOf(manager).addCamera("external/0", c)
        val report = TurnCameraCapabilities(shellRunner = { okServiceList() }, systemProperty = { null }).run(context)
        val detail = report.items.first { it.key == "camera2_ids" }.detail
        assertTrue(detail.contains("external/0=back"))
        assertTrue(detail.contains("sensor-orientation=90"))
        assertTrue(detail.contains("1920x1080"))
        assertTrue(detail.contains("max-digital-zoom=4.0"))
        assertTrue(detail.contains("15-30"))
        assertTrue(detail.contains("not measured FPS"))
        assertTrue(detail.contains("not a camera count or side identity"))
    }

    private fun okServiceList(): TurnCameraCapabilities.ShellOutcome =
        TurnCameraCapabilities.ShellOutcome(
            exitCode = 0,
            stdout = "Found 2 services:\n1\tcamera: [android.hardware.ICameraService]\n2\tactivity: [android.app.IActivityManager]\n",
            stderr = "",
            timedOut = false,
        )

    @Test fun reportCoversAllSectionsAndNeverClaimsVerification() {
        val probe = TurnCameraCapabilities(
            shellRunner = { okServiceList() },
            systemProperty = { if (it == "apps.setting.product.outswver") "3.0.0.0" else null },
            wallClock = { 1_728_000_000_000L },
            monotonicClock = { 5_000_000_000L },
        )
        val report = probe.run(context)
        val keys = report.items.map { it.key }
        assertTrue(
            keys.containsAll(
                listOf(
                    "firmware_build", "cluster_display", "camera_permission",
                    "camera2_ids", "oem_avm_package", "camera_services",
                )
            )
        )
        assertTrue(keys.any { it == "vendor_class:android.hardware.AVMCamera" })
        assertTrue(keys.any { it == "vendor_file:/vendor/lib64/libais_client.so" })
        assertEquals(0, report.durationMillis)
        val text = report.toReportText()
        assertTrue("turn signal must be marked unconfirmed", text.contains("turn_signal_source: UNCONFIRMED"))
        assertTrue("must disclaim streaming capability", text.contains("proves no streaming capability"))
        assertTrue(
            "firmware item records the whitelisted controller property",
            report.items.first { it.key == "firmware_build" }.detail.contains("controller=3.0.0.0"),
        )
        assertTrue(report.summaryLine().startsWith("hints="))
        assertTrue(report.summaryLine().contains("unconfirmed="))
    }

    @Test fun nonWhitelistedShellCommandsAreRefusedBeforeExecution() {
        val runner = TurnCameraCapabilities.ShellRunner.system()
        assertThrows(IllegalArgumentException::class.java) { runner.run(listOf("getprop")) }
        assertThrows(IllegalArgumentException::class.java) { runner.run(listOf("sh", "-c", "getprop")) }
        assertThrows(IllegalArgumentException::class.java) { runner.run(listOf("service", "call", "camera", "3")) }
    }

    /**
     * A stale interrupt flag on the caller thread (leaked by an unrelated earlier test in
     * this JVM) makes every future.get throw InterruptedException at once: items report
     * "cancelled" and run() returns in milliseconds. All deadline/blocking tests clear it.
     */
    private fun clearStaleCallerInterrupt() {
        if (Thread.interrupted()) {
            println("cleared a stale caller interrupt flag before a blocking probe test")
        }
    }

    @Test fun permanentlyBlockingItemStillReturnsAtTheDeadline() {
        clearStaleCallerInterrupt()
        val release = CountDownLatch(1)
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        val probe = TurnCameraCapabilities(
            shellRunner = {
                entered.set(true) // evidence that the blocking step really runs
                release.await() // never returns until the test releases it
                okServiceList()
            },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = System::nanoTime, // real monotonic: the 300ms deadline must bite
            deadlineMillis = 300L,
        )
        val started = System.nanoTime()
        val report = probe.run(context)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        release.countDown()
        assertTrue("run must return near the deadline, took ${elapsedMs}ms", elapsedMs < 5_000L)
        assertTrue("the run must actually spend its deadline blocked", elapsedMs >= 250L)
        assertTrue("the blocking shell step must have been entered", entered.get())
        assertTrue(report.deadlineHit)
        val services = report.items.first { it.key == "camera_services" }
        assertEquals(TurnCameraCapabilities.Status.ERROR, services.status)
        assertTrue(services.detail.contains("not interruptible"))
    }

    // ---- Rework D1: sub-millisecond budgets must stay live until the true deadline ----

    /** Freezes the clock like Codex's repro: first read 0, every later read 500_000ns. */
    @Test fun subMillisecondEntryBudgetIsLiveUntilTheTrueDeadline() {
        clearStaleCallerInterrupt()
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val probe = TurnCameraCapabilities(
            shellRunner = { okServiceList() },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = { if (reads.incrementAndGet() == 1) 0L else 500_000L },
            deadlineMillis = 1L, // deadlineMono = 1_000_000ns; remaining stays 500_000ns
        )
        val report = probe.run(context)
        // With a positive (sub-ms) remaining budget nothing may claim expiry…
        assertTrue(
            "a positive sub-millisecond budget must not be floored to expiry",
            report.items.none { it.detail == "deadline reached" || it.detail.contains("exceeded") },
        )
        // …and an expiry claim would require deadlineHit (consistency invariant).
        assertTrue(report.items.none { it.status == TurnCameraCapabilities.Status.SKIPPED } || report.deadlineHit)
    }

    /** Exactly-zero remaining at entry is genuinely expired and must be reported as such. */
    @Test fun exactlyExpiredEntryIsSkippedConsistently() {
        clearStaleCallerInterrupt()
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val probe = TurnCameraCapabilities(
            shellRunner = { okServiceList() },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = { if (reads.incrementAndGet() == 1) 0L else 1_000_000L },
            deadlineMillis = 1L,
        )
        val report = probe.run(context)
        assertEquals(TurnCameraCapabilities.Status.SKIPPED, report.items.first().status)
        assertEquals("deadline reached", report.items.first().detail)
        assertTrue("an expiry claim requires deadlineHit", report.deadlineHit)
    }

    /**
     * In-flight sub-millisecond budget: after submission the wait loop must keep waiting in
     * nanosecond slices until the clock truly crosses the deadline — then (and only then)
     * report ERROR "exceeded" together with deadlineHit=true. A slow-advancing clock
     * guarantees the loop passes through the (0, 1ms) window the old flooring code
     * collapsed to an immediate, inconsistent expiry.
     */
    @Test fun inFlightBudgetExpiresOnlyAtTheTrueDeadline() {
        clearStaleCallerInterrupt()
        val release = CountDownLatch(1)
        val clock = java.util.concurrent.atomic.AtomicLong(0L)
        val probe = TurnCameraCapabilities(
            shellRunner = {
                release.await() // blocked for the whole run
                okServiceList()
            },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = { clock.getAndAdd(20_000L) }, // +20µs per read, deadline 2ms
            deadlineMillis = 2L,
        )
        val report = probe.run(context)
        release.countDown()
        val services = report.items.first { it.key == "camera_services" }
        assertEquals(
            "the blocked item must expire, not be skipped at entry",
            TurnCameraCapabilities.Status.ERROR,
            services.status,
        )
        assertTrue(services.detail.contains("exceeded"))
        assertTrue("expiry must coincide with the true deadline", report.deadlineHit)
        // The consistency invariant across the whole report.
        assertTrue(
            report.items.none { it.detail == "deadline reached" || it.detail.contains("exceeded") } || report.deadlineHit,
        )
    }

    // ---- Rework D2: interrupt hygiene regression without scheduling assumptions ----

    @Test fun staleCallerInterruptIsClearedAndCannotFakeCancellation() {
        // Red part — only scheduling-free semantics of the interrupted-caller path:
        // the run cannot block to spend its deadline, items are "cancelled", and the
        // report is not deadline-hit. (Whether a worker briefly entered the lambda is a
        // race we deliberately do NOT assert.)
        Thread.currentThread().interrupt()
        val release = CountDownLatch(1)
        val probe = TurnCameraCapabilities(
            shellRunner = { release.await(); okServiceList() },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = System::nanoTime,
            deadlineMillis = 300L,
        )
        val started = System.nanoTime()
        val interruptedReport = probe.run(context)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        release.countDown()
        assertTrue("interrupted caller returns without spending the deadline", elapsedMs < 250L)
        assertTrue(!interruptedReport.deadlineHit)
        assertEquals(
            TurnCameraCapabilities.Status.SKIPPED,
            interruptedReport.items.first { it.key == "camera_services" }.status,
        )
        // Green part — actually re-inject the flag, prove the helper clears it, then run
        // the same probe with full-strength deadline assertions.
        Thread.currentThread().interrupt()
        clearStaleCallerInterrupt()
        assertTrue("the hygiene helper must clear a present interrupt flag", !Thread.currentThread().isInterrupted)
        val release2 = CountDownLatch(1)
        val entered = java.util.concurrent.atomic.AtomicBoolean(false)
        val healthy = TurnCameraCapabilities(
            shellRunner = { entered.set(true); release2.await(); okServiceList() },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = System::nanoTime,
            deadlineMillis = 300L,
        )
        val started2 = System.nanoTime()
        val report = healthy.run(context)
        val elapsed2 = (System.nanoTime() - started2) / 1_000_000L
        release2.countDown()
        assertTrue(elapsed2 >= 250L)
        assertTrue(entered.get())
        assertTrue(report.deadlineHit)
        val services = report.items.first { it.key == "camera_services" }
        assertEquals(TurnCameraCapabilities.Status.ERROR, services.status)
        assertTrue(services.detail.contains("not interruptible"))
    }

    @Test fun cancellationStopsRemainingItemsQuickly() {
        clearStaleCallerInterrupt()
        val release = CountDownLatch(1)
        val probe = TurnCameraCapabilities(
            shellRunner = {
                release.await()
                okServiceList()
            },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = { 1_000_000_000L },
            deadlineMillis = 60_000L,
        )
        val finished = CountDownLatch(1)
        val result = arrayOfNulls<TurnCameraCapabilities.Report>(1)
        val worker = Thread({
            result[0] = probe.run(context)
            finished.countDown()
        }).apply { isDaemon = true; start() }
        Thread.sleep(400) // let the run reach the blocking item
        probe.cancel()
        assertTrue("run must honor cancellation", finished.await(5, TimeUnit.SECONDS))
        release.countDown()
        val report = result[0]!!
        assertTrue(report.cancelled)
        assertEquals(TurnCameraCapabilities.Status.SKIPPED, report.items.first { it.key == "camera_services" }.status)
        assertTrue(report.items.any { it.status == TurnCameraCapabilities.Status.SKIPPED })
    }

    @Test fun wallClockJumpsDoNotExtendTheDeadline() {
        var wall = 1_000L
        val probe = TurnCameraCapabilities(
            shellRunner = { okServiceList() },
            systemProperty = { null },
            wallClock = { wall += 1_000_000L; wall }, // jumps wildly on every read
            monotonicClock = { 42L },
        )
        val report = probe.run(context)
        assertEquals(0, report.durationMillis)
        assertFalse(report.deadlineHit)
    }

    @Test fun perItemFailureIsRecordedAndOthersContinue() {
        val probe = TurnCameraCapabilities(
            shellRunner = { throw RuntimeException("denied by SELinux") },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = { 0L },
        )
        val report = probe.run(context)
        val serviceItem = report.items.first { it.key == "camera_services" }
        assertEquals(TurnCameraCapabilities.Status.ERROR, serviceItem.status)
        assertTrue(serviceItem.detail.contains("denied by SELinux"))
        assertTrue(
            report.items.any { it.key == "firmware_build" && it.status == TurnCameraCapabilities.Status.OK },
        )
    }

    @Test fun systemPropertyReaderOnlyServesWhitelistedKeys() {
        assertNull(TurnCameraCapabilities.readSystemProperty("ro.secure"))
        assertNull(TurnCameraCapabilities.readSystemProperty(""))
    }

    // ---- Rework R6/S5: strict parsing; presence never follows from error text ----

    @Test fun errorOutputContainingCameraStaysError() {
        val outcome = TurnCameraCapabilities.ShellOutcome(
            exitCode = 1, stdout = "", stderr = "Error: cannot connect to camera service",
            timedOut = false,
        )
        val (status, detail) = TurnCameraCapabilities.parseServiceList(outcome)
        assertEquals(TurnCameraCapabilities.Status.ERROR, status)
        assertTrue(detail.contains("not adjudicated"))
    }

    @Test fun permissionDeniedOutputStaysErrorDespiteKeywords() {
        val outcome = TurnCameraCapabilities.ShellOutcome(
            exitCode = 13, stdout = "avm: permission denied", stderr = "",
            timedOut = false,
        )
        val (status, _) = TurnCameraCapabilities.parseServiceList(outcome)
        assertEquals(TurnCameraCapabilities.Status.ERROR, status)
    }

    @Test fun validEmptyListIsMissingNotError() {
        val outcome = TurnCameraCapabilities.ShellOutcome(
            exitCode = 0, stdout = "Found 0 services:\n", stderr = "", timedOut = false,
        )
        val (status, detail) = TurnCameraCapabilities.parseServiceList(outcome)
        assertEquals(TurnCameraCapabilities.Status.MISSING, status)
        assertTrue(detail.contains("parsed 0 services"))
    }

    @Test fun onlyStrictlyParsedServiceNamesCount() {
        val outcome = TurnCameraCapabilities.ShellOutcome(
            exitCode = 0,
            stdout = "Found 2 services:\n1\tqcarcam_ext: [vendor.qcarcam.IExt]\n2\tactivity: [android.app.IActivityManager]\n",
            stderr = "",
            timedOut = false,
        )
        val (status, detail) = TurnCameraCapabilities.parseServiceList(outcome)
        assertEquals(TurnCameraCapabilities.Status.OK, status)
        assertEquals("qcarcam_ext", detail)
    }

    @Test fun binderInstanceSlashIsValidButMalformedAndDuplicateListingsStayUnconfirmed() {
        val base = okServiceList()
        val valid = base.copy(stdout = "Found 2 services:\n0\tandroid.hardware.camera.provider/default: [vendor.ICamera]\n1\tactivity: []\n")
        assertEquals(TurnCameraCapabilities.Status.OK, TurnCameraCapabilities.parseServiceList(valid).first)
        assertEquals("android.hardware.camera.provider/default", TurnCameraCapabilities.parseServiceList(valid).second)
        val noise = valid.copy(stdout = valid.stdout + "camera permission denied\n")
        assertEquals(TurnCameraCapabilities.Status.UNCONFIRMED, TurnCameraCapabilities.parseServiceList(noise).first)
        val duplicate = base.copy(stdout = "Found 2 services:\n0\tcamera: []\n1\tcamera: []\n")
        assertEquals(TurnCameraCapabilities.Status.UNCONFIRMED, TurnCameraCapabilities.parseServiceList(duplicate).first)
        val overflow = base.copy(stdout = "Found 99999999999999999999 services:\n")
        assertEquals(TurnCameraCapabilities.Status.UNCONFIRMED, TurnCameraCapabilities.parseServiceList(overflow).first)
    }

    class CandidateGetters {
        fun getTurnLightState(): Int = error("must never be invoked")
        fun getTurnLightFlashState(): Int = error("must never be invoked")
        fun getDoubleFlashLightState(): String = error("wrong signature must not count")
    }

    @Test fun getterSignaturesAreMetadataOnlyAndNeverGrantSupport() {
        val (status, detail) = TurnCameraCapabilities.inspectTurnGetterMetadata(CandidateGetters::class.java)
        assertEquals(TurnCameraCapabilities.Status.UNCONFIRMED, status)
        assertTrue(detail.contains("getTurnLightState,getTurnLightFlashState"))
        assertFalse(detail.contains("getDoubleFlashLightState"))
        assertTrue(detail.contains("never invoked"))
    }

    class NeverInitializeCandidate {
        companion object { init { error("candidate static initializer must not run during metadata inspection") } }
        fun getTurnLightState(): Int = error("must not invoke getter")
    }

    @Test fun metadataInspectionDoesNotRunTheCandidateStaticInitializer() {
        val type = Class.forName(NeverInitializeCandidate::class.java.name, false, NeverInitializeCandidate::class.java.classLoader)
        assertEquals(TurnCameraCapabilities.Status.UNCONFIRMED, TurnCameraCapabilities.inspectTurnGetterMetadata(type).first)
        assertThrows(ExceptionInInitializerError::class.java) { Class.forName(type.name, true, type.classLoader) }
    }

    @Test fun timeoutAndStartFailureAreErrorsNotAbsence() {
        val timedOut = TurnCameraCapabilities.ShellOutcome(null, "", "", timedOut = true)
        assertEquals(TurnCameraCapabilities.Status.ERROR, TurnCameraCapabilities.parseServiceList(timedOut).first)
        val startFailed = TurnCameraCapabilities.ShellOutcome(null, "", "", timedOut = false, startError = "IOException")
        assertEquals(TurnCameraCapabilities.Status.ERROR, TurnCameraCapabilities.parseServiceList(startFailed).first)
    }

    // ---- Rework S5: integrity of the listing; truncated output is never absence ----

    @Test fun truncatedOutputIsNotAdjudicated() {
        val outcome = TurnCameraCapabilities.ShellOutcome(
            exitCode = 0, stdout = "Found 99 services:", stderr = "", timedOut = false, truncated = true,
        )
        val (status, detail) = TurnCameraCapabilities.parseServiceList(outcome)
        assertEquals(TurnCameraCapabilities.Status.ERROR, status)
        assertTrue(detail.contains("truncated"))
    }

    @Test fun missingHeaderIsNotAdjudicated() {
        val outcome = TurnCameraCapabilities.ShellOutcome(
            exitCode = 0, stdout = "1\tcamera: [android.hardware.ICameraService]\n", stderr = "", timedOut = false,
        )
        val (status, _) = TurnCameraCapabilities.parseServiceList(outcome)
        assertEquals(TurnCameraCapabilities.Status.UNCONFIRMED, status)
    }

    @Test fun headerCountMismatchIsNotAdjudicated() {
        val outcome = TurnCameraCapabilities.ShellOutcome(
            exitCode = 0, stdout = "Found 5 services:\n1\tcamera: [android.hardware.ICameraService]\n", stderr = "", timedOut = false,
        )
        val (status, _) = TurnCameraCapabilities.parseServiceList(outcome)
        assertEquals(TurnCameraCapabilities.Status.UNCONFIRMED, status)
    }

    @Test fun readCappedStopsAtTheCapAndReportsTruncation() {
        val big = ByteArray(25) { 'x'.code.toByte() }
        val (text, truncated) = TurnCameraCapabilities.readCapped(ByteArrayInputStream(big), 10)
        assertEquals(10, text.length)
        assertTrue(truncated)
        val small = ByteArray(5) { 'y'.code.toByte() }
        val (text2, truncated2) = TurnCameraCapabilities.readCapped(ByteArrayInputStream(small), 10)
        assertEquals(5, text2.length)
        assertFalse(truncated2)
    }

    // ---- Rework S5: redaction is a claim about sensitive patterns, not encoding ----

    @Test fun redactionRemovesControlCharactersButKeepsChinese() {
        assertEquals("abcd", TurnCameraCapabilities.redact("ab\u0000cd"))
        assertEquals("仪表 display 名称", TurnCameraCapabilities.redact("仪表 display 名称"))
    }

    @Test fun redactionStripsCredentialLookingValues() {
        val redacted = TurnCameraCapabilities.redact("header auth=Bearer abc123def456 tail token: 998877")
        assertFalse(redacted.contains("abc123def456"))
        assertFalse(redacted.contains("998877"))
        assertTrue(redacted.contains("<redacted>"))
    }

    @Test fun redactionStripsVinShapedStringsAndOpaqueBlobs() {
        assertTrue(TurnCameraCapabilities.redact("VIN LGXC16DF0M0123456 here").contains("<vin>"))
        val blob = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        assertTrue(TurnCameraCapabilities.redact("sig $blob end").contains("<opaque>"))
    }

    @Test fun displayNamesSurviveRedactionAsMarkedTokens() {
        val token = TurnCameraCapabilities.displayToken("Display4_Port0_a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6")
        val line = "projection target: $token"
        val redacted = TurnCameraCapabilities.redact(line)
        assertTrue(redacted.contains(token))
        assertTrue(TurnCameraCapabilities.displayToken("x".repeat(200)).length <= 50)
        // Opaque runs outside the marks are still masked.
        assertTrue(TurnCameraCapabilities.redact("sig a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6").contains("<opaque>"))
    }

    @Test fun headerCountMismatchReportsUnparsedShapes() {
        val outcome = TurnCameraCapabilities.ShellOutcome(
            exitCode = 0,
            stdout = "Found 3 services:\n1\tcamera: [android.hardware.ICameraService]\nweird line without format\n",
            stderr = "", timedOut = false,
        )
        val (status, detail) = TurnCameraCapabilities.parseServiceList(outcome)
        assertEquals(TurnCameraCapabilities.Status.UNCONFIRMED, status)
        assertTrue(detail.contains("unparsed shapes: weird line without format"))
    }

    @Test fun redactionCapsLength() {
        assertTrue(TurnCameraCapabilities.redact("x".repeat(1_000)).length <= 401)
    }

    // ---- Rework S4: a stuck non-interruptible call cannot breed workers ----

    @Test fun stuckNonInterruptibleCallsAreBoundedAcrossRuns() {
        clearStaleCallerInterrupt()
        // Drain any worker left over from earlier tests before measuring the budget.
        var drain = 0
        while (TurnCameraCapabilities.activeProbeWorkers() > 0 && drain < 300) {
            Thread.sleep(20)
            drain++
        }
        assertEquals(0, TurnCameraCapabilities.activeProbeWorkers())

        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val stuck = TurnCameraCapabilities.ShellRunner {
            // Truly non-interruptible: interruption is swallowed until external release.
            while (!released.get()) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                }
            }
            okServiceList()
        }
        fun probe() = TurnCameraCapabilities(
            shellRunner = stuck,
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = System::nanoTime,
            deadlineMillis = 150L,
        )

        val first = probe().run(context) // worker 1 gets stuck, run returns at the deadline
        assertEquals(TurnCameraCapabilities.Status.ERROR, first.items.first { it.key == "camera_services" }.status)
        val second = probe().run(context) // worker 2 allowed: at most MAX_PROBE_WORKERS
        assertEquals(TurnCameraCapabilities.Status.ERROR, second.items.first { it.key == "camera_services" }.status)
        assertEquals(2, TurnCameraCapabilities.activeProbeWorkers())
        val third = probe().run(context) // process-wide budget exhausted → busy, no new thread
        assertTrue(third.isBusy)
        assertEquals(TurnCameraCapabilities.Status.SKIPPED, third.items.single().status)

        released.set(true)
        var tries = 0
        while (TurnCameraCapabilities.activeProbeWorkers() > 0 && tries < 300) {
            Thread.sleep(20)
            tries++
        }
        assertEquals("quarantined workers must release their slots", 0, TurnCameraCapabilities.activeProbeWorkers())
        val fourth = probe().run(context)
        assertFalse(fourth.isBusy)
    }

    // ---- Rework T2: reservations transfer exactly once; no leak without a worker ----

    @Test fun preCancelledRunsDoNotLeakTheWorkerBudget() {
        var drain = 0
        while (TurnCameraCapabilities.activeProbeWorkers() > 0 && drain < 300) {
            Thread.sleep(20)
            drain++
        }
        assertEquals(0, TurnCameraCapabilities.activeProbeWorkers())
        repeat(3) {
            val probe = TurnCameraCapabilities(
                shellRunner = { okServiceList() },
                systemProperty = { null },
                wallClock = { 0L },
                monotonicClock = { 0L },
            )
            probe.cancel() // the cancel lands before the run even starts
            val report = probe.run(context)
            assertTrue(report.cancelled)
            assertFalse(
                "Codex repro: the third pre-cancelled run must not report busy",
                report.isBusy,
            )
            assertEquals(
                "no worker may exist after a pre-cancelled run",
                0,
                TurnCameraCapabilities.activeProbeWorkers(),
            )
        }
    }

    @Test fun deadlineExpiringBeforeTheFirstItemDoesNotLeak() {
        clearStaleCallerInterrupt()
        val probe = TurnCameraCapabilities(
            shellRunner = { okServiceList() },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = System::nanoTime,
            deadlineMillis = 0L, // the deadline is gone the instant the run starts
        )
        val report = probe.run(context)
        assertTrue(report.deadlineHit)
        assertTrue(report.items.isNotEmpty())
        assertTrue(report.items.all { it.status == TurnCameraCapabilities.Status.SKIPPED })
        assertEquals(0, TurnCameraCapabilities.activeProbeWorkers())
    }

    @Test fun budgetNeverGoesNegativeOrOverTheCap() {
        // Mix: two pre-cancelled runs, one fast run, then assert bounds.
        repeat(2) {
            val probe = TurnCameraCapabilities(
                shellRunner = { okServiceList() }, systemProperty = { null },
                wallClock = { 0L }, monotonicClock = { 0L },
            )
            probe.cancel()
            probe.run(context)
        }
        val fast = TurnCameraCapabilities(
            shellRunner = { okServiceList() }, systemProperty = { null },
            wallClock = { 0L }, monotonicClock = { 0L },
        )
        val report = fast.run(context)
        assertFalse(report.isBusy)
        assertTrue(TurnCameraCapabilities.activeProbeWorkers() in 0..TurnCameraCapabilities.MAX_PROBE_WORKERS)
        var drain = 0
        while (TurnCameraCapabilities.activeProbeWorkers() > 0 && drain < 300) {
            Thread.sleep(20)
            drain++
        }
        assertEquals(0, TurnCameraCapabilities.activeProbeWorkers())
    }

    // ---- Rework R7/S4: the bounded wait primitive itself, on real processes ----

    @Test fun boundedExitWaitReturnsPromptlyAndKillsLongProcesses() {
        val sleep = ProcessBuilder("/bin/sleep", "30").start()
        val startedAt = System.nanoTime()
        val (exit, timedOut) = TurnCameraCapabilities.awaitProcessExit(sleep, 150L) { false }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("wait must be bounded, took ${elapsedMs}ms", elapsedMs < 2_000L)
        assertTrue(timedOut)
        assertNull("a destroyed child may not report an exit status", exit)
    }

    @Test fun boundedExitWaitReturnsZeroForAQuickProcess() {
        val echo = ProcessBuilder("/bin/echo", "probe").start()
        val (exit, timedOut) = TurnCameraCapabilities.awaitProcessExit(echo, 3_000L) { false }
        assertEquals(0, exit)
        assertTrue(!timedOut)
    }

    @Test fun boundedExitWaitHonoursCancellation() {
        val sleep = ProcessBuilder("/bin/sleep", "30").start()
        val startedAt = System.nanoTime()
        val (exit, timedOut) = TurnCameraCapabilities.awaitProcessExit(sleep, 30_000L) { true }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        assertTrue("cancellation must not wait for the timeout, took ${elapsedMs}ms", elapsedMs < 2_000L)
        assertTrue(!timedOut)
    }

    @Test fun interruptingTheWaitingThreadStillCleansUpTheChild() {
        val sleep = ProcessBuilder("/bin/sleep", "30").start()
        val done = CountDownLatch(1)
        val result = arrayOfNulls<Pair<Int?, Boolean>>(1)
        val waiter = Thread {
            result[0] = TurnCameraCapabilities.awaitProcessExit(sleep, 30_000L) { false }
            done.countDown()
        }.apply { isDaemon = true; start() }
        Thread.sleep(150)
        waiter.interrupt()
        assertTrue("interrupt must unblock the wait", done.await(3, TimeUnit.SECONDS))
        var exited = false
        repeat(100) {
            try {
                sleep.exitValue()
                exited = true
                return@repeat
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(20)
            }
        }
        assertTrue("the interrupted wait must have destroyed the child", exited)
    }

    @Test fun clockThrowingBeforeTheExecutorReleasesItsReservation() {
        clearStaleCallerInterrupt()
        // Rework T2/V1: a clock throwing between the reservation and the executor creation
        // used to leak the slot permanently (no worker existed to release it).
        var drain = 0
        while (TurnCameraCapabilities.activeProbeWorkers() > 0 && drain < 300) {
            Thread.sleep(20)
            drain++
        }
        assertEquals(0, TurnCameraCapabilities.activeProbeWorkers())
        val probe = TurnCameraCapabilities(
            shellRunner = { okServiceList() },
            systemProperty = { null },
            wallClock = { throw IllegalStateException("clock broken") },
            monotonicClock = System::nanoTime,
        )
        runCatching { probe.run(context) }
        assertEquals("a run whose setup throws must give its slot back", 0, TurnCameraCapabilities.activeProbeWorkers())
        val healthy = TurnCameraCapabilities(
            shellRunner = { okServiceList() }, systemProperty = { null },
            wallClock = { 0L }, monotonicClock = { 0L },
        )
        assertFalse(healthy.run(context).isBusy)
    }

    // ---- Rework R7: same probe behaviour across the supported SDK floor ----

    @Test
    @Config(sdk = [25])
    fun probeRunsOnApi25() = probeSmoke()

    @Test
    @Config(sdk = [26])
    fun probeRunsOnApi26() = probeSmoke()

    @Test
    @Config(sdk = [29])
    fun probeRunsOnApi29() = probeSmoke()

    private fun probeSmoke() {
        val probe = TurnCameraCapabilities(
            shellRunner = { okServiceList() },
            systemProperty = { null },
            wallClock = { 0L },
            monotonicClock = { 0L },
        )
        val report = probe.run(context)
        assertTrue(report.items.isNotEmpty())
        assertEquals(TurnCameraCapabilities.Status.OK, report.items.first { it.key == "camera_services" }.status)
    }
}
