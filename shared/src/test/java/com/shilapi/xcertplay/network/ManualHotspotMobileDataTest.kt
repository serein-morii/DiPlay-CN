package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The modem's mobile-data interfaces never stand in for the car hotspot (#573's search, #921's interfaces). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ManualHotspotMobileDataTest {
    private fun iface(name: String, index: Int, vararg addresses: String) = HotspotInterfaceSnapshot(
        name, index, true, addresses.map(InetAddress::getByName), wirelessInterfaceName(name))

    private val wlan0 = iface("wlan0", 30, "192.168.43.1")
    private val rmnetData0 = iface("rmnet_data0", 9, "10.120.5.7", "fe80::5")
    private val rRmnetData4 = iface("r_rmnet_data4", 24, "10.120.5.9")
    private val ccmni0 = iface("ccmni0", 31, "10.33.8.2")

    @Test fun mobileDataInterfacesAreKnownByTheirNames() {
        for (name in listOf("rmnet0", "rmnet_data0", "rmnet_data8", "rmnet_ipa0", "r_rmnet_data5", "ccmni0", "ccmni2")) {
            assertTrue(name, mobileDataInterfaceName(name))
        }
        for (name in listOf("eth0", "eth0.3", "wlan0", "swlan0", "br0", "p2p-wlan0-0")) {
            assertFalse(name, mobileDataInterfaceName(name))
        }
    }

    @Test fun aVlanStillWinsOverMobileData() {
        val vlan = iface("eth0.5", 12, "192.168.5.1")
        val ap = apInterfaces(emptySet(), listOf(rmnetData0, rRmnetData4, ccmni0, vlan))
        assertEquals(setOf("eth0.5"), ap)
        assertEquals("eth0.5", select(ap, rmnetData0, rRmnetData4, ccmni0, vlan))
    }

    @Test fun mobileDataAloneLeavesTheEmptySetEmpty() {
        val ap = apInterfaces(emptySet(), listOf(rmnetData0, wlan0))
        assertEquals(emptySet<String>(), ap)
        assertNull(select(ap, rmnetData0, wlan0))
    }

    private fun select(ap: Set<String>?, vararg interfaces: HotspotInterfaceSnapshot) = selectHotspotInterface(
        HotspotNetworkSnapshot(interfaces.toList(), ap, emptySet(), "rmnet_data2", consistent = true, apEnabled = true)) {}?.name

    @Suppress("UNCHECKED_CAST")
    private fun apInterfaces(observed: Set<String>?, interfaces: List<HotspotInterfaceSnapshot>): Set<String>? =
        ManualHotspotInterfaces::class.java.getDeclaredMethod("apInterfaces", Set::class.java, List::class.java,
            java.lang.Boolean::class.java, Set::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(ManualHotspotInterfaces(RuntimeEnvironment.getApplication()), observed, interfaces, true,
                emptySet<String>(), "rmnet_data2") as Set<String>?
}
