package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.orchestration.ConnectionFailureCause
import org.junit.Assert.*
import org.junit.Test
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress

class ManualHotspotReadinessTest {
    private val ipv4 = InetAddress.getByName("192.168.43.1")
    private val ipv6 = InetAddress.getByName("fe80::1234")
    private fun iface(name: String = "wlan0", index: Int = 7, up: Boolean = true,
        addresses: List<InetAddress> = listOf(ipv4)) =
        HotspotInterfaceSnapshot(name, index, up, addresses, wirelessInterfaceName(name))
    private fun snapshot(vararg interfaces: HotspotInterfaceSnapshot, ap: Set<String>? = null,
        wifi: Set<String>? = emptySet(), default: String? = null, consistent: Boolean = true) =
        HotspotNetworkSnapshot(interfaces.toList(), ap, wifi, default, consistent)
    private fun select(value: HotspotNetworkSnapshot) = selectHotspotInterface(value) {}
    private var now = 0L
    private fun await(timeout: Long = 1_500, cancel: () -> Boolean = { false },
        sample: () -> HotspotNetworkSnapshot): HotspotSelection = ManualHotspotReadiness(
        sample, cancel, { now += it }, { now },
    ).await(timeout)

    @Test fun ethernetFirstWaitsForStableWireless() {
        val selected = await {
            if (now < 500) snapshot(iface("eth0")) else snapshot(iface("eth0"), iface())
        }
        assertEquals("wlan0", selected.name)
        assertEquals(1_000L, now)
    }

    @Test fun ethernetWithoutOwnershipTimesOut() {
        val error = assertThrows(WirelessStartupException::class.java) { await { snapshot(iface("eth0")) } }
        assertEquals(WirelessStartupFailure.HOTSPOT_NOT_READY, error.reason)
        assertEquals(1_500L, now)
    }

    @Test fun platformOwnershipAllowsVendorBridgeAndEthernet() {
        for (name in listOf("eth0", "br0", "vendor_radio")) {
            assertEquals(name, select(snapshot(iface(name), ap = setOf(name)))?.name)
        }
    }

    @Test fun staApConcurrencyRejectsWifiUpstream() {
        for (ap in listOf("wlan1", "ap0", "swlan0")) {
            assertEquals(ap, select(snapshot(iface(), iface(ap), wifi = setOf("wlan0"), default = "wlan0"))?.name)
        }
    }

    @Test fun absentDefaultNetworkDoesNotHideAnObservedSta() {
        assertNull(select(snapshot(iface(), wifi = setOf("wlan0"))))
        assertEquals("wlan0", select(snapshot(iface()))?.name)
    }

    @Test fun changingDefaultResamplesThenAllowsStableApEvidenceConflict() {
        val selected = await {
            snapshot(iface(), ap = setOf("wlan0"), wifi = setOf("wlan0"), default = "wlan0",
                consistent = now >= 500)
        }
        assertEquals("wlan0", selected.name)
        assertEquals(1_000L, now)
    }

    @Test fun alreadyRunningHotspotDoesNotNeedANewInterface() {
        assertEquals("wlan0", await { snapshot(iface()) }.name)
        assertEquals(500L, now)
    }

    @Test fun dualStackPrefersIpv4AndKeepsTheScopedLinkLocalFallback() {
        assertEquals(ipv4, select(snapshot(iface()))?.address)
        val onlyIpv6 = select(snapshot(iface(index = 9, addresses = listOf(ipv6))))!!
        assertEquals(9, (onlyIpv6.address as Inet6Address).scopeId)
        assertTrue(onlyIpv6.address.isLinkLocalAddress)
        assertEquals(ipv4, select(snapshot(iface(index = 9, addresses = listOf(ipv4, ipv6))))?.address)
    }

    @Test fun addressAndIdentityChangesRestartStability() {
        val selected = await {
            when (now) {
                0L -> snapshot(iface())
                250L -> snapshot(iface(up = false))
                500L -> snapshot(iface(index = 8))
                else -> snapshot(iface(index = 8, addresses = listOf(ipv6)))
            }
        }
        assertEquals(1_250L, now)
        assertEquals(8, (selected.address as Inet6Address).scopeId)
    }

    @Test fun missingPermissionsDoNotPretendUpstreamsAreAbsent() {
        assertNull(select(snapshot(iface(), wifi = null)))
        assertEquals("wlan0", select(snapshot(iface(), wifi = null, ap = setOf("wlan0")))?.name)
        assertNull(select(snapshot(iface(), ap = emptySet())))
    }

    @Test fun vehicleRuntimeWlanDoesNotNeedToMatchStaleFirmwareRegexes() {
        // 实车：SoftApManager 使用 wlan0，但固件 tetherableWifiRegexs 只有 softap0/wigig0。
        val ap = legacyHotspotInterfaces(listOf("wlan0"), listOf("softap0", "wigig0"))
        assertNull(ap)
        val selected = await {
            snapshot(iface("eth0", 37), iface("wlan0", 31, addresses = listOf(ipv4, ipv6)),
                ap = ap, default = "rmnet_data5")
        }
        assertEquals("wlan0", selected.name)
        assertEquals(ipv4, selected.address)
        assertEquals(500L, now)
    }

    @Test fun unmatchedLegacyNamesStillCannotAuthorizeEthernetOrSta() {
        val ap = legacyHotspotInterfaces(listOf("eth0"), listOf("softap0", "wigig0"))
        assertNull(ap)
        assertNull(select(snapshot(iface("eth0"), ap = ap)))
        assertNull(select(snapshot(iface(), ap = ap, wifi = setOf("wlan0"))))
    }

    @Test fun matchedLegacyPlatformOwnershipStillAllowsVendorInterface() {
        val ap = legacyHotspotInterfaces(listOf("vendor_ap"), listOf("vendor_.*"))
        assertEquals(setOf("vendor_ap"), ap)
        assertEquals("vendor_ap", select(snapshot(iface("vendor_ap"), ap = ap))?.name)
    }

    @Test fun emptyRuntimeListAndUnavailablePatternsRemainDistinct() {
        assertEquals(emptySet<String>(), legacyHotspotInterfaces(emptyList(), listOf("wlan.*")))
        assertNull(legacyHotspotInterfaces(listOf("wlan0"), emptyList()))
    }

    @Test fun cancelledGenerationStopsAtNextWakeWithoutSelecting() {
        assertThrows(InterruptedIOException::class.java) {
            await(cancel = { now >= 250 }) { snapshot(iface()) }
        }
        assertEquals(250L, now)
    }

    @Test fun cancellationDuringSamplingCannotReturnReady() {
        var cancelled = false
        assertThrows(InterruptedIOException::class.java) {
            await(cancel = { cancelled }) {
                if (now == 500L) cancelled = true
                snapshot(iface())
            }
        }
    }

    @Test fun switchedOffOrMissingAddressesAreNotReady() {
        assertNull(select(snapshot(iface()).copy(apEnabled = false)))
        assertNull(select(snapshot(iface(addresses = emptyList()))))
        assertNull(select(snapshot(iface(addresses = listOf(InetAddress.getByName("127.0.0.1"))))))
    }

    private fun notReady(savedInterface: String?, sample: HotspotNetworkSnapshot): WirelessStartupException {
        now = 0
        val error = assertThrows(WirelessStartupException::class.java) {
            ManualHotspotReadiness({ sample }, { false }, { now += it }, { now }, configuredInterface = savedInterface).await(1_500)
        }
        assertEquals(WirelessStartupFailure.HOTSPOT_NOT_READY, error.reason)
        assertEquals("Hotspot network is not ready", error.message)
        return error
    }

    @Test fun aSavedInterfaceTheCarDoesNotHaveIsNamed() {
        assertEquals(ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND,
            notReady("wlan9", snapshot(iface(), ap = setOf("wlan9"))).knownCause)
    }

    @Test fun theInterfaceIsNamedOnlyWhenTheHotspotIsOnAndTheSavedNameIsMissing() {
        val missing = snapshot(iface(), ap = setOf("wlan9"))
        assertNull(notReady(null, snapshot(iface("eth0"))).knownCause)
        assertNull(notReady("", snapshot(iface("eth0"))).knownCause)
        assertNull(notReady("wlan9", missing.copy(apEnabled = null)).knownCause)
        assertNull(notReady("wlan9", missing.copy(apEnabled = false)).knownCause)
        assertNull(notReady("eth0.3", snapshot(iface(), iface("eth0.3", addresses = emptyList()), ap = setOf("eth0.3"))).knownCause)
    }

    @Test fun aFailedInterfaceListingNamesNoInterface() {
        assertNull(notReady("wlan9", snapshot(ap = setOf("wlan9"))).knownCause)
    }
}
