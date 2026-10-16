package com.shilapi.xcertplay.turncamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.view.Display
import com.shilapi.xcertplay.ClusterMapPresentation
import com.shilapi.xcertplay.setup.DiLinkGeneration
import java.io.File
import java.io.InputStream
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Read-only compatibility probe for the turn side-camera feature (plan §5 phase A).
 *
 * Everything it does is evidence gathering only: it never opens a camera, loads a native
 * library, starts the OEM AVM, requests a permission or changes any setting (mayAsk=false).
 * Every camera finding is a hint, not a support guarantee; [ProbeItem.Status.OK] on a class
 * or file only means "visible from this app", never "streaming works on this car".
 *
 * Timing rules (Rework R5): the deadline and duration use an injectable monotonic clock;
 * the wall clock is only rendered into the report date, so clock jumps cannot extend a
 * run. Each item runs on a single-thread executor and is joined with the remaining
 * deadline, so [run] always returns within the deadline even if an underlying call blocks
 * forever; such a call is honestly reported as left to finish, never as terminated.
 *
 * Resource bound (Rework S4): probe workers are counted process-wide. A run whose worker
 * is stuck in a non-interruptible call keeps its slot until the worker thread finally
 * exits (quarantine); while [MAX_PROBE_WORKERS] slots are taken, further runs return a
 * busy report instead of piling up threads. Cancelling or restarting an Activity cannot
 * bypass this bound.
 *
 * The turn-signal source is deliberately reported as unconfirmed: the researched BYD Extend
 * addresses were only validated on another model (plan §2) and must not be copied as fact.
 */
class TurnCameraCapabilities(
    /** Null selects the real whitelisted runner wired to this probe's cancelled flag. */
    private val shellRunner: ShellRunner? = null,
    private val systemProperty: (String) -> String? = ::readSystemProperty,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = System::nanoTime,
    private val deadlineMillis: Long = 8_000L,
) {
    enum class Status { OK, MISSING, UNCONFIRMED, ERROR, SKIPPED }

    data class ProbeItem(val key: String, val status: Status, val detail: String)

    /**
     * Structured shell outcome (Rework R6/S5): presence is only ever adjudicated from a
     * zero exit, a complete (untruncated) output and strictly parsed lines — never from
     * error text or a missing keyword. [truncated] marks output cut at the read cap.
     */
    data class ShellOutcome(
        /** Null when the process failed to start or never exited within bounds. */
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
        val startError: String? = null,
        val truncated: Boolean = false,
    )

    fun interface ShellRunner {
        fun run(argv: List<String>): ShellOutcome

        companion object {
            /**
             * Real runner: whitelisted argv, both pipes consumed while waiting via
             * [readCapped] (so a chatty child cannot deadlock or silently overflow us),
             * and an API-25-safe bounded exit poll that cleans the process up on timeout,
             * cancellation AND thread interruption (Rework R5/R7/S4).
             */
            fun system(
                cancelled: () -> Boolean = { false },
                timeoutMillis: Long = 3_000L,
            ): ShellRunner = ShellRunner { argv ->
                require(argv in SHELL_WHITELIST) { "command not whitelisted: $argv" }
                val process = try {
                    ProcessBuilder(argv).start()
                } catch (t: Throwable) {
                    return@ShellRunner ShellOutcome(
                        exitCode = null, stdout = "", stderr = "",
                        timedOut = false, startError = t.javaClass.simpleName,
                    )
                }
                // Per-call results; both pipes are consumed while the child runs.
                val results = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()
                val readers = listOf("out" to process.inputStream, "err" to process.errorStream)
                    .map { (tag, stream) ->
                        Thread { results[tag] = readCapped(stream, MAX_STREAM_BYTES) }
                            .apply { isDaemon = true; start() }
                    }
                val (exit, timedOut) = awaitProcessExit(process, timeoutMillis, cancelled)
                readers.forEach { it.join(500) }
                val stdoutResult = results["out"] ?: ("" to false)
                val stderrResult = results["err"] ?: ("" to false)
                ShellOutcome(
                    exitCode = exit,
                    stdout = stdoutResult.first,
                    stderr = stderrResult.first,
                    timedOut = timedOut,
                    truncated = stdoutResult.second || stderrResult.second,
                )
            }
        }
    }

    data class Report(
        /** Wall-clock timestamp, rendered as the report date only. */
        val startedAtWallMillis: Long,
        /** Monotonic duration; immune to wall-clock jumps. */
        val durationMillis: Long,
        val deadlineHit: Boolean,
        val cancelled: Boolean,
        val items: List<ProbeItem>,
    ) {
        val isBusy: Boolean get() = items.any { it.key == BUSY_ITEM_KEY }

        fun summaryLine(): String = buildString {
            append("hints=${items.count { it.status == Status.OK }}")
            append(" missing=${items.count { it.status == Status.MISSING }}")
            append(" unconfirmed=${items.count { it.status == Status.UNCONFIRMED }}")
            append(" error=${items.count { it.status == Status.ERROR }}")
            append(" skipped=${items.count { it.status == Status.SKIPPED }}")
            if (deadlineHit) append(" deadline-hit")
            if (cancelled) append(" cancelled")
        }

        fun toReportText(): String = buildString {
            appendLine("DiPlay turn camera probe (read-only, phase A)")
            appendLine(
                "generated: ${DateFormat.getDateTimeInstance().format(Date(startedAtWallMillis))}" +
                    " duration: ${durationMillis}ms deadline-hit: $deadlineHit cancelled: $cancelled",
            )
            appendLine("All camera findings are presence hints; nothing is verified on this car.")
            appendLine()
            for (item in items) {
                appendLine("${item.key}: ${item.status}${if (item.detail.isEmpty()) "" else " — ${item.detail}"}")
            }
            appendLine()
            appendLine("turn_signal_source: UNCONFIRMED — no getter/device/FID/value semantics are")
            appendLine("  verified for this firmware. Research only (other model, BYD Extend commit")
            appendLine("  7f0c4c392dc7fa27ad5ddaf49078462fd4d261df, AGPL-3.0): stalk device 1004")
            appendLine("  FID 321912876, blinker device 1004 FID 950009900; left/right/hazard value")
            appendLine("  encoding unpublished. Do not treat these as this car's configuration.")
            appendLine("  Additional static research: BYDAutoLightDevice getTurnLightState(),")
            appendLine("  getTurnLightFlashState(), getDoubleFlashLightState(). Metadata only;")
            appendLine("  no getter is invoked and no value encoding is assumed.")
            appendLine("camera_paths_researched: vendor AVMCamera reflection (BYD Extend); Qualcomm")
            appendLine("  AIS /vendor/lib64/libais_client.so + qcarcam_* (OverDrive DILINK5 doc).")
            appendLine("This report proves no streaming capability. Phase B needs on-car evidence:")
            appendLine("  confirmed turn-signal getter and values, left/right camera identity, safe")
            appendLine("  cluster area, and OEM reverse/360 coexistence.")
        }
    }

    @Volatile private var cancelled = false

    /** Cooperative cancel from the UI; bounded to ~CANCEL_POLL_MILLIS by the item loop. */
    fun cancel() {
        cancelled = true
    }

    /** Test visibility for the cancellation contract. */
    fun isCancelled(): Boolean = cancelled

    /** Runs on the caller's background thread; returns within the deadline; nothing is uploaded. */
    fun run(context: Context): Report {
        // Process-wide bound: at most MAX_PROBE_WORKERS probe threads may exist, however
        // often the user retries or the Activity is recreated (Rework S4). Ownership is
        // transferred exactly once (Rework T2): a run that never starts a worker (already
        // cancelled, deadline gone, or submission rejected) releases its reservation
        // itself; a worker that did start releases from its thread's finally block — so a
        // genuinely stuck call keeps its slot until the thread really dies, and nothing
        // else can leak or double-release.
        if (liveProbeWorkers.incrementAndGet() > MAX_PROBE_WORKERS) {
            liveProbeWorkers.decrementAndGet()
            return Report(
                startedAtWallMillis = wallClock(),
                durationMillis = 0L,
                deadlineHit = false,
                cancelled = false,
                items = listOf(
                    ProbeItem(
                        BUSY_ITEM_KEY, Status.SKIPPED,
                        "another probe is still finishing a stuck step; try again in a moment",
                    ),
                ),
            )
        }
        val reservation = java.util.concurrent.atomic.AtomicBoolean(false)
        fun releaseReservationOnce() {
            if (reservation.compareAndSet(false, true)) liveProbeWorkers.decrementAndGet()
        }
        val workerRef = java.util.concurrent.atomic.AtomicReference<Thread?>(null)
        val appContext = context.applicationContext
        // Everything between the reservation and the executor creation can throw (clocks,
        // context, factory); with no worker started yet, THIS run must give the slot back
        // (Rework T2/V1: a clock throwing here used to leak the reservation permanently).
        val startedWall: Long
        val startedMono: Long
        val deadlineMono: Long
        val shell: ShellRunner
        val executor: java.util.concurrent.ExecutorService
        try {
            startedWall = wallClock()
            startedMono = monotonicClock()
            deadlineMono = startedMono + deadlineMillis * 1_000_000L
            shell = shellRunner ?: ShellRunner.system(cancelled = { cancelled })
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread({
                    try {
                        runnable.run()
                    } finally {
                        // The worker owns the reservation from its first instruction to its death.
                        releaseReservationOnce()
                    }
                }, "turn-camera-probe").apply {
                    isDaemon = true
                    workerRef.set(this)
                }
            }
        } catch (t: Throwable) {
            releaseReservationOnce()
            throw t
        }
        val items = mutableListOf<ProbeItem>()
        try {
            // Nanosecond precision (Rework D1): flooring a positive sub-millisecond
            // remainder to 0ms used to mark items expired while the report still compared
            // against the true deadline — items claimed expiry with deadlineHit=false.
            fun remainingNanos(): Long = deadlineMono - monotonicClock()

            fun item(key: String, probe: () -> Pair<Status, String>) {
                if (cancelled || remainingNanos() <= 0L) {
                    items += ProbeItem(key, Status.SKIPPED, if (cancelled) "cancelled" else "deadline reached")
                    return
                }
                val future = executor.submit(Callable { probe() })
                var result: ProbeItem? = null
                while (result == null) {
                    val sliceNanos = minOf(remainingNanos(), CANCEL_POLL_MILLIS * 1_000_000L)
                    if (sliceNanos <= 0L) {
                        future.cancel(true)
                        result = ProbeItem(
                            key, Status.ERROR,
                            "item exceeded the remaining probe deadline; the underlying call is not interruptible and was left to finish",
                        )
                        continue
                    }
                    result = try {
                        val (status, detail) = future.get(sliceNanos, TimeUnit.NANOSECONDS)
                        ProbeItem(key, status, redactDetail(key, detail))
                    } catch (_: TimeoutException) {
                        if (cancelled) {
                            future.cancel(true)
                            ProbeItem(key, Status.SKIPPED, "cancelled")
                        } else {
                            null // keep polling until the real deadline
                        }
                    } catch (e: ExecutionException) {
                        val cause = e.cause ?: e
                        ProbeItem(key, Status.ERROR, redact(cause.javaClass.simpleName + ": " + (cause.message ?: "")))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        future.cancel(true)
                        ProbeItem(key, Status.SKIPPED, "cancelled")
                    }
                }
                items += result
            }

            item("firmware_build") {
                val controller = systemProperty(CONTROLLER_VERSION_PROPERTY)?.trim().orEmpty()
                val detection = DiLinkGeneration.detect()
                Pair(
                    Status.OK,
                    "display=${Build.DISPLAY} product=${Build.PRODUCT} board=${Build.BOARD} " +
                        "sdk=${Build.VERSION.SDK_INT} controller=$controller " +
                        "dilink=${detection.generation} (${detection.source})",
                )
            }
            item("cluster_display") {
                val displays = displaySummary(appContext)
                val chosen = ClusterMapPresentation.findDisplay(appContext)
                Pair(
                    if (displays.isEmpty()) Status.UNCONFIRMED else Status.OK,
                    displays.joinToString("; ").ifEmpty { "no presentation displays visible to this app" } +
                        (chosen?.let { " · projection target: ${it.name}" } ?: " · projection target: none"),
                )
            }
            item("camera_permission") {
                val granted = appContext.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                // Not requested here (mayAsk=false); recording only.
                Pair(if (granted) Status.OK else Status.MISSING, "CAMERA granted=$granted (not requested)")
            }
            item("camera2_ids") {
                // Enumerates ids and static metadata only; opening any camera is phase B.
                val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val ids = manager.cameraIdList
                val described = ids.take(16).joinToString("\n") { id ->
                    try {
                        val characteristics = manager.getCameraCharacteristics(id)
                        val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                            CameraCharacteristics.LENS_FACING_FRONT -> "front"
                            CameraCharacteristics.LENS_FACING_BACK -> "back"
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                            else -> "unknown-facing"
                        }
                        val level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)?.toString() ?: "?"
                        val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                        val pixels = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                        val zoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        val fps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                            ?.take(8)?.joinToString { "${it.lower}-${it.upper}" }
                        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                        val physical = if (Build.VERSION.SDK_INT >= 28) {
                            characteristics.physicalCameraIds.take(16).joinToString { it.take(128) }
                        } else null
                        "${id.take(128)}=$facing (hardware-level=$level; not a camera count or side identity); " +
                            "sensor-orientation=${orientation ?: "?"}; pixels=${pixels ?: "?"}; " +
                            "max-digital-zoom=${zoom ?: "?"}; AE-fps-ranges=${fps ?: "?"}; " +
                            "SurfaceTexture-sizes=${sizes?.take(12)?.joinToString() ?: "?"} " +
                            "(${sizes?.size ?: 0} listed); physical-ids=${physical ?: "?"}"
                    } catch (e: Exception) {
                        // One inaccessible ID must not hide other visible cameras. Do not
                        // echo arbitrary vendor exception messages into an exportable report.
                        "${id.take(128)}: metadata unconfirmed (${e.javaClass.simpleName})"
                    }
                }
                // An empty list is what this app can see, not proof the car has no cameras.
                Pair(
                    if (ids.isEmpty()) Status.UNCONFIRMED else Status.OK,
                    "${ids.size} visible ids; at most 16 ids / 12 sizes / 8 fps ranges per id, " +
                        "report text capped at 8000 characters. " +
                        "Static metadata only, not measured FPS or verified vendor AVM side identities.\n" +
                        described.ifEmpty { "no ids visible to this app" },
                )
            }
            for (name in VENDOR_CLASSES) {
                item("vendor_class:$name") {
                    // initialize=false: presence only, no static initializer may run (plan §5).
                    // Not loadable from THIS app's ClassLoader is unconfirmed, not absent.
                    val present = try {
                        Class.forName(name, false, TurnCameraCapabilities::class.java.classLoader)
                        true
                    } catch (_: ClassNotFoundException) {
                        false
                    }
                    Pair(
                        if (present) Status.OK else Status.UNCONFIRMED,
                        if (present) "loadable (hint)" else "not loadable from this app's ClassLoader; presence unconfirmed",
                    )
                }
            }
            item("turn_signal_getter_metadata") {
                val type = try {
                    Class.forName(LIGHT_DEVICE_CLASS, false, TurnCameraCapabilities::class.java.classLoader)
                } catch (_: ClassNotFoundException) {
                    return@item Status.UNCONFIRMED to "candidate class not visible; getters and values unconfirmed"
                }
                inspectTurnGetterMetadata(type)
            }
            for (path in AIS_FILES) {
                item("vendor_file:$path") {
                    val present = File(path).isFile
                    Pair(
                        if (present) Status.OK else Status.UNCONFIRMED,
                        if (present) "present (hint)" else "not visible to this app (permission or absence); unconfirmed",
                    )
                }
            }
            item("oem_avm_package") {
                val present = try {
                    appContext.packageManager.getPackageInfo(OEM_AVM_PACKAGE, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
                // API 30+ package visibility: an invisible package is unconfirmed, not absent.
                Pair(
                    if (present) Status.OK else Status.UNCONFIRMED,
                    "$OEM_AVM_PACKAGE visible=$present (absence unconfirmed under package visibility; never started)",
                )
            }
            item("camera_services") {
                parseServiceList(shell.run(listOf("service", "list")))
            }
        } finally {
            executor.shutdownNow() // best effort; a quarantined worker releases its slot when it exits
            val worker = workerRef.get()
            if (worker == null || !worker.isAlive) {
                // No worker was ever created (pre-cancelled run, deadline gone before the
                // first item, rejected submissions) or it already finished: this run owns
                // the reservation and releases it exactly once.
                releaseReservationOnce()
            }
        }

        return Report(
            startedAtWallMillis = startedWall,
            durationMillis = (monotonicClock() - startedMono) / 1_000_000L,
            deadlineHit = monotonicClock() >= deadlineMono,
            cancelled = cancelled,
            items = items,
        )
    }

    private fun displaySummary(context: Context): List<String> =
        context.getSystemService(Context.DISPLAY_SERVICE)?.let { service ->
            (service as android.hardware.display.DisplayManager).getDisplays("").map { display: Display ->
                val size = ClusterMapPresentation.sizeOf(display)
                "${display.name} ${size.x}x${size.y} (displayId=${display.displayId})"
            }
        } ?: emptyList()

    companion object {
        /** Exact argv allowlist; anything else throws instead of running. */
        val SHELL_WHITELIST: Set<List<String>> = setOf(listOf("service", "list"))
        private const val CANCEL_POLL_MILLIS = 100L
        private const val MAX_STREAM_BYTES = 64 * 1_024
        private const val REDACT_MAX = 400
        private val WHITELISTED_PROPERTIES = setOf("apps.setting.product.outswver")
        const val BUSY_ITEM_KEY = "probe_busy"

        /** Process-wide probe worker budget and its current occupancy (Rework S4). */
        const val MAX_PROBE_WORKERS = 2
        private val liveProbeWorkers = AtomicInteger(0)
        fun activeProbeWorkers(): Int = liveProbeWorkers.get()

        private const val LIGHT_DEVICE_CLASS = "android.hardware.bydauto.light.BYDAutoLightDevice"
        private val TURN_GETTER_NAMES = listOf("getTurnLightState", "getTurnLightFlashState", "getDoubleFlashLightState")

        /** Static metadata only: never obtains a device, invokes a getter, or registers a listener. */
        internal fun inspectTurnGetterMetadata(type: Class<*>): Pair<Status, String> {
            val visible = TURN_GETTER_NAMES.filter { name ->
                try {
                    type.getMethod(name).returnType == Int::class.javaPrimitiveType
                } catch (_: NoSuchMethodException) {
                    false
                }
            }
            return Status.UNCONFIRMED to (
                "public int getters visible=${visible.joinToString(",").ifEmpty { "none" }}; " +
                    "metadata only, never invoked; firmware values and semantics unconfirmed"
                )
        }

        /** Presence candidates: BYD Extend camera names and user-supplied APK light metadata. */
        val VENDOR_CLASSES = listOf(
            "android.hardware.AVMCamera",
            "android.hardware.BmmCameraInfo",
            "com.ts.avm.AvmAndroidService",
            LIGHT_DEVICE_CLASS,
        )

        /** Qualcomm AIS path names from the OverDrive DiLink 5 capture discovery doc. */
        val AIS_FILES = listOf(
            "/vendor/lib64/libais_client.so",
            "/vendor/bin/qcarcam_test",
            "/vendor/bin/1cam.xml",
        )

        private const val OEM_AVM_PACKAGE = "com.byd.avm"
        private const val CONTROLLER_VERSION_PROPERTY = "apps.setting.product.outswver"

        /** `service list` line format: `42\tname: [interface]`; header `Found N services:`. */
        // Binder instance names may contain '/', e.g. android.hardware.camera.provider/legacy.
        private val SERVICE_LINE = Regex("""\d+\s+([\w.$/-]+):\s*\[.*?]""")
        private val SERVICE_HEADER = Regex("""Found (\d+) services:?""")

        /**
         * Presence is only adjudicated from exit 0, an untruncated output, a matching
         * `Found N services:` header and strictly parsed names. Anything else — failures,
         * timeouts, truncation, malformed or header-mismatched output — stays ERROR or
         * UNCONFIRMED ("not adjudicated"), so error text containing "camera" can never be
         * counted as a found service, and a broken listing can never be read as absence
         * (Rework R6/S5).
         */
        internal fun parseServiceList(outcome: ShellOutcome): Pair<Status, String> {
            if (outcome.startError != null) {
                return Status.ERROR to "failed to start (${outcome.startError}); presence not adjudicated"
            }
            if (outcome.timedOut) return Status.ERROR to "timed out; presence not adjudicated"
            if (outcome.truncated) return Status.ERROR to "output truncated at the read cap; presence not adjudicated"
            val code = outcome.exitCode
                ?: return Status.ERROR to "no exit status; presence not adjudicated"
            if (code != 0) return Status.ERROR to "exit=$code; presence not adjudicated"
            val lines = outcome.stdout.lineSequence().filter { it.isNotBlank() }.toList()
            val header = lines.firstOrNull()?.let { SERVICE_HEADER.matchEntire(it.trim()) }
            if (header == null) {
                return Status.UNCONFIRMED to "unexpected output format; presence not adjudicated"
            }
            val expected = header.groupValues[1].toIntOrNull()
                ?: return Status.UNCONFIRMED to "invalid service count; presence not adjudicated"
            val services = lines.drop(1)
                .mapNotNull { line -> SERVICE_LINE.matchEntire(line.trim())?.groupValues?.get(1) }
            if (services.size != expected) {
                return Status.UNCONFIRMED to "header lists $expected services but ${services.size} parsed; not adjudicated"
            }
            if (lines.size - 1 != expected || services.distinct().size != services.size) {
                return Status.UNCONFIRMED to "malformed or duplicate service listing; presence not adjudicated"
            }
            val hits = services.filter { name ->
                listOf("camera", "avm", "ais", "qcarcam", "bmm").any { it in name.lowercase() }
            }
            return if (hits.isEmpty()) {
                Status.MISSING to "parsed ${services.size} services, none camera/avm-related"
            } else {
                Status.OK to hits.joinToString(" | ")
            }
        }

        /**
         * Sanitizes text that may reach a copyable report: strips control characters,
         * removes credential-looking values, VIN-shaped strings and long opaque blobs,
         * and caps the length. Normal non-ASCII text (Chinese display names) is preserved
         * — "redacted" here is a claim about sensitive patterns, not about encoding
         * (Rework S5).
         */
        // Metadata lists need more room than ordinary shell/property snippets. Keep the
        // same sanitizer and a separate fixed bound, including when saving a report again.
        internal fun redactDetail(key: String, text: String): String =
            redact(text, if (key == "camera2_ids") 8_000 else REDACT_MAX)

        internal fun redact(text: String): String = redact(text, REDACT_MAX)

        private fun redact(text: String, maxChars: Int): String {
            var cleaned = text.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")
            cleaned = Regex("(?i)\\b(token|secret|password|passwd|authorization|auth|key|session)[=: ]+(?:(bearer|basic|mac)\\s+)?\\S+")
                .replace(cleaned) { it.groupValues[1] + "=<redacted>" }
            cleaned = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b").replace(cleaned, "<vin>")
            cleaned = Regex("[A-Za-z0-9+/=_-]{32,}").replace(cleaned, "<opaque>")
            return if (cleaned.length > maxChars) cleaned.take(maxChars) + "…" else cleaned
        }

        /**
         * Reads at most [maxBytes] bytes and reports whether the cap was hit, so callers
         * can refuse to adjudicate on truncated data. Consumes while the child runs.
         */
        internal fun readCapped(stream: InputStream, maxBytes: Int): Pair<String, Boolean> {
            val buffer = StringBuilder()
            val chunk = ByteArray(4_096)
            var total = 0
            var truncated = false
            while (true) {
                val read = try {
                    stream.read(chunk)
                } catch (_: java.io.IOException) {
                    -1
                }
                if (read < 0) break
                val keep = if (total + read > maxBytes) {
                    truncated = true
                    maxBytes - total
                } else read
                if (keep > 0) buffer.append(String(chunk, 0, keep, Charsets.UTF_8))
                total += read
                if (truncated) break
            }
            runCatching { stream.close() }
            return buffer.toString() to truncated
        }

        /**
         * API-25-safe bounded exit wait (Rework R7/S4): poll [Process.exitValue] instead
         * of the API-26 `waitFor(timeout)`; destroy() on timeout, cancellation AND
         * interruption, so an interrupted probe worker still cleans up its child.
         * Returns (exitCode, timedOut); exitCode is null if the process never exited.
         */
        internal fun awaitProcessExit(
            process: Process,
            timeoutMillis: Long,
            cancelled: () -> Boolean,
        ): Pair<Int?, Boolean> {
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (true) {
                val exit = try {
                    process.exitValue()
                } catch (_: IllegalThreadStateException) {
                    null
                }
                if (exit != null) return exit to false
                if (cancelled()) {
                    process.destroy()
                    return finalExit(process) to false
                }
                if (System.nanoTime() >= deadline) {
                    process.destroy()
                    return finalExit(process) to true
                }
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // Interrupted (e.g. a future cancelled while the call is stuck): restore
                    // the flag, still clean the child up, and report an unclaimed exit.
                    Thread.currentThread().interrupt()
                    process.destroy()
                    return finalExit(process) to false
                }
            }
        }

        private fun finalExit(process: Process): Int? =
            try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                null
            }

        /** Hidden-API read via reflection; only whitelisted keys are ever requested. */
        fun readSystemProperty(key: String): String? {
            if (key !in WHITELISTED_PROPERTIES) return null
            return runCatching {
                val cls = Class.forName("android.os.SystemProperties")
                val method = cls.getMethod("get", String::class.java, String::class.java)
                method.invoke(null, key, "")?.toString()
            }.getOrNull()?.trim().takeIf { !it.isNullOrEmpty() }
        }
    }
}
