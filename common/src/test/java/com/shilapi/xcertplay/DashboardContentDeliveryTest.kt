package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardContentDeliveryTest {
    private class Fixture : AutoCloseable {
        val config = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480),
            cluster = AirPlayDisplayConfig(1600, 600, initialUrl = CarPlayClusterDisplay.Content.TURN_CARD.url))
        val controller = CarPlayController(RuntimeEnvironment.getApplication(),
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            config, AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {})
        private val phones = mutableListOf<AirPlaySession>()
        fun phone(setup: Boolean = true): AirPlaySession = AirPlaySession(Socket(), config,
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {
                override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>) = 1
            }).also {
                phones += it
                if (setup) setup(it)
            }
        fun activate(phone: AirPlaySession) = set(controller, "activeSession", phone)
        fun cornerMask(phone: AirPlaySession, png: ByteArray, width: Int = 800) {
            val listener = controller.javaClass.getDeclaredField("sessionListener").apply { isAccessible = true }
                .get(controller) as AirPlaySessionListener
            listener.onCornerMask(phone, png, width)
        }
        fun record(phone: AirPlaySession) {
            val listener = controller.javaClass.getDeclaredField("sessionListener").apply { isAccessible = true }
                .get(controller) as AirPlaySessionListener
            listener.onSessionActive(phone)
        }
        fun awaitCommands() {
            val executor = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
                .get(controller) as ExecutorService
            executor.submit {}.get(3, TimeUnit.SECONDS)
        }
        override fun close() {
            controller.close()
            controller.awaitClosed(2_000)
            phones.forEach { it.close() }
        }
    }

    private class EventSocket(private val onWrite: (() -> Unit)? = null) : Socket() {
        var writes = 0
        private val bytes = ByteArrayOutputStream()
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { bytes.write(value) }
            override fun write(data: ByteArray, offset: Int, length: Int) {
                bytes.write(data, offset, length)
                writes++
                if (writes == 1) onWrite?.invoke()
            }
        }
    }

    private class Completion {
        private val done = CountDownLatch(1)
        private val value = AtomicReference<Boolean?>()
        fun accept(result: Boolean) { value.set(result); done.countDown() }
        fun await(): Boolean {
            assertTrue("Delivery must complete", done.await(3, TimeUnit.SECONDS))
            return value.get()!!
        }
        fun pending() = done.count == 1L
    }

    private fun eventReady(phone: AirPlaySession, event: EventSocket = EventSocket()): EventSocket {
        set(phone, "eventSocket", event)
        set(phone, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
        return event
    }

    private fun blockQueue(controller: CarPlayController): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val field = controller.javaClass.getDeclaredField("touchExecutor").apply { isAccessible = true }
        (field.get(controller) as ExecutorService).execute { entered.countDown(); release.await(3, TimeUnit.SECONDS) }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        return release
    }

    @Test fun aMissingPhoneOrClusterReportsFailureImmediately() {
        Fixture().use { f ->
            val withoutPhone = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, withoutPhone::accept)
            assertFalse(withoutPhone.await())
            f.activate(f.phone(setup = false))
            val withoutCluster = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, withoutCluster::accept)
            assertFalse(withoutCluster.await())
        }
    }

    @Test fun iPhoneMaskIsAvailableForActivityReattachmentOnlyFromActiveSession() {
        Fixture().use { f ->
            val phone = f.phone(setup = false)
            val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e)
            f.cornerMask(phone, image)
            assertNull(f.controller.activeIphoneCornerMask())
            f.activate(phone)
            val retained = f.controller.activeIphoneCornerMask()!!
            assertSame(phone, retained.first)
            assertEquals(800, retained.third)
            assertArrayEquals(image, retained.second)
            retained.second[0] = 0
            assertArrayEquals(image, f.controller.activeIphoneCornerMask()!!.second)
            // A concurrent handshake must not overwrite the live phone's corner mask.
            val probe = f.phone(setup = false)
            f.cornerMask(probe, byteArrayOf(1, 2, 3))
            assertSame(phone, f.controller.activeIphoneCornerMask()!!.first)
            f.activate(probe)
            assertNull(f.controller.activeIphoneCornerMask())
        }
    }

    @Test fun theLiveSessionIsAvailableForActivityReattachment() {
        Fixture().use { f ->
            val phone = f.phone(setup = false)
            assertNull(f.controller.activeAirPlaySession())
            f.activate(phone)
            assertSame(phone, f.controller.activeAirPlaySession())
        }
    }

    @Test fun anEnqueuedSwitchReportsActualFailureAndKeepsTurnCardZoomIneligible() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            f.controller.setDashboardMapOutputVisible(true)
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertFalse(complete.await()) // Accepted by the executor, but there is no event channel.
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, phone.clusterUrl())
            assertNull(f.controller.dashboardMapRoute())
        }
    }

    @Test fun successIsReportedOnlyAfterBothCommandsWereWritten() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            f.controller.setDashboardMapOutputVisible(true)
            val event = eventReady(phone)
            val complete = Completion()
            val writesAtCompletion = AtomicReference(0)
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL) {
                writesAtCompletion.set(event.writes)
                complete.accept(it)
            }
            assertTrue(complete.await())
            assertEquals(2, writesAtCompletion.get())
            assertEquals(CarPlayClusterDisplay.MAP_URL, phone.clusterUrl())
            assertNotNull(f.controller.dashboardMapRoute())
        }
    }

    @Test fun aQueuedSwitchCannotReachAReplacedPhone() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val event = eventReady(phone)
            val release = blockQueue(f.controller)
            try {
                val complete = Completion()
                f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
                assertTrue(complete.pending())
                f.activate(f.phone())
                release.countDown()
                assertFalse(complete.await())
                assertEquals(0, event.writes)
            } finally { release.countDown() }
        }
    }

    @Test fun aQueuedSwitchCannotReachAReplacementClusterStream() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val event = eventReady(phone)
            val release = blockQueue(f.controller)
            try {
                val complete = Completion()
                f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
                setup(phone)
                release.countDown()
                assertFalse(complete.await())
                assertEquals(0, event.writes)
                assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, phone.clusterUrl())
            } finally { release.countDown() }
        }
    }

    @Test fun aPhoneReplacedDuringDeliveryCannotReportSuccessForTheNewPhone() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val replacement = f.phone()
            eventReady(phone, EventSocket { f.activate(replacement) })
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertFalse(complete.await())
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, replacement.clusterUrl())
        }
    }

    @Test fun anUnknownRouteIsRedrawnEvenWhenTheControllerAssumedTheNewStreamWasVisible() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            val event = eventReady(phone, EventSocket { setup(phone) })
            assertFalse(phone.setClusterUiShown(false))
            assertNull(phone.clusterUrl())
            visibility(f.controller, true)
            assertEquals(3, event.writes)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, phone.clusterUrl())
        }
    }

    @Test fun aPausedSelectionWaitsForResumeWithoutGrantingMapZoom() {
        Fixture().use { f ->
            val phone = f.phone()
            f.activate(phone)
            f.controller.setDashboardMapOutputVisible(true)
            val event = eventReady(phone)
            visibility(f.controller, false)
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertTrue(complete.await()) // The pending choice is safely retained while paused.
            assertEquals(1, event.writes)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, phone.clusterUrl())
            assertNull(f.controller.dashboardMapRoute())
            visibility(f.controller, true)
            assertEquals(CarPlayClusterDisplay.MAP_URL, phone.clusterUrl())
            assertNotNull(f.controller.dashboardMapRoute())
        }
    }

    @Test fun aReplacementPhoneInheritsTheLastDeliveredSelectionWithoutBlockingActivation() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertTrue(complete.await())
            val second = f.phone()
            val event = eventReady(second)
            val release = blockQueue(f.controller)
            try {
                f.record(second) // Activation must return while command execution is still blocked.
                assertEquals(0, event.writes)
                assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, second.clusterUrl())
                release.countDown()
                f.awaitCommands()
                assertEquals(2, event.writes)
                assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
            } finally { release.countDown() }
        }
    }

    @Test fun aPhoneActivatedBeforeClusterSetupReappliesTheSelectionOnlyAfterSetup() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val complete = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, complete::accept)
            assertTrue(complete.await())
            val second = f.phone(setup = false)
            val event = eventReady(second)
            f.record(second)
            f.awaitCommands()
            assertEquals(0, event.writes)
            assertNull(second.clusterUrl())
            setup(second)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, second.clusterUrl())
            second.javaClass.getDeclaredMethod("notifySetupResponseSent").apply { isAccessible = true }.invoke(second)
            assertEquals(2, event.writes)
            assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
        }
    }

    @Test fun aFailedSwitchCannotReplaceTheLastAcceptedSelectionForTheNextPhone() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val accepted = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, accepted::accept)
            assertTrue(accepted.await())
            set(first, "eventSocket", null)
            val failed = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.Content.INSTRUMENTS.url, failed::accept)
            assertFalse(failed.await())
            val second = f.phone()
            eventReady(second)
            f.record(second)
            f.awaitCommands()
            assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
        }
    }

    @Test fun aStaleQueuedSwitchCannotReplaceTheChoiceRestoredToANewPhone() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val accepted = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, accepted::accept)
            assertTrue(accepted.await())
            val second = f.phone()
            eventReady(second)
            val release = blockQueue(f.controller)
            try {
                val stale = Completion()
                f.controller.showDashboardContent(CarPlayClusterDisplay.Content.INSTRUMENTS.url, stale::accept)
                f.record(second)
                release.countDown()
                assertFalse(stale.await())
                f.awaitCommands()
                assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
            } finally { release.countDown() }
        }
    }

    @Test fun aReplacementDuringDeliveryCannotOverwriteTheLastAcceptedChoice() {
        Fixture().use { f ->
            val first = f.phone()
            eventReady(first)
            f.record(first)
            f.awaitCommands()
            val accepted = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, accepted::accept)
            assertTrue(accepted.await())
            val second = f.phone()
            eventReady(second)
            eventReady(first, EventSocket { f.record(second) })
            val stale = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.Content.INSTRUMENTS.url, stale::accept)
            assertFalse(stale.await())
            f.awaitCommands()
            assertEquals(CarPlayClusterDisplay.MAP_URL, second.clusterUrl())
        }
    }

    @Test fun aRepeatedRecordDoesNotResumeAPausedSelection() {
        Fixture().use { f ->
            val first = f.phone()
            val event = eventReady(first)
            f.record(first)
            f.awaitCommands()
            visibility(f.controller, false)
            val accepted = Completion()
            f.controller.showDashboardContent(CarPlayClusterDisplay.MAP_URL, accepted::accept)
            assertTrue(accepted.await())
            f.record(first)
            f.awaitCommands()
            assertEquals(1, event.writes)
            assertEquals(CarPlayClusterDisplay.Content.TURN_CARD.url, first.clusterUrl())
            visibility(f.controller, true)
            assertEquals(CarPlayClusterDisplay.MAP_URL, first.clusterUrl())
        }
    }

    companion object {
        private fun set(target: Any, name: String, value: Any?) {
            target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
        }
        private fun setup(phone: AirPlaySession) {
            phone.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
                .invoke(phone, listOf(mapOf("type" to 111L)))
        }
        private fun visibility(controller: CarPlayController, shown: Boolean) {
            controller.javaClass.getDeclaredMethod("applyClusterUi", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(controller, shown)
        }
    }
}
