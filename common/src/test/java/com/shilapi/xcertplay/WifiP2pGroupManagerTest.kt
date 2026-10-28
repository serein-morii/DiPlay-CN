package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.shilapi.xcertplay.network.P2pResetRequiredException
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWifiP2pManager
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE,
    shadows = [WifiP2pGroupManagerTest.P2pRadio::class, WifiP2pGroupManagerTest.P2pChannel::class])
class WifiP2pGroupManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val radio get() = shadowOf(context.getSystemService(WifiP2pManager::class.java)) as P2pRadio

    @Before fun setup() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val wifi = context.getSystemService(WifiManager::class.java)
        wifi.isWifiEnabled = true
        // A station on 5180 MHz implies a 5 GHz radio; Robolectric reports none by default.
        shadowOf(wifi).setIs5GHzBandSupported(true)
        shadowOf(wifi.connectionInfo).setFrequency(5180)
        shadowOf(wifi.connectionInfo).setSupplicantState(SupplicantState.COMPLETED)
    }

    @Test fun disconnectedStationCannotPinNewGroupToStaleTwoGhzChannel() {
        val info = context.getSystemService(WifiManager::class.java).connectionInfo
        shadowOf(info).setFrequency(2462)
        shadowOf(info).setSupplicantState(SupplicantState.DISCONNECTED)
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            background { manager.start(5000) }
            assertEquals(listOf(5180), radio.requests.map { it?.groupOwnerBand })
            assertTrue(logs.any { it.contains("stationMHz=unknown stationState=DISCONNECTED reportedStationMHz=2462") })
        }
    }

    @Test fun associatingStationDoesNotSelectOrReuseStaleChannel() {
        seedMemory(station = 2462)
        val info = context.getSystemService(WifiManager::class.java).connectionInfo
        shadowOf(info).setFrequency(2462)
        shadowOf(info).setSupplicantState(SupplicantState.ASSOCIATING)
        WifiP2pGroupManager(context).use { manager ->
            background { manager.start(5000) }
            assertEquals(listOf(5180), radio.requests.map { it?.groupOwnerBand })
        }
    }

    private val memory get() = context.getSharedPreferences("carplay_wifi_p2p_success", Context.MODE_PRIVATE)
    private fun seedMemory(kind: String = "frequency", requested: Int = 2437, station: Int = 5180): String {
        val encoded = "$kind|$requested|2437|$station|00000000-0000-0000-0000-000000000001"
        memory.edit().putString("confirmed", encoded).commit()
        return encoded
    }

    @Test fun manualChannelOverridesAlignmentWithoutReplacingAutomaticMemory() {
        val remembered = seedMemory()
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add, preferredChannel = 149).use { manager ->
            val info = background { manager.start(5000) }
            assertEquals(149, info.channel)
            assertEquals(listOf(5745), radio.requests.map { it?.groupOwnerBand })
            manager.onCarPlayConfirmed()
            assertEquals(remembered, memory.getString("confirmed", null))
        }
        assertEquals(remembered, memory.getString("confirmed", null))
        assertTrue(logs.any { it.contains("preference=149 frequencyMHz=5745") })
        assertTrue(logs.any { it.contains("requestedMHz=5745 actualMHz=5745 matched=true") })
        radio.requests.clear()
        WifiP2pGroupManager(context).use { manager ->
            background { manager.start(5000) }
            assertEquals(listOf(2437), radio.requests.map { it?.groupOwnerBand })
            manager.onCarPlayConfirmed()
        }
    }

    @Test fun rejectedManualChannelHasAnActionableErrorAndNoFallback() {
        radio.rejectCustom = true
        WifiP2pGroupManager(context, preferredChannel = 149).use { manager ->
            val error = failure { manager.start(5000) }
            assertTrue(error.message!!.contains("channel 149"))
            assertTrue(error.message!!.contains("Choose Auto"))
        }
        assertEquals(listOf(5745), radio.requests.map { it?.groupOwnerBand })
        assertNull(memory.getString("confirmed", null))
    }

    @Test fun firmwareIgnoringManualChannelFailsAndRemovesItsOwnGroup() {
        radio.reportedFrequency = 5180
        WifiP2pGroupManager(context, preferredChannel = 149).use { manager ->
            assertTrue(failure { manager.start(5000) }.message!!.contains("selected channel 36 instead"))
        }
        assertEquals(listOf(5745), radio.requests.map { it?.groupOwnerBand })
        assertEquals(1, radio.removals)
        assertNull(memory.getString("confirmed", null))
    }

    @Test fun hotspotCreationAloneAndLateConfirmationAfterCloseAreNeverLearned() {
        val manager = WifiP2pGroupManager(context)
        background { manager.start(5000) }
        assertNull(memory.getString("confirmed", null))
        manager.close()
        manager.onCarPlayConfirmed()
        assertNull(memory.getString("confirmed", null))
    }

    @Test fun confirmedTwoGhzConfigurationSurvivesManagerRecreationAndGoesFirst() {
        radio.fixed24Only = true
        WifiP2pGroupManager(context).use { manager ->
            val info = background { manager.start(6000) }
            assertEquals(2, radio.requests.size)
            assertNull(memory.getString("confirmed", null))
            manager.onCarPlayConfirmed()
            val record = memory.getString("confirmed", null)!!
            manager.onCarPlayConfirmed()
            assertEquals(record, memory.getString("confirmed", null))
            assertTrue(record.startsWith("frequency|2437|2437|5180|"))
            assertFalse(record.contains(info.ssid) || record.contains(info.passphrase))
        }
        radio.requests.clear()
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            val info = background { manager.start(5000) }
            assertEquals(6, info.channel)
            assertEquals(listOf(2437), radio.requests.map { it?.groupOwnerBand })
            assertTrue(logs.any { it.contains("remembered first mode=FIXED_2_GHZ") })
            manager.onCarPlayConfirmed()
        }
    }

    @Test fun rejectedRememberedChannelIsClearedAndNewSuccessReplacesIt() {
        seedMemory()
        radio.fixed24Only = true
        radio.allowed24 = setOf(2462)
        WifiP2pGroupManager(context).use { manager ->
            val info = background { manager.start(7000) }
            assertEquals(listOf(2437, 5180, 2412, 2462), radio.requests.map { it?.groupOwnerBand })
            assertEquals(11, info.channel)
            assertNull(memory.getString("confirmed", null))
            manager.onCarPlayConfirmed()
            assertTrue(memory.getString("confirmed", null)!!.startsWith("frequency|2462|2462|"))
        }
    }

    @Test fun rememberedGroupThatNeverReachesCarPlayIsEvictedOnClose() {
        seedMemory()
        WifiP2pGroupManager(context).use { manager -> background { manager.start(5000) } }
        assertEquals(listOf(2437), radio.requests.map { it?.groupOwnerBand })
        assertNull(memory.getString("confirmed", null))
    }

    @Test fun changedInternetChannelUsesCurrentAlignmentInsteadOfOldPreference() {
        val previous = seedMemory()
        shadowOf(context.getSystemService(WifiManager::class.java).connectionInfo).setFrequency(2412)
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager -> background { manager.start(5000) } }
        assertEquals(listOf(2412), radio.requests.map { it?.groupOwnerBand })
        assertEquals(previous, memory.getString("confirmed", null))
        assertTrue(logs.any { it.contains("skipped=station_channel_changed") })
    }

    @Test fun rememberedDefaultUsesSystemCreationInsteadOfInventingAFixedChannel() {
        seedMemory(kind = "system", requested = 0, station = 2437)
        shadowOf(context.getSystemService(WifiManager::class.java).connectionInfo).setFrequency(2437)
        WifiP2pGroupManager(context).use { manager ->
            background { manager.start(5000) }
            assertEquals(1, radio.requests.size)
            assertNull(radio.requests.single())
            manager.onCarPlayConfirmed()
            assertTrue(memory.getString("confirmed", null)!!.startsWith("system|0|2437|"))
        }
    }

    @Test fun rememberedDefaultCannotBypassExplicitChannelsBesideFiveGhzStation() {
        seedMemory(kind = "system", requested = 0, station = 5200)
        shadowOf(context.getSystemService(WifiManager::class.java).connectionInfo).setFrequency(5200)
        radio.fixed24Only = true
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            background { manager.start(6000) }
            assertEquals(listOf(5200, 2437), radio.requests.map { it?.groupOwnerBand })
            assertTrue(logs.any { it.contains("remembered deferred mode=SYSTEM_DEFAULT") })
            manager.onCarPlayConfirmed()
            assertTrue(memory.getString("confirmed", null)!!.startsWith("frequency|2437|2437|5200|"))
        }
    }

    @Test fun malformedStoredPreferenceDoesNotBlockConnection() {
        memory.edit().putInt("confirmed", 123).commit()
        WifiP2pGroupManager(context).use { manager ->
            background { manager.start(5000) }
            assertEquals(listOf(5180), radio.requests.map { it?.groupOwnerBand })
            manager.onCarPlayConfirmed()
            assertTrue(memory.getString("confirmed", null)!!.startsWith("frequency|5180|5180|"))
        }
    }

    @Test fun staleCloseDoesNotEraseANewerConfirmedRecord() {
        seedMemory()
        WifiP2pGroupManager(context).use { manager ->
            background { manager.start(5000) }
            val newer = "frequency|2462|2462|5180|00000000-0000-0000-0000-000000000002"
            memory.edit().putString("confirmed", newer).commit()
        }
        assertTrue(memory.getString("confirmed", null)!!.startsWith("frequency|2462|"))
    }

    @Test fun workingRadioKeepsAlignedChannelAndRemovesOnlyItsGroupOnClose() {
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            val info = background { manager.start(6000) }
            assertEquals(5180, info.frequencyMHz)
            assertEquals(1, radio.requests.size)
            assertEquals(5180, radio.requests.single()!!.groupOwnerBand)
            assertEquals(info.ssid, context.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE).getString("owned_ssid", null))
            assertTrue(logs.any { it.contains("ready mode=ALIGNED_5_GHZ") })
        }
        assertEquals(1, radio.removals)
        assertTrue(context.getSystemService(WifiManager::class.java).isWifiEnabled)
    }

    @Test fun genericErrorsRecoverWithRealSystemCredentialsAndChannel() {
        radio.rejectCustom = true
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            val info = background { manager.start(6000) }
            assertEquals(6, radio.requests.size)
            assertNull(radio.requests.last())
            assertEquals("DIRECT-system-test", info.ssid)
            assertEquals("test-generated-key", info.passphrase)
            assertEquals(6, info.channel)
            assertEquals("2.4 GHz", info.bandLabel)
            assertEquals(info.ssid, context.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE).getString("owned_ssid", null))
            assertTrue(logs.all { DiagnosticRedactor.redact(it) != null })
            assertFalse(logs.any { it.contains(info.ssid) || it.contains(info.passphrase) })
        }
        assertEquals(1, radio.removals)
    }

    @Test fun twoGhzOnlyNoAcsDriverConnectsUsingAnExplicitChannelAndOwnedCredentials() {
        radio.fixed24Only = true
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            val info = background { manager.start(6000) }
            assertEquals(listOf(5180, 2437), radio.requests.map { it?.groupOwnerBand })
            assertTrue(radio.requests.all { it != null && it.groupOwnerBand > 1000 })
            assertEquals(2437, info.frequencyMHz)
            assertEquals(6, info.channel)
            assertEquals("2.4 GHz", info.bandLabel)
            assertTrue(info.ssid.startsWith("DIRECT-dp"))
            assertEquals(radio.requests.last()!!.passphrase, info.passphrase)
            assertTrue(logs.any { it.contains("ready mode=FIXED_2_GHZ") })
            assertTrue(logs.any { it.contains("requestedMHz=2437 actualMHz=2437 matched=true") })
            assertTrue(logs.all { DiagnosticRedactor.redact(it) != null })
            assertFalse(logs.any { it.contains(info.ssid) || it.contains(info.passphrase) })
        }
        assertEquals(1, radio.removals)
        assertTrue(context.getSystemService(WifiManager::class.java).isWifiEnabled)
        assertEquals(5180, context.getSystemService(WifiManager::class.java).connectionInfo.frequency)
    }

    @Test fun twoGhzStationAlignsImmediatelyWithoutFiveGhzOrAutomaticRequests() {
        shadowOf(context.getSystemService(WifiManager::class.java).connectionInfo).setFrequency(2462)
        radio.fixed24Only = true
        WifiP2pGroupManager(context).use { manager ->
            val info = background { manager.start(5000) }
            assertEquals(listOf(2462), radio.requests.map { it?.groupOwnerBand })
            assertEquals(11, info.channel)
        }
    }

    @Test fun channelRejectionsReachChannelElevenWithoutAutomaticSelection() {
        radio.fixed24Only = true
        radio.allowed24 = setOf(2462)
        WifiP2pGroupManager(context).use { manager ->
            val info = background { manager.start(6000) }
            assertEquals(listOf(5180, 2437, 2412, 2462), radio.requests.map { it?.groupOwnerBand })
            assertEquals(11, info.channel)
        }
    }

    @Test fun reportedChannelIsUsedWhenFirmwareIgnoresTheRequestedFrequency() {
        radio.fixed24Only = true
        radio.reportedFrequency = 2412
        val logs = mutableListOf<String>()
        WifiP2pGroupManager(context, logs::add).use { manager ->
            val info = background { manager.start(6000) }
            assertEquals(1, info.channel)
            assertTrue(logs.any { it.contains("requestedMHz=2437 actualMHz=2412 matched=false") })
        }
    }

    @Test fun unknownExistingGroupIsNeverRemovedOrReplaced() {
        radio.group = radio.makeGroup(null)
        WifiP2pGroupManager(context).use { manager ->
            assertTrue(failure { manager.start(3000) } is P2pResetRequiredException)
        }
        assertTrue(radio.requests.isEmpty())
        assertEquals(0, radio.removals)
    }

    // DiPlay's process ended during a session and left its group. A Galaxy Note20 (Android 13)
    // answered group info only about 0.6 s after removing it, past the 0.5 s poll.
    @Test fun aRetainedOwnGroupIsReclaimedWhenTheAnswerComesAfterItsRemoval() {
        retainOwnGroup()
        radio.groupInfoAfterRemovalMillis = 700
        WifiP2pGroupManager(context).use { manager -> background { manager.start(5000) } }
        assertEquals(1, radio.requests.size)
    }

    @Test fun aRemovalThatIsNeverAnsweredStillFails() {
        retainOwnGroup()
        radio.groupInfoAfterRemovalMillis = null
        WifiP2pGroupManager(context).use { manager ->
            assertTrue(failure { manager.start(5000) }.message!!.contains("did not respond"))
        }
        assertTrue(radio.requests.isEmpty())
    }

    private fun retainOwnGroup() {
        val name = "DIRECT-dp000000000000-Ab12"
        context.getSharedPreferences("carplay_wifi_p2p", Context.MODE_PRIVATE).edit().putString("owned_ssid", name).commit()
        radio.group = radio.makeGroup(null).also { shadowOf(it).setNetworkName(name) }
    }

    @Test fun closeLeavesAnotherAppsReplacementGroupRunning() {
        val logs = mutableListOf<String>()
        val manager = WifiP2pGroupManager(context, logs::add)
        background { manager.start(5000) }
        val replacement = radio.makeGroup(null)
        radio.group = replacement
        background { manager.close() }
        assertSame(replacement, radio.group)
        assertEquals(0, radio.removals)
        assertTrue(logs.any { it.contains("cleanup skipped=another_app_owns_group") })
    }

    @Test fun connectionSnapshotDoesNotInterpretAnEmptyP2pListAsFailedIphoneAssociation() {
        val manager = WifiP2pGroupManager(context)
        background { manager.start(5000) }
        val snapshot = background { manager.connectionDiagnosticSnapshot() }
        assertTrue(snapshot.contains("p2pGroup=present owner=true sameGroup=true"))
        assertTrue(snapshot.contains("reportedP2pClients=0 association=unknown legacyClients=not_exposed"))
        radio.group = null
        assertEquals("p2pGroup=absent association=unknown", background { manager.connectionDiagnosticSnapshot() })
        background { manager.close() }
        assertEquals("p2pGroup=unavailable association=unknown", manager.connectionDiagnosticSnapshot())
    }

    @Test fun staleCloseCannotRemoveANewerDiPlaySession() {
        val old = WifiP2pGroupManager(context)
        val previous = background { old.start(5000) }
        // The framework has lost the old group; a new controller acquires a fresh one.
        radio.group = null
        val current = WifiP2pGroupManager(context)
        val next = background { current.start(5000) }
        assertNotEquals(previous.ssid, next.ssid)
        val replacement = radio.group
        background { old.close() }
        assertSame(replacement, radio.group)
        assertEquals(0, radio.removals)
        background { current.close() }
        assertEquals(1, radio.removals)
    }

    @Test fun groupAppearingAfterRejectionIsNotRemovedDuringFallback() {
        radio.rejectCustom = true
        radio.competingGroup = true
        WifiP2pGroupManager(context).use { manager ->
            assertTrue(failure { manager.start(3000) } is P2pResetRequiredException)
        }
        assertEquals(1, radio.requests.size)
        assertEquals(0, radio.removals)
    }

    @Test fun missingSystemCredentialsDoNotReuseTheRejectedCustomCredentials() {
        radio.rejectCustom = true
        radio.missingCredentials = true
        WifiP2pGroupManager(context).use { manager ->
            assertTrue(failure { manager.start(4000) }.message!!.contains("usable Wi-Fi P2P group"))
        }
        assertEquals(6, radio.requests.size)
        assertEquals(1, radio.removals)
    }

    @Test fun unansweredCreateIsNotFollowedByASecondRequest() {
        radio.noCreateReply = true
        WifiP2pGroupManager(context).use { manager ->
            assertTrue(failure { manager.start(300) }.message!!.contains("group creation"))
        }
        assertEquals(1, radio.requests.size)
        assertEquals(0, radio.removals)
    }

    @Test fun disabledWifiHasAnActionableErrorWithoutCreatingAnything() {
        context.getSystemService(WifiManager::class.java).isWifiEnabled = false
        WifiP2pGroupManager(context).use { manager ->
            assertTrue(failure { manager.start(3000) }.message!!.contains("Turn on Wi-Fi"))
        }
        assertTrue(radio.requests.isEmpty())
        assertEquals(0, radio.removals)
    }

    private fun failure(block: () -> Any): Throwable {
        try { background(block); fail("Expected failure") }
        catch (failure: ExecutionException) { return failure.cause!! }
        error("unreachable")
    }

    private fun <T> background(block: () -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { block() }.get(10, TimeUnit.SECONDS) }
        finally { executor.shutdownNow() }
    }

    // Robolectric's manager creates a channel without the framework's AsyncChannel transport.
    @Implements(WifiP2pManager.Channel::class)
    class P2pChannel {
        @Implementation protected fun close() {}
    }

    @Implements(WifiP2pManager::class)
    class P2pRadio : ShadowWifiP2pManager() {
        val requests = mutableListOf<WifiP2pConfig?>()
        var group: WifiP2pGroup? = null
        var removals = 0
        var rejectCustom = false
        var competingGroup = false
        var missingCredentials = false
        var noCreateReply = false
        var fixed24Only = false
        var allowed24 = setOf(2412, 2437, 2462)
        var reportedFrequency: Int? = null

        @Implementation protected fun requestP2pState(channel: WifiP2pManager.Channel, listener: WifiP2pManager.P2pStateListener) {
            listener.onP2pStateAvailable(WifiP2pManager.WIFI_P2P_STATE_ENABLED)
        }

        /** After a removal, group info is answered this late, or never when null; 0 answers at once. */
        var groupInfoAfterRemovalMillis: Long? = 0
        private var removedAtNanos = 0L

        @Implementation override fun requestGroupInfo(channel: WifiP2pManager.Channel, listener: WifiP2pManager.GroupInfoListener) {
            if (removals == 0) return listener.onGroupInfoAvailable(group)
            val after = groupInfoAfterRemovalMillis ?: return
            val waitMillis = after - (System.nanoTime() - removedAtNanos) / 1_000_000
            if (waitMillis <= 0) return listener.onGroupInfoAvailable(group)
            Thread { Thread.sleep(waitMillis); listener.onGroupInfoAvailable(group) }.start()
        }

        @Implementation override fun requestConnectionInfo(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ConnectionInfoListener) {
            listener.onConnectionInfoAvailable(WifiP2pInfo().apply {
                groupFormed = true
                isGroupOwner = true
                groupOwnerAddress = InetAddress.getByName("192.168.49.1")
            })
        }

        @Implementation override fun createGroup(channel: WifiP2pManager.Channel, config: WifiP2pConfig?, listener: WifiP2pManager.ActionListener) {
            requests += config
            if (noCreateReply) return
            if ((rejectCustom && config != null) || (fixed24Only && config?.groupOwnerBand !in allowed24)) {
                if (competingGroup) group = makeGroup(null)
                listener.onFailure(WifiP2pManager.ERROR)
            } else {
                group = makeGroup(config)
                listener.onSuccess()
            }
        }

        @Implementation override fun removeGroup(channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) {
            removals++
            removedAtNanos = System.nanoTime()
            group = null
            listener.onSuccess()
        }

        fun makeGroup(config: WifiP2pConfig?) = WifiP2pGroup().apply {
            shadowOf(this).setIsGroupOwner(true)
            shadowOf(this).setNetworkName(config?.networkName ?: "DIRECT-system-test")
            shadowOf(this).setPassphrase(if (missingCredentials) null else config?.passphrase ?: "test-generated-key")
            shadowOf(this).setInterface("p2p-test-missing")
            ReflectionHelpers.setField(this, "mFrequency", reportedFrequency ?: config?.groupOwnerBand ?: 2437)
        }
    }
}
