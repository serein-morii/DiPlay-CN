package com.shilapi.xcertplay.network

import android.net.wifi.p2p.WifiP2pManager
import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class P2pStartupRecoveryTest {
    @Test fun manualChannelOverridesStationAndRememberedConfiguration() {
        val remembered = P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)
        for ((channel, frequency) in listOf(1 to 2412, 6 to 2437, 11 to 2462,
            36 to 5180, 40 to 5200, 44 to 5220, 48 to 5240, 149 to 5745,
            153 to 5765, 157 to 5785, 161 to 5805, 165 to 5825)) {
            assertEquals(listOf(P2pCreationRequest(P2pCreationMode.PREFERRED_CHANNEL, frequency)),
                P2pStartupRecovery.plan(5180, remembered, preferredChannel = channel))
        }
        assertEquals(P2pStartupRecovery.plan(5200, remembered),
            P2pStartupRecovery.plan(5200, remembered, preferredChannel = WifiP2pChannels.AUTO))
    }

    @Test fun rejectedManualChannelNeverFallsBackToAnotherChannel() {
        val attempts = mutableListOf<Int?>()
        val rejection = P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
        val failure = assertThrows(P2pChannelUnavailableException::class.java) {
            P2pStartupRecovery.create(5180, { fail("Unexpected fallback") }, preferredChannel = 149) {
                attempts += it.frequencyMHz
                throw rejection
            }
        }
        assertEquals(listOf(5745), attempts)
        assertSame(rejection, failure.cause)
        assertEquals(ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE, failure.reason)
        assertEquals("Wi-Fi Direct could not use channel 149. Choose Auto or another channel. rejected", failure.message)
    }

    @Test fun busyManualChannelRetriesTheSameChannelOnlyOnce() {
        val attempts = mutableListOf<Int?>()
        var retries = 0
        assertThrows(P2pChannelUnavailableException::class.java) {
            P2pStartupRecovery.create(5180, { retries++ }, preferredChannel = 149) {
                attempts += it.frequencyMHz
                throw P2pCreateRejected(WifiP2pManager.BUSY, "busy")
            }
        }
        assertEquals(listOf(5745, 5745), attempts)
        assertEquals(1, retries)
    }

    @Test fun manualChannelDoesNotUseSystemFallbackWhenBuilderIsUnavailable() {
        var calls = 0
        assertThrows(P2pChannelUnavailableException::class.java) {
            P2pStartupRecovery.create(null, { fail("Unexpected fallback") }, preferredChannel = 149) {
                calls++
                throw P2pConfigBuildCompatibilityFailure(NoSuchMethodError("vendor framework"))
            }
        }
        assertEquals(1, calls)
    }

    @Test fun manualChannelDoesNotRetryUncertainTimeoutOrMissingPermission() {
        for (failure in listOf(IOException("timeout"),
            P2pCreateRejected(WifiP2pManager.NO_PERMISSION, "permission"),
            P2pCreateRejected(WifiP2pManager.P2P_UNSUPPORTED, "unsupported"))) {
            var calls = 0
            assertSame(failure, assertThrows(IOException::class.java) {
                P2pStartupRecovery.create(null, { fail("Unsafe retry") }, preferredChannel = 149) {
                    calls++
                    throw failure
                }
            })
            assertEquals(1, calls)
        }
    }

    @Test fun confirmedFrequencyGoesFirstWithoutBeingRetriedLater() {
        val preferred = P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2437)
        val plan = P2pStartupRecovery.plan(5180, preferred)
        assertEquals(preferred, plan.first())
        assertEquals(1, plan.count { it.frequencyMHz == 2437 })
        assertEquals(listOf(2437, 5180, 2412, 2462, 5745, null), plan.map { it.frequencyMHz })
    }

    @Test fun nextToAFiveGhzStationTwoGhzComesBeforeAnotherFiveGhzChannel() {
        // A Tang on its home network at 5200 MHz: 5745 made the radio switch channels.
        assertEquals(listOf(5200, 2437, 2412, 2462, 5180, 5745, null), P2pStartupRecovery.plan(5200).map { it.frequencyMHz })
        // A remembered 5745 no longer goes first there, but stays as a late fallback.
        val remembered = P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5745)
        assertEquals(listOf(5200, 2437, 2412, 2462, 5180, 5745, null),
            P2pStartupRecovery.plan(5200, remembered).map { it.frequencyMHz })
        // The station's own channel remembered still goes first; away from a station nothing changes.
        assertEquals(5200, P2pStartupRecovery.plan(5200, P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5200)).first().frequencyMHz)
        assertEquals(listOf(5745, 5180, 2437, 2412, 2462, null), P2pStartupRecovery.plan(null, remembered).map { it.frequencyMHz })
        // A station on a DFS 5 GHz channel is not aligned to, but still keeps the group off 5 GHz first.
        assertEquals(listOf(2437, 2412, 2462, 5180, 5745, null), P2pStartupRecovery.plan(5500).map { it.frequencyMHz })
    }

    @Test fun rejectedRememberedSystemConfigurationFallsBackToExplicitChannels() {
        val attempted = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(2437, {}, P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)) {
            attempted += it.frequencyMHz
            if (it.frequencyMHz == null) throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
        }
        assertEquals(listOf(null, 2437), attempted)
        assertEquals(2437, result.frequencyMHz)
    }

    @Test fun rememberedSystemDefaultCannotBypassTwoGhzBesideAFiveGhzStation() {
        val remembered = P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)
        val plan = P2pStartupRecovery.plan(5200, remembered)
        assertEquals(listOf(5200, 2437, 2412, 2462, 5180, 5745, null), plan.map { it.frequencyMHz })
        val attempts = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(5200, {}, remembered) {
            attempts += it.frequencyMHz
            if (it.frequencyMHz != 2437) throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
        }
        assertEquals(listOf(5200, 2437), attempts)
        assertEquals(2437, result.frequencyMHz)
        // Proven default configuration remains first outside the new 5 GHz station policy.
        assertEquals(remembered, P2pStartupRecovery.plan(null, remembered).first())
        assertEquals(remembered, P2pStartupRecovery.plan(2437, remembered).first())
    }

    @Test fun unsafeRememberedFrequenciesDoNotBypassTheChannelPolicy() {
        for (frequency in listOf(0, 2472, 2484, 5500, 5955)) {
            assertEquals(P2pStartupRecovery.plan(null), P2pStartupRecovery.plan(null,
                P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, frequency)))
        }
    }

    @Test fun workingAlignedChannelNeverUsesFallback() {
        val attempts = mutableListOf<P2pCreationRequest>()
        val mode = P2pStartupRecovery.create(5180, { fail("Unexpected retry") }) { attempts += it }
        assertEquals(listOf(P2pCreationRequest(P2pCreationMode.ALIGNED_5_GHZ, 5180)), attempts)
        assertEquals(P2pCreationMode.ALIGNED_5_GHZ, mode.mode)
    }

    @Test fun rejectedChannelAndCustomGroupFallBackToSystemConfiguration() {
        val attempts = mutableListOf<P2pCreationRequest>()
        var retries = 0
        val mode = P2pStartupRecovery.create(5180, { retries++ }) {
            attempts += it
            if (it.mode != P2pCreationMode.SYSTEM_DEFAULT) throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
        }
        assertEquals(listOf(5180, 2437, 2412, 2462, 5745, null), attempts.map { it.frequencyMHz })
        assertEquals(P2pCreationMode.SYSTEM_DEFAULT, mode.mode)
        assertEquals(5, retries)
    }

    @Test fun unconnectedStationStartsWithFiveGhzAndBusyRetriesEachChannelOnce() {
        val attempts = mutableListOf<P2pCreationRequest>()
        try {
            P2pStartupRecovery.create(null, {}) {
                attempts += it
                throw P2pCreateRejected(WifiP2pManager.BUSY, "busy")
            }
            fail("Expected busy")
        } catch (failure: P2pCreateRejected) { assertEquals(WifiP2pManager.BUSY, failure.reason) }
        assertEquals(P2pStartupRecovery.plan(null).flatMap { mode -> List(2) { mode } }, attempts)
    }

    @Test fun firmwareThatBusiesEveryFiveGhzRequestStillReachesTwoGhz() {
        val attempts = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(5200, {}) {
            attempts += it.frequencyMHz
            if ((it.frequencyMHz ?: 0) >= 5000) throw P2pCreateRejected(WifiP2pManager.BUSY, "busy")
        }
        assertEquals(P2pCreationMode.FIXED_2_GHZ, result.mode)
        assertEquals(2437, result.frequencyMHz)
        // Next to a 5 GHz station, 2.4 GHz is tried right after the station's own channel.
        assertEquals(listOf(5200, 5200, 2437), attempts)
    }

    @Test fun everyConfigurationRejectedStopsAfterBoundedAttempts() {
        var calls = 0
        try {
            P2pStartupRecovery.create(null, {}) {
                calls++
                throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
            }
            fail("Expected rejection")
        } catch (_: P2pCreateRejected) { assertEquals(6, calls) }
    }

    @Test fun twoGhzOnlyDriverWithoutChannelSelectionRecoversBeforeAnyAutomaticRequest() {
        val attempts = mutableListOf<P2pCreationRequest>()
        val result = P2pStartupRecovery.create(null, {}) {
            attempts += it
            if (it.frequencyMHz !in listOf(2412, 2437, 2462)) {
                throw P2pCreateRejected(WifiP2pManager.ERROR, "unsupported band or automatic channel")
            }
        }
        assertEquals(2437, result.frequencyMHz)
        assertTrue(attempts.all { it.frequencyMHz != null })
        assertEquals(listOf(5180, 5745, 2437), attempts.map { it.frequencyMHz })
    }

    @Test fun existingTwoGhzStationUsesItsExactChannelFirst() {
        val result = P2pStartupRecovery.create(2422, { fail("Unexpected fallback") }) {
            assertEquals(2422, it.frequencyMHz)
        }
        assertEquals(P2pCreationMode.ALIGNED_2_GHZ, result.mode)
    }

    @Test fun rejectedChannelSixTriesOtherExplicitTwoGhzChannels() {
        val attempted = mutableListOf<Int?>()
        val result = P2pStartupRecovery.create(null, {}) {
            attempted += it.frequencyMHz
            if (it.frequencyMHz != 2462) throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
        }
        assertEquals(2462, result.frequencyMHz)
        assertEquals(listOf(5180, 5745, 2437, 2412, 2462), attempted)
    }

    @Test fun planNeverRequestsDfsSixGhzInvalidOrPhoneUnfriendlyStationFrequencies() {
        for (station in listOf(0, -1, 5955, 2472, 2484)) {
            val plan = P2pStartupRecovery.plan(station)
            assertEquals(listOf(5180, 5745, 2437, 2412, 2462, null), plan.map { it.frequencyMHz })
        }
        // 5 GHz stations the group cannot align to (off-grid or DFS) keep it on 2.4 GHz first.
        for (station in listOf(5191, 5260, 5500)) {
            val plan = P2pStartupRecovery.plan(station)
            assertEquals(listOf(2437, 2412, 2462, 5180, 5745, null), plan.map { it.frequencyMHz })
        }
        val plan = P2pStartupRecovery.plan(5200)
        assertEquals(5200, plan.first().frequencyMHz)
        assertEquals(plan.size, plan.distinctBy { it.frequencyMHz }.size)
        assertTrue(plan.size <= 7)
    }

    @Test fun permissionUnsupportedAndUncertainTimeoutNeverTriggerAnotherCreation() {
        for (failure in listOf(P2pCreateRejected(WifiP2pManager.NO_PERMISSION, "permission"),
            P2pCreateRejected(WifiP2pManager.P2P_UNSUPPORTED, "unsupported"), IOException("timeout"))) {
            var calls = 0
            try {
                P2pStartupRecovery.create(5180, { fail("Unsafe retry") }) { calls++; throw failure }
                fail("Expected error")
            } catch (actual: IOException) { assertSame(failure, actual) }
            assertEquals(1, calls)
        }
    }

    @Test fun groupAppearingDuringRecoveryPreventsASecondCreation() {
        var calls = 0
        try {
            P2pStartupRecovery.create(5180, { throw P2pResetRequiredException() }) {
                calls++
                throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
            }
            fail("Expected occupied group")
        } catch (_: P2pResetRequiredException) { assertEquals(1, calls) }
    }

    @Test fun knownPreCreateBuilderFailureUsesSystemDefaultOnceAndReturnsItsEffectiveMode() {
        val attempts = mutableListOf<P2pCreationRequest>()
        var guards = 0
        val effective = P2pStartupRecovery.create(5180, { guards++ }) {
            attempts += it
            if (it.mode != P2pCreationMode.SYSTEM_DEFAULT) {
                throw P2pConfigBuildCompatibilityFailure(NoSuchMethodError())
            }
        }
        assertEquals(listOf(5180, null), attempts.map { it.frequencyMHz })
        assertEquals(1, guards)
        assertEquals(P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT), effective)
    }

    @Test fun failedCompatibilityDefaultStopsWithoutTryingAnotherChannelOrDefault() {
        val terminalFailures = listOf(
            P2pCreateRejected(WifiP2pManager.ERROR, "default rejected"),
            P2pCreateRejected(WifiP2pManager.NO_PERMISSION, "permission"),
            P2pCreateRejected(WifiP2pManager.P2P_UNSUPPORTED, "unsupported"),
            IOException("default timeout"),
        )
        for (terminal in terminalFailures) {
            val attempts = mutableListOf<P2pCreationRequest>()
            var guards = 0
            try {
                P2pStartupRecovery.create(5180, { guards++ }) {
                    attempts += it
                    if (it.mode == P2pCreationMode.SYSTEM_DEFAULT) throw terminal
                    throw P2pConfigBuildCompatibilityFailure(NoSuchMethodError())
                }
                fail("Expected original default failure")
            } catch (actual: IOException) { assertSame(terminal, actual) }
            assertEquals(listOf(5180, null), attempts.map { it.frequencyMHz })
            assertEquals(1, guards)
        }
    }

    @Test fun aForeignGroupOrChangedPrerequisiteBlocksTheCompatibilityDefault() {
        val guards = listOf(P2pResetRequiredException(), SecurityException("permission revoked"))
        for (blocked in guards) {
            var calls = 0
            try {
                P2pStartupRecovery.create(5180, { throw blocked }) {
                    calls++
                    throw P2pConfigBuildCompatibilityFailure(NoSuchMethodError())
                }
                fail("Expected guard failure")
            } catch (actual: Exception) { assertSame(blocked, actual) }
            assertEquals(1, calls)
        }
    }

    @Test fun compatibilityFailureAtAnAlreadyDefaultRequestDoesNotRepeatIt() {
        var calls = 0
        val original = P2pConfigBuildCompatibilityFailure(NoSuchMethodError())
        try {
            P2pStartupRecovery.create(2437, { fail("Unexpected guard") }, P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT)) {
                calls++
                throw original
            }
            fail("Expected original failure")
        } catch (actual: P2pConfigBuildCompatibilityFailure) { assertSame(original, actual) }
        assertEquals(1, calls)
    }

    @Test fun aReportedBuilderFailureRunsTheGuardBeforeOneNullConfigCreation() {
        val events = mutableListOf<String>()
        val createdConfigs = mutableListOf<Any?>()
        val failure = reportedBuilderFailure()
        val effective = P2pStartupRecovery.create(5180, { events += "guard" }) { selection ->
            val config: Any? = if (selection.mode == P2pCreationMode.SYSTEM_DEFAULT) null else {
                P2pConfigBuildDiagnostics.build(29, selection, {}) {
                    events += "builder"
                    throw failure
                }
            }
            events += "createGroup"
            createdConfigs += config
        }
        assertEquals(listOf("builder", "guard", "createGroup"), events)
        assertEquals(listOf<Any?>(null), createdConfigs)
        assertEquals(P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT), effective)
    }

    @Test fun anUnmatchedOrAndroidElevenBuilderErrorCannotReachAnyCreationOrRetry() {
        val cases = listOf(
            29 to reportedBuilderFailure("I"),
            30 to reportedBuilderFailure(),
        )
        for ((sdk, original) in cases) {
            var requests = 0
            var creations = 0
            try {
                P2pStartupRecovery.create(5180, { fail("Unexpected retry") }) { selection ->
                    requests++
                    if (selection.mode != P2pCreationMode.SYSTEM_DEFAULT) {
                        P2pConfigBuildDiagnostics.build(sdk, selection, {}) { throw original }
                    }
                    creations++
                }
                fail("Expected original linkage failure")
            } catch (actual: NoSuchMethodError) { assertSame(original, actual) }
            assertEquals(1, requests)
            assertEquals(0, creations)
        }
    }

    @Test fun fiveGhzBandStaysInsideTheBandAndIgnoresStationOnTwoGhzAndRememberedAuto() {
        val remembered = P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2437)
        val plan = P2pStartupRecovery.plan(2437, remembered, preferredChannel = WifiP2pChannels.AUTO_5_GHZ)
        assertEquals(listOf(
            P2pCreationRequest(P2pCreationMode.BAND_5_GHZ),
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5180),
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5745),
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5220),
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5785),
        ), plan)
        assertTrue(plan.none { it.mode == P2pCreationMode.SYSTEM_DEFAULT })
    }

    @Test fun bandPlanPutsAnInBandStationChannelFirstWithoutRepeatingIt() {
        assertEquals(listOf(
            P2pCreationRequest(P2pCreationMode.ALIGNED_5_GHZ, 5745),
            P2pCreationRequest(P2pCreationMode.BAND_5_GHZ),
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5180),
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5220),
            P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5785),
        ), P2pStartupRecovery.plan(5745, preferredChannel = WifiP2pChannels.AUTO_5_GHZ))
        assertEquals(listOf(
            P2pCreationRequest(P2pCreationMode.ALIGNED_2_GHZ, 2412),
            P2pCreationRequest(P2pCreationMode.BAND_2_GHZ),
            P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2437),
            P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2462),
        ), P2pStartupRecovery.plan(2412, preferredChannel = WifiP2pChannels.AUTO_2_4_GHZ))
    }

    @Test fun legacyBandPlanUsesOnlyExplicitChannels() {
        assertEquals(listOf(5180, 5745, 5220, 5785),
            P2pStartupRecovery.plan(null, preferredChannel = WifiP2pChannels.AUTO_5_GHZ, bandRequests = false)
                .map { it.frequencyMHz })
    }

    @Test fun rejectedBandWalksItsChannelsThenStopsWithoutSwitchingBand() {
        val attempts = mutableListOf<P2pCreationRequest>()
        val failure = assertThrows(P2pChannelUnavailableException::class.java) {
            P2pStartupRecovery.create(null, {}, preferredChannel = WifiP2pChannels.AUTO_5_GHZ) {
                attempts += it
                throw P2pCreateRejected(WifiP2pManager.ERROR, "rejected")
            }
        }
        assertEquals(P2pStartupRecovery.plan(null, preferredChannel = WifiP2pChannels.AUTO_5_GHZ), attempts)
        assertTrue(attempts.all { it.mode == P2pCreationMode.BAND_5_GHZ || WifiP2pBand.FIVE_GHZ.contains(it.frequencyMHz) })
        assertTrue(failure.message!!.contains("5 GHz band"))
    }

    @Test fun bandBuilderFailureNeverFallsBackToTheUnpinnedDefault() {
        var calls = 0
        assertThrows(P2pChannelUnavailableException::class.java) {
            P2pStartupRecovery.create(null, { fail("Unexpected fallback") }, preferredChannel = WifiP2pChannels.AUTO_2_4_GHZ) {
                calls++
                throw P2pConfigBuildCompatibilityFailure(NoSuchMethodError("vendor framework"))
            }
        }
        assertEquals(1, calls)
    }

    private fun reportedBuilderFailure(returnDescriptor: String = "Ljava/lang/String;") = NoSuchMethodError(
        "No virtual method getNetworkName()$returnDescriptor in class Landroid/net/wifi/p2p/WifiP2pConfig;",
    ).apply {
        stackTrace = arrayOf(StackTraceElement("android.net.wifi.p2p.WifiP2pConfig\$Builder", "build", null, 1))
    }

    @Test fun autoSkipsFiveGhzOnAChipWithoutIt() {
        // #582: an i.MX8 head unit (Android 8.1, fiveGhzSupported=false, no station) was asked for 5180 first.
        assertEquals(listOf(
            P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2437),
            P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2412),
            P2pCreationRequest(P2pCreationMode.FIXED_2_GHZ, 2462),
            P2pCreationRequest(P2pCreationMode.SYSTEM_DEFAULT),
        ), P2pStartupRecovery.plan(null, bandRequests = false, fiveGhz = false))
        val remembered = P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5745)
        assertTrue(P2pStartupRecovery.plan(null, remembered, fiveGhz = false).none { (it.frequencyMHz ?: 0) > 5000 })
        assertEquals(P2pCreationRequest(P2pCreationMode.FIXED_5_GHZ, 5180), P2pStartupRecovery.plan(null).first())
    }

    @Test fun aManualChannelIsStillTheDriversChoiceWithoutFiveGhz() {
        assertEquals(listOf(P2pCreationRequest(P2pCreationMode.PREFERRED_CHANNEL, 5180)),
            P2pStartupRecovery.plan(null, preferredChannel = 36, fiveGhz = false))
    }

    // Each further request would show Android's "turn the hotspot off?" prompt again (Galaxy Note20, Android 13).
    @Test fun aBusyWithAKnownCauseEndsThePlanAtOnce() {
        val attempts = mutableListOf<P2pCreationRequest>()
        val asked = mutableListOf<P2pCreateRejected>()
        val cause = IOException("refused beside the car hotspot")
        val failure = assertThrows(IOException::class.java) {
            P2pStartupRecovery.create(2437, { fail("Unexpected retry") }, busyCause = { asked += it; cause }) {
                attempts += it
                throw P2pCreateRejected(WifiP2pManager.BUSY, "busy", replyMillis = 3_500)
            }
        }
        assertSame(cause, failure)
        assertEquals(1, attempts.size)
        assertEquals(listOf(3_500L), asked.map { it.replyMillis })
    }

    @Test fun aBusyWithoutAKnownCauseStillWalksThePlan() {
        val attempts = mutableListOf<P2pCreationRequest>()
        var asked = 0
        assertThrows(P2pCreateRejected::class.java) {
            P2pStartupRecovery.create(null, {}, busyCause = { asked++; null }) {
                attempts += it
                throw P2pCreateRejected(WifiP2pManager.BUSY, "busy", replyMillis = 40)
            }
        }
        assertEquals(P2pStartupRecovery.plan(null).flatMap { listOf(it, it) }, attempts)
        assertEquals(attempts.size, asked)
    }

    @Test fun onlyBusyAsksForACause() {
        var asked = 0
        assertThrows(P2pCreateRejected::class.java) {
            P2pStartupRecovery.create(null, {}, busyCause = { asked++; IOException("unexpected") }) {
                throw P2pCreateRejected(WifiP2pManager.ERROR, "error", replyMillis = 3_500)
            }
        }
        assertEquals(0, asked)
    }

    @Test fun aRefusedPromptBesideTheCarHotspotEndsWifiDirect() {
        val rejection = P2pCreateRejected(WifiP2pManager.BUSY, "busy", replyMillis = 3_500)
        val failure = requireNotNull(P2pHotspotConflict.failure(33, true, rejection))
        assertEquals(WirelessStartupFailure.WIFI_DIRECT_BESIDE_HOTSPOT, failure.reason)
        assertFalse(failure.reason.retryable)
        assertSame(rejection, failure.cause)
    }

    @Test fun otherBusyAnswersAreNotARefusedPrompt() {
        fun busy(replyMillis: Long?) = P2pCreateRejected(WifiP2pManager.BUSY, "busy", replyMillis)
        // Android 12 and older do not ask.
        assertNull(P2pHotspotConflict.failure(32, true, busy(3_500)))
        assertNull(P2pHotspotConflict.failure(33, false, busy(3_500)))
        // Firmware that hides the hotspot state keeps the old behaviour.
        assertNull(P2pHotspotConflict.failure(33, null, busy(3_500)))
        // A firmware's own per-channel BUSY answers without waiting for anyone.
        assertNull(P2pHotspotConflict.failure(33, true, busy(40)))
        assertNull(P2pHotspotConflict.failure(33, true, busy(null)))
        assertNull(P2pHotspotConflict.failure(33, true, P2pCreateRejected(WifiP2pManager.ERROR, "error", 3_500)))
    }
}
