package com.shilapi.xcertplay

import android.content.Context
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.network.CarPlayBonjour
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.PairingStore
import java.net.ServerSocket
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayBonjourDualStackTest {
    private val context = mock(Context::class.java)
    private val wifi = mock(WifiManager::class.java)
    private val lock = mock(WifiManager.MulticastLock::class.java)
    private val ipv4 = InetAddress.getByName("192.0.2.10")
    private val ipv6 = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, 7)
    private val config = AirPlayConfig(deviceName = "DiPlay", deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02", sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test-pairing")

    private fun setup() {
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSystemService(WifiManager::class.java)).thenReturn(wifi)
        `when`(wifi.createMulticastLock("carplay-bonjour")).thenReturn(lock)
        `when`(lock.isHeld).thenReturn(true)
    }

    @Test fun localHostNamesAreAvailableBeforeJmDnsStartsForBothFamilies() {
        setup()
        val hostName = "carplay-020000000002"
        val received = mutableListOf<InetAddress>()
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(any(InetAddress::class.java), eq(hostName)) }
                .thenAnswer { invocation ->
                    val address = invocation.getArgument<InetAddress>(0)
                    // toString does not query DNS; check the cached name before reading hostName.
                    assertEquals("$hostName/${address.hostAddress}", address.toString())
                    assertEquals(hostName, address.hostName)
                    received.add(address)
                    mock(JmDNS::class.java)
                }
            CarPlayBonjour(context, config, identity, ipv4.hostAddress, true,
                additionalAddresses = listOf(ipv6)).use { it.start() }
        }
        assertEquals(listOf(ipv4, ipv6), received)
        assertEquals(ipv6.scopeId, (received[1] as Inet6Address).scopeId)
    }

    @Test fun publishesAndBrowsesBothFamiliesAndClosesBothRegistries() {
        setup()
        val v4 = mock(JmDNS::class.java)
        val v6 = mock(JmDNS::class.java)
        `when`(v4.inetAddress).thenReturn(ipv4)
        `when`(v6.inetAddress).thenReturn(ipv6)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(v6)
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenReturn(v4)
            val bonjour = CarPlayBonjour(context, config, identity, ipv6.hostAddress, true,
                additionalAddresses = listOf(ipv4))
            bonjour.start()
            assertTrue(bonjour.diagnosticSnapshot().contains("mdnsFamilies=IPv6,IPv4"))
            listOf(v6, v4).forEach { dns ->
                verify(dns).registerService(any(ServiceInfo::class.java))
                verify(dns).addServiceListener(eq("_carplay-ctrl._tcp.local."), any(ServiceListener::class.java))
            }
            bonjour.close()
            bonjour.close()
            verify(v6).close()
            verify(v4).close()
            verify(lock).release()
        }
    }

    @Test fun registryAddressControlsFamilyEvenWhenDeprecatedInterfaceReturnsTheOtherFamily() {
        setup()
        val dns = mock(JmDNS::class.java)
        `when`(dns.inetAddress).thenReturn(ipv4)
        `when`(dns.getInterface()).thenReturn(ipv6) // Android's arbitrary interface address.
        val info = mock(ServiceInfo::class.java)
        `when`(info.inetAddresses).thenReturn(arrayOf(InetAddress.getByName("192.0.2.20")))
        `when`(info.port).thenReturn(7000)
        val event = mock(javax.jmdns.ServiceEvent::class.java)
        `when`(event.dns).thenReturn(dns)
        `when`(event.info).thenReturn(info)
        `when`(event.name).thenReturn("test-phone")
        CarPlayBonjour(context, config, identity, ipv6.hostAddress, true,
            additionalAddresses = listOf(ipv4)).use { bonjour ->
            val listener = ReflectionHelpers.getField<ServiceListener>(bonjour, "interfaceListener")
            listener.serviceResolved(event)
            val queue = ReflectionHelpers.getField<java.util.concurrent.LinkedBlockingQueue<*>>(bonjour, "interfaceServices")
            assertEquals(1, queue.size)
            verify(dns, never()).getInterface()
        }
    }

    @Test fun dualLanThenSingleP2pThenDualLanServesAirPlayInfo() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        var activeSessions = 0
        try {
            val service = controller.get()
            for (extras in listOf(listOf(InetAddress.getByName("127.0.0.1")), emptyList(),
                                  listOf(InetAddress.getByName("127.0.0.1")))) {
                assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                    InetAddress.getByName("::1"), config.copy(port = 0), identity, PairingStore(), null,
                    object : AirPlaySessionListener {
                        override fun onDebugLog(message: String) { events.add(message) }
                        override fun onSessionActive(session: com.shilapi.xcertplay.airplay.AirPlaySession) { activeSessions++ }
                    }, object : AirPlayMediaHandler {}, extras))
                for (address in listOf(InetAddress.getByName("::1")) + extras) {
                    java.net.Socket(address, service.boundPort()!!).use { socket ->
                        socket.soTimeout = 3000
                        socket.getOutputStream().write("GET /info HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray())
                        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        assertEquals("HTTP/1.1 200 OK", input.readLine())
                        var length = 0
                        while (true) {
                            val line = input.readLine()
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:")) length = line.substringAfter(':').trim().toInt()
                        }
                        val body = CharArray(length)
                        var offset = 0
                        while (offset < length) { val read = input.read(body, offset, length - offset); assertTrue(read > 0); offset += read }
                        assertTrue(String(body).startsWith("bplist00"))
                    }
                }
                assertTrue(service.isAttached())
                service.detach()
            }
            assertEquals(0, activeSessions)
            assertEquals(5, events.count { it.startsWith("airplay rx GET /info") })
        } finally { controller.destroy() }
    }

    @Test fun probesSelectMatchingLocalFamilyAndApplyIpv6InterfaceScope() {
        setup()
        val bonjour = CarPlayBonjour(context, config, identity, ipv6.hostAddress, true,
            additionalAddresses = listOf(ipv4))
        fun invoke(name: String, target: InetAddress): InetAddress = ReflectionHelpers.callInstanceMethod(
            bonjour, name, ClassParameter.from(InetAddress::class.java, target))
        assertEquals(ipv4, invoke("sourceAddressFor", InetAddress.getByName("192.0.2.20")))
        assertEquals(ipv6, invoke("sourceAddressFor", InetAddress.getByName("fe80::20")))
        assertEquals(7, (invoke("applyLocalScope", InetAddress.getByName("fe80::20")) as Inet6Address).scopeId)
        bonjour.close()
    }

    @Test fun defaultSingleAddressPublicationRemainsUnchanged() {
        setup()
        val dns = mock(JmDNS::class.java)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(dns)
            CarPlayBonjour(context, config, identity, ipv6.hostAddress, true).use {
                it.start()
                assertTrue(it.diagnosticSnapshot().contains("mdnsFamilies=IPv6"))
                verify(dns).registerService(any(ServiceInfo::class.java))
            }
            factory.verify({ JmDNS.create(ipv6, "carplay-020000000002") }, times(1))
            factory.verifyNoMoreInteractions()
            verify(dns).close()
        }
        verify(context, never()).getSystemService(Context.NSD_SERVICE)
    }

    @Test fun serviceDetachesBothSamePortListenersOnAndroid10() {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                InetAddress.getByName("::1"), config.copy(port = 0), identity, PairingStore(), null,
                object : AirPlaySessionListener {}, object : AirPlayMediaHandler {},
                listOf(InetAddress.getByName("127.0.0.1"))))
            val primary = ReflectionHelpers.getField<ServerSocket>(service, "serverSocket")
            val extra = ReflectionHelpers.getField<List<ServerSocket>>(service, "additionalServers").single()
            assertEquals(primary.localPort, extra.localPort)
            service.detach()
            assertTrue(primary.isClosed)
            assertTrue(extra.isClosed)
            assertFalse(service.isAttached())
            service.detach()
        } finally { controller.destroy() }
    }

    @Test fun secondRegistryFailureCleansUpTheFirstAndMulticastLock() {
        setup()
        val first = mock(JmDNS::class.java)
        mockStatic(JmDNS::class.java).use { factory ->
            factory.`when`<JmDNS> { JmDNS.create(ipv6, "carplay-020000000002") }.thenReturn(first)
            factory.`when`<JmDNS> { JmDNS.create(ipv4, "carplay-020000000002") }.thenThrow(IOException("bind failed"))
            val bonjour = CarPlayBonjour(context, config, identity, ipv6.hostAddress, true,
                additionalAddresses = listOf(ipv4))
            assertThrows(IOException::class.java) { bonjour.start() }
            verify(first).close()
            verify(lock).release()
        }
    }
}
