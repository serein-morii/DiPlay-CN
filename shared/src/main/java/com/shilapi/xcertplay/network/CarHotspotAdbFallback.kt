package com.shilapi.xcertplay.network

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Uses only a firmware-advertised command for the saved hotspot; never configures a replacement AP. */
internal object CarHotspotAdbFallback {
    private const val EXIT = "DIPLAY_HOTSPOT_EXIT:"

    fun start(deadline: Long, cancelled: () -> Boolean, state: () -> Boolean?,
        shell: (String, Int) -> String?, log: (String) -> Unit): Boolean {
        fun remaining() = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtMost(30_000).toInt()
        fun stopped() = cancelled() || Thread.currentThread().isInterrupted || remaining() <= 0
        fun read(command: String): String? = if (stopped()) null else shell(command, remaining().coerceAtLeast(1))
        fun observed(): Boolean? = state() ?: apState(read("dumpsys wifi"))
        if (stopped()) return false
        // No blind mutation when no AP state can be observed.
        val initial = observed() ?: return false.also { log("car hotspot: ADB cannot read the AP state; not starting it") }
        if (stopped()) return false
        if (initial) return true
        var command: String? = null
        for (service in listOf("connectivity", "tethering")) {
            command = advertisedCommand(service, read("cmd $service help"))
            if (command != null) break
        }
        if (stopped()) return false
        if (command == null) {
            log("car hotspot: firmware lists no hotspot start command in cmd connectivity/tethering help")
            return false
        }
        val output = read("$command 2>&1; printf '\\n$EXIT%s\\n' \"\$?\"")
        if (stopped()) return false
        if (!accepted(output)) {
            log("car hotspot: firmware rejected '$command': " +
                output.orEmpty().lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty().take(120))
            return false
        }
        log("car hotspot: firmware ADB start request accepted; waiting for AP enabled state")
        while (!stopped()) {
            if (observed() == true && !stopped()) {
                log("car hotspot: AP enabled state confirmed after local ADB request")
                return true
            }
            try {
                Thread.sleep(minOf(250, remaining().coerceAtLeast(1)).toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    /** The PR's command forms are vendor extensions, not generic Android commands. */
    internal fun advertisedCommand(service: String, help: String?): String? {
        if (service !in listOf("connectivity", "tethering") || help == null || rejected(help)) return null
        for (line in help.lineSequence()) {
            val form = Regex("^\\s*(start-tethering|start)\\s+(wifi|[<\\[(][^>\\])]*\\bwifi\\b[^>\\])]*[>\\])])(?:\\s*\\[[^\\n]*])?\\s*$")
                .matchEntire(line) ?: continue
            val verb = form.groupValues[1]
            if (service == "connectivity" && verb != "start-tethering") continue
            return "cmd $service $verb wifi"
        }
        return null
    }

    internal fun accepted(output: String?): Boolean = output != null && !rejected(output) &&
        Regex("(?m)^$EXIT(\\d+)\\s*$").findAll(output).lastOrNull()?.groupValues?.get(1) == "0"

    private fun rejected(output: String): Boolean = Regex(
        "unknown command|permission denied|securityexception|(?:^|\\n)\\s*error:|failed|can't find service|not found|not supported|unsupported",
        RegexOption.IGNORE_CASE).containsMatchIn(output)

    /** Read current AP fields only, never a past log, station Wi-Fi state or an interface address. */
    internal fun apState(dump: String?): Boolean? {
        if (dump == null) return null
        val managers = dump.split(Regex("(?m)^\\s*(?:--)?Dump of SoftApManager(?:--| id=\\S+)?\\s*$")).drop(1)
        if (managers.isEmpty()) {
            val numeric = Regex("(?m)^\\s*(?:mWifiApState|mSoftApState|mTetheredSoftApState)\\s*[:=]\\s*(\\d+)\\s*$")
                .findAll(dump).map { CarHotspotStatus.stateEnabled(it.groupValues[1].toIntOrNull()) }.toList()
            return if (numeric.any { it == null }) null else numeric.distinct().singleOrNull()
        }
        val tethered = managers.filter { block ->
            val mode = Regex("(?m)^\\s*(?:mMode|mOriginalModeConfiguration\\.targetMode):\\s*(\\d+)\\s*$").find(block)?.groupValues?.get(1)
            mode == "1"
        }
        val states = tethered.map { block ->
            val current = Regex("(?m)^\\s*current StateMachine mode:\\s*(\\w+)\\s*$").find(block)?.groupValues?.get(1)
            val up = Regex("(?m)^\\s*mIfaceIsUp:\\s*(true|false)\\s*$").find(block)?.groupValues?.get(1)
            when {
                current == "StartedState" && up == "true" -> true
                current == "IdleState" && up == "false" -> false
                else -> null
            }
        }
        return if (states.any { it == null }) null else states.distinct().singleOrNull()
    }

    /** Deadline includes connection, capability reads, writes and AP-state polling. Cancellation aborts I/O. */
    internal fun bounded(deadline: Long, cancelled: () -> Boolean, abort: () -> Unit, action: () -> Boolean): Boolean {
        if (cancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadline) return false
        val task = FutureTask(action)
        Thread(task, "diplay-hotspot-adb").apply { isDaemon = true; start() }
        try {
            while (!cancelled() && !Thread.currentThread().isInterrupted) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    return task.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) { }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: java.util.concurrent.ExecutionException) { }
        finally {
            if (!task.isDone) { abort(); task.cancel(true) }
        }
        return false
    }
}
