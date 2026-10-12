package com.shilapi.xcertplay.hud

import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BluetoothSuspendLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val leaseField = BydBluetoothSuspend::class.java.getDeclaredField("lease").apply { isAccessible = true }
    private val original = leaseField.get(null)
    private val worker = BydBluetoothSuspend::class.java.getDeclaredField("worker").apply { isAccessible = true }
        .get(null) as ScheduledExecutorService
    private val controllers = mutableListOf<CarPlayController>()

    @After fun tearDown() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
        // Finish queued recovery before restoring the production dependency for other tests.
        worker.submit {}.get(2, TimeUnit.SECONDS)
        leaseField.set(null, original)
    }

    @Test fun realControllerCloseCancelsDelayedDisableWithoutSessionEndedCallback() {
        val journal = Journal(); var enabled: Boolean? = true
        val commands = mutableListOf<Boolean>()
        val lease = BluetoothSuspendLease(journal, { enabled }, { commands += it; enabled = it }, { enabled == it })
        leaseField.set(null, lease)
        val controller = controller()
        BydBluetoothSuspend.suspend(app, controller, 60_000)
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertTrue(commands.isEmpty())
        assertFalse(journal.recorded)
        assertNotNull("the controller must have retired its active lease", lease.recoveryOnAppOpen())
    }

    @Test fun realControllerCloseRestoresPersistedJournalWithoutAnInMemoryOwner() {
        val journal = Journal().apply { recorded = true }; var enabled: Boolean? = false
        val restored = CountDownLatch(1)
        val lease = BluetoothSuspendLease(journal, { enabled }, {
            enabled = it; if (it) restored.countDown()
        }, { enabled == it })
        leaseField.set(null, lease)
        controller().close()
        assertTrue(restored.await(2, TimeUnit.SECONDS))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(journal.recorded)
        assertEquals(true, enabled)
    }

    @Test fun realControllerCloseDoesNotWaitForARunningDisableAndRestoresAfterIt() {
        val journal = Journal(); var enabled: Boolean? = true
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val restored = CountDownLatch(1)
        leaseField.set(null, BluetoothSuspendLease(journal, { enabled }, {
            if (!it) { entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS)) }
            enabled = it
            if (it) restored.countDown()
        }, { enabled == it }))
        val controller = controller()
        BydBluetoothSuspend.suspend(app, controller, 0)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            controller.close()
            assertTrue("controller close waited for the Bluetooth command", controller.awaitClosed(1_000))
        } finally { proceed.countDown() }
        assertTrue(restored.await(2, TimeUnit.SECONDS))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(journal.recorded)
        assertEquals(true, enabled)
    }

    @Test fun appOpenRunsCrashRecoveryWithoutAConnectionOrApprovalPrompt() {
        val journal = Journal().apply { recorded = true }; var enabled: Boolean? = false
        val restored = CountDownLatch(1)
        leaseField.set(null, BluetoothSuspendLease(journal, { enabled }, {
            enabled = it; if (it) restored.countDown()
        }, { enabled == it }))
        BydBluetoothSuspend.onAppOpened(app)
        assertTrue(restored.await(2, TimeUnit.SECONDS))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(journal.recorded)
    }

    @Test fun handshakeRecoveryTimeoutDoesNotClaimReadinessWhileDisableIsRunning() {
        val journal = Journal(); var enabled: Boolean? = true
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        leaseField.set(null, BluetoothSuspendLease(journal, { enabled }, {
            if (!it) { entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS)) }
            enabled = it
        }, { enabled == it }))
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        BydBluetoothSuspend.suspend(app, Any(), 0)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(BydBluetoothSuspend.resumeAndWait(app, adapter, 25))
            assertTrue("the still-ON snapshot alone cannot prove recovery", adapter.isEnabled)
        } finally { proceed.countDown() }
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertFalse(journal.recorded)
        assertEquals(true, enabled)
    }

    @Test fun radioEnabledFallsBackToDefaultAdapterWhenBluetoothManagerIsNull() {
        val wrappedContext = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (Context.BLUETOOTH_SERVICE == name) return null
                return super.getSystemService(name)
            }
        }
        val defaultAdapter = BluetoothAdapter.getDefaultAdapter()
        assertNotNull(defaultAdapter)
        shadowOf(defaultAdapter).setState(BluetoothAdapter.STATE_ON)
        assertFalse(BydBluetoothSuspend.isSuspendedByUs(wrappedContext))
    }

    @Test fun oldControllerCloseCannotCancelNewControllerDelay() {
        val journal = Journal(); var enabled: Boolean? = true
        val lease = BluetoothSuspendLease(journal, { enabled }, { enabled = it }, { enabled == it })
        leaseField.set(null, lease)
        val old = controller(); val current = controller()
        BydBluetoothSuspend.suspend(app, old, 60_000)
        BydBluetoothSuspend.suspend(app, current, 60_000)
        old.close()
        assertTrue(old.awaitClosed(2_000))
        assertNull("current owner must survive the old controller close", lease.recoveryOnAppOpen())
        current.close()
        assertNotNull(lease.recoveryOnAppOpen())
    }

    @Test fun oldControllerCloseCannotSupersedeQueuedWirelessHandshakeRecovery() {
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_OFF)
        val journal = Journal().apply { recorded = true }
        val lease = BluetoothSuspendLease(journal, { adapter.isEnabled }, {
            shadowOf(adapter).setState(if (it) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF)
        }, { adapter.isEnabled == it })
        leaseField.set(null, lease)
        val old = controller()
        lease.begin(old)
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val blocker = worker.submit { entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val result = AtomicReference<Boolean>()
        val handshake = thread { result.set(BydBluetoothSuspend.resumeAndWait(app, adapter, 3_000)) }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (handshake.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("handshake must have queued recovery before the old close", Thread.State.TIMED_WAITING, handshake.state)
            old.close()
            assertTrue(old.awaitClosed(1_000))
        } finally { proceed.countDown(); handshake.join(4_000); blocker.get(2, TimeUnit.SECONDS) }
        assertFalse(handshake.isAlive)
        assertEquals("late old close must not abort a valid new handshake", true, result.get())
        assertTrue(adapter.isEnabled)
        assertFalse(journal.recorded)
    }

    @Test fun aSessionActivatedWhileTheControllerClosesNeverKeepsThePause() {
        val journal = Journal(); var enabled: Boolean? = true
        val commands = mutableListOf<Boolean>()
        val lease = BluetoothSuspendLease(journal, { enabled }, { commands += it; enabled = it }, { enabled == it })
        leaseField.set(null, lease)
        // Hold the session thread at the pause switch while close() runs.
        val checked = CountDownLatch(1); val proceed = CountDownLatch(1)
        val controller = controller { real ->
            object : SharedPreferences by real {
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    if (key != "bt_suspend_during_carplay") return real.getBoolean(key, defValue)
                    checked.countDown()
                    assertTrue(proceed.await(5, TimeUnit.SECONDS))
                    return true
                }
            }
        }
        val session = session()
        // The same session again is not a replacement, so HUD and cluster start-up stay out of this test.
        CarPlayController::class.java.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, session)
        val listener = CarPlayController::class.java.getDeclaredField("sessionListener").apply { isAccessible = true }
            .get(controller) as AirPlaySessionListener
        val active = thread { listener.onSessionActive(session) }
        assertTrue(checked.await(2, TimeUnit.SECONDS))
        val closing = thread { controller.close() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (closing.isAlive && closing.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(5)
        } finally { proceed.countDown() }
        active.join(2_000); closing.join(2_000)
        assertTrue(controller.awaitClosed(2_000))
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertNotNull("a closed controller must not keep the Bluetooth pause", lease.recoveryOnAppOpen())
        assertTrue(commands.isEmpty())
    }

    @Test fun aSessionThatEndsBeforeThePauseGuardNeverKeepsThePause() {
        val journal = Journal(); var enabled: Boolean? = true
        val commands = mutableListOf<Boolean>()
        val lease = BluetoothSuspendLease(journal, { enabled }, { commands += it; enabled = it }, { enabled == it })
        leaseField.set(null, lease)
        // Hold the session thread after it marked the session active, before the pause guard.
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val fetched = CountDownLatch(1); val proceed = CountDownLatch(1)
        val controller = controller { real ->
            if (Thread.currentThread().name == SESSION_THREAD && armed.compareAndSet(true, false)) {
                fetched.countDown()
                assertTrue(proceed.await(5, TimeUnit.SECONDS))
            }
            object : SharedPreferences by real {
                override fun getBoolean(key: String?, defValue: Boolean): Boolean =
                    if (key == "bt_suspend_during_carplay") true else real.getBoolean(key, defValue)
            }
        }
        val session = session()
        CarPlayController::class.java.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, session)
        val listener = CarPlayController::class.java.getDeclaredField("sessionListener").apply { isAccessible = true }
            .get(controller) as AirPlaySessionListener
        armed.set(true)
        val active = thread(name = SESSION_THREAD) { listener.onSessionActive(session) }
        try {
            assertTrue(fetched.await(2, TimeUnit.SECONDS))
            listener.onSessionEnded(session)
        } finally { proceed.countDown() }
        active.join(2_000)
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertNotNull("an ended session must not leave the Bluetooth pause", lease.recoveryOnAppOpen())
        assertTrue(commands.isEmpty())
    }

    @Test fun aSessionClosedBeforeItBecameActiveNeverTakesThePause() {
        val journal = Journal(); var enabled: Boolean? = true
        val lease = BluetoothSuspendLease(journal, { enabled }, { enabled = it }, { enabled == it })
        leaseField.set(null, lease)
        val controller = controller { real ->
            object : SharedPreferences by real {
                override fun getBoolean(key: String?, defValue: Boolean): Boolean =
                    if (key == "bt_suspend_during_carplay") true else real.getBoolean(key, defValue)
            }
        }
        val session = session()
        CarPlayController::class.java.getDeclaredField("activeSession").apply { isAccessible = true }.set(controller, session)
        val listener = CarPlayController::class.java.getDeclaredField("sessionListener").apply { isAccessible = true }
            .get(controller) as AirPlaySessionListener
        session.close()
        listener.onSessionActive(session)
        worker.submit {}.get(2, TimeUnit.SECONDS)
        assertNotNull("a closed session must not take the Bluetooth pause", lease.recoveryOnAppOpen())
    }

    private fun session() = AirPlaySession(Socket(),
        AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})

    private companion object {
        const val SESSION_THREAD = "test-airplay-session"
    }

    private class Journal : BluetoothRestoreJournal {
        @Volatile var recorded = false
        override fun pending() = recorded
        override fun write(pending: Boolean): Boolean { recorded = pending; return true }
    }

    private fun controller(
        airPlayPreferences: (SharedPreferences) -> SharedPreferences = { it },
    ): CarPlayController {
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun bindService(service: Intent, conn: ServiceConnection, flags: Int) = false
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                val real = super.getSharedPreferences(name, mode)
                return if (name == "xcertplay_airplay") airPlayPreferences(real) else real
            }
        }
        return CarPlayController(context, CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL, transport = CarPlayTransport.WIRED,
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {}).also(controllers::add)
    }
}
