package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The AP set [ManualHotspotInterfaces] hands to readiness: a hotspot-bearing wired interface (#573) is looked
 * for only when the platform reports no tethered Wi-Fi interface, never when it cannot tell.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ManualHotspotOwnershipTest {
    private val wlan0 = HotspotInterfaceSnapshot("wlan0", 31, true, listOf(InetAddress.getByName("192.168.43.1")), true)
    private val eth0 = HotspotInterfaceSnapshot("eth0", 37, true,
        listOf(InetAddress.getByName("192.168.1.10"), InetAddress.getByName("fe80::1")), false)

    @Test fun anUnobservableTetherSetStaysUnobservable() {
        // The car of vehicleRuntimeWlanDoesNotNeedToMatchStaleFirmwareRegexes: its hotspot runs on wlan0, while the
        // firmware's regexes name only softap0 and wigig0. Its eth0 must not become the hotspot.
        val observed = legacyHotspotInterfaces(listOf("wlan0"), listOf("softap0", "wigig0"))
        assertNull(observed)
        val ap = apInterfaces(observed, listOf(eth0, wlan0))
        assertNull(ap)
        assertEquals("wlan0", selectHotspotInterface(snapshot(ap, eth0, wlan0)) {}?.name)
    }

    @Test fun anEmptyTetherSetStillFindsTheVlan() {
        // #573: getTetheredIfaces() = [] on the Lynk & Co 03, whose hotspot reaches Android on eth0.3.
        val observed = legacyHotspotInterfaces(emptyList(), listOf("wlan0", "softap0", "wigig0", "wifi_br0"))
        val vlan = HotspotInterfaceSnapshot("eth0.3", 12, true, listOf(InetAddress.getByName("198.18.34.15")), false)
        val ap = apInterfaces(observed, listOf(vlan, eth0))
        assertEquals(setOf("eth0.3"), ap)
        assertEquals("eth0.3", selectHotspotInterface(snapshot(ap, vlan, eth0)) {}?.name)
    }

    private fun snapshot(ap: Set<String>?, vararg interfaces: HotspotInterfaceSnapshot) = HotspotNetworkSnapshot(
        interfaces.toList(), ap, emptySet(), "rmnet_data5", consistent = true, apEnabled = true)

    @Suppress("UNCHECKED_CAST")
    private fun apInterfaces(observed: Set<String>?, interfaces: List<HotspotInterfaceSnapshot>): Set<String>? =
        ManualHotspotInterfaces::class.java.getDeclaredMethod("apInterfaces", Set::class.java, List::class.java,
            java.lang.Boolean::class.java, Set::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(ManualHotspotInterfaces(RuntimeEnvironment.getApplication()), observed, interfaces, true,
                emptySet<String>(), "rmnet_data5") as Set<String>?
}
