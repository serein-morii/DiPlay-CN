package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.CarPlayVpnService.Companion.TunnelCandidate
import com.shilapi.xcertplay.network.CarPlayVpnService.Companion.selectTunnelInterface
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Android 7.x cannot bind an unscoped link-local listener; the owning tunnel supplies the scope. */
class CarPlayVpnListenerScopeTest {
    private val wired = InetAddress.getByName("fe80::2") as Inet6Address

    @Test fun picksTheInterfaceThatOwnsTheWiredAddress() {
        val chosen = selectTunnelInterface(wired, listOf(
            TunnelCandidate("wlan0", 3, listOf(InetAddress.getByName("fe80::1234"))),
            TunnelCandidate("tun0", 12, listOf(Inet6Address.getByAddress(null, wired.address, 12))),
        ))
        assertEquals(12, chosen?.index)
    }

    @Test fun prefersTunWhenAnotherInterfaceCarriesTheSameAddress() {
        val chosen = selectTunnelInterface(wired, listOf(
            TunnelCandidate("usb0", 5, listOf(InetAddress.getByName("fe80::2"))),
            TunnelCandidate("tun1", 14, listOf(InetAddress.getByName("fe80::2"))),
        ))
        assertEquals("tun1", chosen?.name)
    }

    @Test fun returnsNullWhenNoInterfaceOwnsTheAddress() {
        assertNull(selectTunnelInterface(wired, listOf(
            TunnelCandidate("wlan0", 3, listOf(InetAddress.getByName("fe80::1234"))),
            TunnelCandidate("tun0", 0, listOf(InetAddress.getByName("fe80::2"))),
        )))
    }

    @Test fun selectedHostInterfaceScopesALinkLocalAddressThatBinds() {
        val candidates = Collections.list(NetworkInterface.getNetworkInterfaces()).map { networkInterface ->
            TunnelCandidate(networkInterface.name, networkInterface.index, Collections.list(networkInterface.inetAddresses))
        }
        val owned = candidates.flatMap { it.addresses }.filterIsInstance<Inet6Address>()
            .firstOrNull { it.isLinkLocalAddress }
        assumeTrue("Host has no link-local IPv6 address", owned != null)
        val unscoped = Inet6Address.getByAddress(null, owned!!.address, 0)

        val chosen = selectTunnelInterface(unscoped, candidates)

        assertNotNull(chosen)
        ServerSocket().use { server ->
            server.bind(InetSocketAddress(Inet6Address.getByAddress(null, unscoped.address, chosen!!.index), 0))
        }
    }
}
