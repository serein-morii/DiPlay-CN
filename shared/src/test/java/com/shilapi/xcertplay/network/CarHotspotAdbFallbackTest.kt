package com.shilapi.xcertplay.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class CarHotspotAdbFallbackTest {
    private fun deadline(ms: Long = 1_000) = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)

    @Test fun genericAospHelpNeverTriggersTheGuessedCommands() {
        assertNull(CarHotspotAdbFallback.advertisedCommand("connectivity", "Connectivity service commands:\n  help\n  airplane-mode [enable|disable]"))
        assertNull(CarHotspotAdbFallback.advertisedCommand("tethering", "Unknown command: help"))
        assertNull(CarHotspotAdbFallback.advertisedCommand("wifi", "  start-softap <ssid> open"))
    }

    @Test fun advertisedFirmwareFormsAreSelectedWithoutProbingMutatingCommands() {
        assertEquals("cmd connectivity start-tethering wifi", CarHotspotAdbFallback.advertisedCommand("connectivity", "  start-tethering wifi"))
        assertEquals("cmd tethering start-tethering wifi", CarHotspotAdbFallback.advertisedCommand("tethering", "  start-tethering <wifi|usb|bluetooth>"))
        assertEquals("cmd tethering start wifi", CarHotspotAdbFallback.advertisedCommand("tethering", "  start wifi [options]"))
        assertNull(CarHotspotAdbFallback.advertisedCommand("tethering", "Example: start wifi\n  start <type>"))
    }

    @Test fun aZeroExitCodeCannotHidePermissionOrUnknownCommandOutput() {
        assertTrue(CarHotspotAdbFallback.accepted("Started\nDIPLAY_HOTSPOT_EXIT:0"))
        for (output in listOf(null, "", "DIPLAY_HOTSPOT_EXIT:1", "Unknown command\nDIPLAY_HOTSPOT_EXIT:0", "SecurityException\nDIPLAY_HOTSPOT_EXIT:0")) {
            assertFalse(CarHotspotAdbFallback.accepted(output))
        }
    }

    @Test fun shellAcceptanceStillWaitsForAnActualApState() {
        var on = false
        val commands = mutableListOf<String>()
        assertTrue(CarHotspotAdbFallback.start(deadline(), { false }, { on }, { command, _ ->
            commands += command
            if (command.endsWith("help")) "  start-tethering wifi" else {
                on = true
                "DIPLAY_HOTSPOT_EXIT:0"
            }
        }, {}))
        assertEquals(2, commands.size)
        assertFalse(commands.last().contains("||"))
        assertTrue(commands.last().contains("\$?"))
        assertFalse(CarHotspotAdbFallback.start(deadline(20), { false }, { false }, { command, _ ->
            if (command.endsWith("help")) "  start-tethering wifi" else "DIPLAY_HOTSPOT_EXIT:0"
        }, {}))
    }

    @Test fun noStatusOrCapabilityMeansNoMutation() {
        val commands = mutableListOf<String>()
        assertFalse(CarHotspotAdbFallback.start(deadline(), { false }, { null }, { command, _ -> commands += command; null }, {}))
        assertEquals(listOf("dumpsys wifi"), commands)
        commands.clear()
        assertFalse(CarHotspotAdbFallback.start(deadline(), { false }, { false }, { command, _ -> commands += command; "  help" }, {}))
        assertTrue(commands.all { it.endsWith("help") })
    }

    @Test fun theReportSaysWhyTheAdbPathStopped() {
        // #616: an Android 10 DiLink 3.0 failed within 90 ms and the report did not say which step stopped.
        val logs = mutableListOf<String>()
        CarHotspotAdbFallback.start(deadline(), { false }, { null }, { _, _ -> null }, { logs += it })
        assertEquals(listOf("car hotspot: ADB cannot read the AP state; not starting it"), logs)

        logs.clear()
        CarHotspotAdbFallback.start(deadline(), { false }, { false }, { _, _ -> "Connectivity service commands:\n  help" }, { logs += it })
        assertEquals(listOf("car hotspot: firmware lists no hotspot start command in cmd connectivity/tethering help"), logs)

        logs.clear()
        CarHotspotAdbFallback.start(deadline(), { false }, { false }, { command, _ ->
            if (command.endsWith("help")) "  start-tethering wifi" else "\nSecurityException: no tether permission\nDIPLAY_HOTSPOT_EXIT:0"
        }, { logs += it })
        assertEquals(listOf("car hotspot: firmware rejected 'cmd connectivity start-tethering wifi': " +
            "SecurityException: no tether permission"), logs)
    }

    @Test fun unknownAppStatusCanBeConfirmedThroughCurrentTetheredApDump() {
        var on = false
        assertTrue(CarHotspotAdbFallback.start(deadline(), { false }, { null }, { command, _ ->
            when {
                command == "dumpsys wifi" -> "mWifiApState: ${if (on) 13 else 11}"
                command.endsWith("help") -> "  start-tethering wifi"
                else -> { on = true; "DIPLAY_HOTSPOT_EXIT:0" }
            }
        }, {}))
    }

    @Test fun dumpParserIgnoresHistoricalStationAndLocalOnlyState() {
        assertNull(CarHotspotAdbFallback.apState("log: mWifiApState=13\nWi-Fi is enabled\nwlan1 has address"))
        assertNull(CarHotspotAdbFallback.apState("mWifiApState: 11\nmSoftApState: 13"))
        val started = "--Dump of SoftApManager--\ncurrent StateMachine mode: StartedState\nmIfaceIsUp: true\nmMode: 1"
        assertEquals(true, CarHotspotAdbFallback.apState(started))
        assertNull(CarHotspotAdbFallback.apState("old log: " + started))
        assertNull(CarHotspotAdbFallback.apState(started.replace("mMode: 1", "mMode: 2")))
        assertEquals(false, CarHotspotAdbFallback.apState(started.replace("StartedState", "IdleState").replace("true", "false")))
    }

    @Test fun dumpParserDoesNotDiscardUnknownOrConflictingCurrentStates() {
        assertNull(CarHotspotAdbFallback.apState("mWifiApState: 13\nmSoftApState: 99"))
        val started = "Dump of SoftApManager id=1\ncurrent StateMachine mode: StartedState\nmIfaceIsUp: true\nmOriginalModeConfiguration.targetMode: 1"
        assertEquals(true, CarHotspotAdbFallback.apState(started))
        assertNull(CarHotspotAdbFallback.apState(started.replace("Configuration.targetMode", "ConfigurationXtargetMode")))
        assertNull(CarHotspotAdbFallback.apState("mWifiApState: 13\n" + started.replace("targetMode: 1", "targetMode: 2")))
        assertNull(CarHotspotAdbFallback.apState(started + "\n" + started.replace("StartedState", "StartingState")))
        assertNull(CarHotspotAdbFallback.apState(started + "\n" + started.replace("StartedState", "IdleState").replace("true", "false")))
    }

    @Test fun cancellationAfterCapabilityReadPreventsMutation() {
        var cancelled = false
        val commands = mutableListOf<String>()
        assertFalse(CarHotspotAdbFallback.start(deadline(), { cancelled }, { false }, { command, _ ->
            commands += command; cancelled = true; "  start-tethering wifi"
        }, {}))
        assertEquals(listOf("cmd connectivity help"), commands)
    }

    @Test fun boundedDeadlineAbortsStalledIoWithoutTakingASecondBudget() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val aborted = AtomicBoolean(false)
        val started = System.nanoTime()
        assertFalse(CarHotspotAdbFallback.bounded(deadline(50), { false }, { aborted.set(true); release.countDown() }) {
            entered.countDown(); release.await(2, TimeUnit.SECONDS); false
        })
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertTrue(aborted.get())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
    }

    @Test fun boundedCancellationAbortsBeforeAReadTimeout() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val aborted = AtomicBoolean(false)
        val cancellation = thread { entered.await(1, TimeUnit.SECONDS); cancelled.set(true) }
        assertFalse(CarHotspotAdbFallback.bounded(deadline(2_000), cancelled::get, { aborted.set(true); release.countDown() }) {
            entered.countDown(); release.await(2, TimeUnit.SECONDS); false
        })
        cancellation.join(1_000)
        assertTrue(aborted.get())
    }
}
