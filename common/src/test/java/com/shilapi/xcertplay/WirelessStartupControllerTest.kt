package com.shilapi.xcertplay

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.network.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WirelessStartupControllerTest {
    private val statuses = mutableListOf<CarPlayStatus>()
    private fun controller(
        chosenIphone: String? = null,
        mode: WirelessHotspotMode = WirelessHotspotMode.WIFI_P2P,
    ) = CarPlayController(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
    },
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRELESS,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
            wirelessBluetoothDeviceAddress = chosenIphone, wirelessHotspotMode = mode,
            manualHotspotSsid = "Car", manualHotspotPassphrase = "12345678"),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, statuses::add)

    private fun fail(controller: CarPlayController, generation: Int, error: Throwable) {
        controller.javaClass.getDeclaredMethod("fail", Throwable::class.java, Int::class.javaObjectType)
            .apply { isAccessible = true }.invoke(controller, error, generation)
    }

    @Test fun timeoutAndCleanupExceptionProduceOnlyOneTypedFailure() {
        val controller = controller()
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            fail(controller, 0, IOException("Bluetooth socket closed"))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, statuses.size)
            assertEquals(WirelessStartupFailure.FIRST_TCP_TIMEOUT, (statuses.single() as CarPlayStatus.Failed).startupFailure)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun queuedOldFailureAndOldCleanupDoNotAffectNewGeneration() {
        val controller = controller()
        var closed = 0
        val hotspot = object : WirelessHotspotManager {
            override fun start(timeoutMillis: Long): WirelessHotspotInfo = error("Not started")
            override fun close() { closed++ }
        }
        try {
            fail(controller, 0, WirelessStartupException(WirelessStartupFailure.FIRST_TCP_TIMEOUT, "timeout"))
            ReflectionHelpers.getField<AtomicInteger>(controller, "wirelessGeneration").set(1)
            ReflectionHelpers.setField(controller, "hotspot", hotspot)
            controller.javaClass.getDeclaredMethod("closeWirelessStack", CarPlayVpnService::class.java, Int::class.javaObjectType)
                .apply { isAccessible = true }.invoke(controller, null, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
            assertEquals(0, closed)
            assertSame(hotspot, ReflectionHelpers.getField(controller, "hotspot"))
            fail(controller, 0, IOException("late old failure"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(statuses.isEmpty())
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun aKnownCauseInTheChainIsNamedWithoutBecomingAStartupFailure() {
        val controller = controller()
        try {
            fail(controller, 0, IOException("Could not establish WIFI_P2P hotspot: no channel",
                ConnectionFailureException(ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE, "no channel")))
            shadowOf(Looper.getMainLooper()).idle()
            val failure = statuses.single() as CarPlayStatus.Failed
            assertEquals(ConnectionFailureCause.WIFI_DIRECT_CHANNEL_UNAVAILABLE, failure.knownCause)
            assertNull(failure.startupFailure)
            assertEquals("Could not establish WIFI_P2P hotspot: no channel", failure.message)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test fun aStartupFailureCarriesItsKnownCause() {
        val controller = controller()
        try {
            fail(controller, 0, IOException("Could not establish MANUAL hotspot: Hotspot network is not ready",
                WirelessStartupException(WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
                    ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND)))
            shadowOf(Looper.getMainLooper()).idle()
            val failure = statuses.single() as CarPlayStatus.Failed
            assertEquals(WirelessStartupFailure.HOTSPOT_NOT_READY, failure.startupFailure)
            assertEquals(ConnectionFailureCause.HOTSPOT_INTERFACE_NOT_FOUND, failure.knownCause)
            assertEquals("Could not establish MANUAL hotspot: Hotspot network is not ready", failure.message)
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    private fun hotspotStartFailure(): WirelessStartupException {
        val controller = controller(mode = WirelessHotspotMode.MANUAL)
        try {
            return assertThrows(InvocationTargetException::class.java) {
                controller.javaClass.getDeclaredMethod("startWirelessHotspot", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(controller, 0)
            }.cause as WirelessStartupException
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }

    @Test @Config(shadows = [CarHotspotOffConnectTest.HotspotState::class])
    fun aCarHotspotReportedOffIsNamed() {
        CarHotspotOffConnectTest.HotspotState.on = false
        val failure = hotspotStartFailure()
        assertEquals(WirelessStartupFailure.HOTSPOT_CONFIGURATION, failure.reason)
        assertEquals("The car hotspot is off. Turn it on in the car settings and connect again.", failure.message)
        assertEquals(ConnectionFailureCause.CAR_HOTSPOT_OFF, failure.knownCause)
    }

    @Test @Config(shadows = [Tethering::class])
    fun aCarHotspotThatDidNotStartIsNamed() {
        CarHotspotSettings.setEnabled(RuntimeEnvironment.getApplication(), true)
        for ((result, reason, cause) in listOf(
            Triple(CarHotspotTethering.Result.TIMED_OUT, WirelessStartupFailure.HOTSPOT_NOT_READY,
                ConnectionFailureCause.CAR_HOTSPOT_START_TIMED_OUT),
            Triple(CarHotspotTethering.Result.FAILED, WirelessStartupFailure.HOTSPOT_NOT_READY,
                ConnectionFailureCause.CAR_HOTSPOT_START_FAILED),
            Triple(CarHotspotTethering.Result.PERMISSION_REQUIRED, WirelessStartupFailure.HOTSPOT_CONFIGURATION, null),
        )) {
            Tethering.result = result
            val failure = hotspotStartFailure()
            assertEquals(reason, failure.reason)
            assertEquals("${result.diagnostic}. Open the car hotspot settings and connect again.", failure.message)
            assertEquals(cause, failure.knownCause)
        }
    }

    @Implements(className = "com.shilapi.xcertplay.network.CarHotspotTethering", isInAndroidSdk = false)
    class Tethering {
        @Implementation fun enable(context: Context, isCancelled: () -> Boolean, timeoutMillis: Long,
            log: (String) -> Unit): CarHotspotTethering.Result = result

        companion object {
            @Volatile var result = CarHotspotTethering.Result.READY
        }
    }

    @Test fun theIphoneChoiceSaysWhyNoIphoneWasPicked() {
        val iPhone = "iPhone" to "00:11:22:33:44:01"
        assertCause(ConnectionFailureCause.CHOSEN_IPHONE_NOT_PAIRED,
            "The selected iPhone is no longer paired. Choose it again in DiPlay.", selectionFailure("00:11:22:33:44:99", iPhone))
        val noneBonded = "No unambiguous bonded iPhone found; pair one iPhone and retry"
        assertCause(ConnectionFailureCause.NO_PAIRED_IPHONE, noneBonded, selectionFailure(null))
        assertCause(ConnectionFailureCause.IPHONE_NOT_CHOSEN,
            "Multiple bonded iPhones found and none is currently connected; connect one iPhone and retry",
            selectionFailure(null, iPhone, "Work iPhone" to "00:11:22:33:44:02"))
        assertCause(ConnectionFailureCause.IPHONE_NOT_CHOSEN, noneBonded,
            selectionFailure(null, "Headset" to "00:11:22:33:44:03", "Pixel" to "00:11:22:33:44:04"))
    }

    private fun assertCause(cause: ConnectionFailureCause, message: String, failure: Throwable) {
        assertEquals(message, failure.message)
        assertEquals(cause, (failure as? ConnectionFailureException)?.reason)
    }

    private fun selectionFailure(chosenIphone: String?, vararg bonded: Pair<String, String>): Throwable {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        // No profile service answers in a test, so each profile query would wait out its timeout.
        // Setting any proxy, even null, makes Robolectric's getProfileProxy return false for every profile left unset.
        shadowOf(adapter).setProfileProxy(BluetoothProfile.HEADSET, null)
        shadowOf(adapter).setBondedDevices(bonded.mapTo(mutableSetOf()) { (name, address) ->
            adapter.getRemoteDevice(address).also { shadowOf(it).setName(name) }
        })
        val controller = controller(chosenIphone)
        try {
            return assertThrows(InvocationTargetException::class.java) {
                controller.javaClass.getDeclaredMethod("selectWirelessBluetoothDevice", BluetoothAdapter::class.java)
                    .apply { isAccessible = true }.invoke(controller, adapter)
            }.cause!!
        } finally { controller.close(); controller.awaitClosed(2_000) }
    }
}
