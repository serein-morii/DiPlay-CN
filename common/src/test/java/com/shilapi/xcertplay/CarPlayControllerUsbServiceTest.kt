package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerUsbServiceTest {
    private val controllers = mutableListOf<CarPlayController>()
    private val statuses = mutableListOf<CarPlayStatus>()

    @After fun tearDown() {
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
        BydNavigationOutputs.endNow()
    }

    @Test fun wirelessLocalCanBeCreatedAndReachMfiWithoutAUsbService() {
        val context = UsbContext(false)
        val controller = controller(context, CarPlayTransport.WIRELESS, MfiTarget.LOCAL)
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.contains(CarPlayStatus.DiscoveringMfi))
        assertFalse(statuses.filterIsInstance<CarPlayStatus.Failed>().any { it.message.contains("USB service") })
        assertEquals(0, context.receivers.size)
    }

    @Test fun wiredStartReportsMissingUsbServiceWithoutRegisteringReceivers() {
        missingServiceFailsNormally(CarPlayTransport.WIRED, MfiTarget.LOCAL)
    }

    @Test fun wirelessCh341ReportsMissingUsbServiceWithoutRegisteringReceivers() {
        missingServiceFailsNormally(CarPlayTransport.WIRELESS, MfiTarget.USB_CH341)
    }

    @Test fun reconnectMfiAlsoReportsMissingUsbServiceNormally() {
        val context = UsbContext(false)
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL)
        controller.reconnectMfi()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.single() is CarPlayStatus.Failed)
        assertTrue((statuses.single() as CarPlayStatus.Failed).message.contains("USB service"))
        assertEquals(0, context.receivers.size)
    }

    @Test fun availableWiredUsbStillRegistersAndClosesBothIphoneReceivers() {
        val context = UsbContext(true)
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL)
        controller.start()
        assertEquals(2, context.receivers.size)
        assertTrue(context.receivers.values.any { it.hasAction("${context.packageName}.IPHONE_USB_PERMISSION") })
        assertTrue(context.receivers.values.any { it.hasAction("android.hardware.usb.action.USB_DEVICE_ATTACHED") })
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        assertEquals(0, context.receivers.size)
        assertEquals(2, context.unregistered)
    }

    @Test fun availableWirelessCh341StillWaitsForMfiAndClosesItsPermissionReceiver() {
        val context = UsbContext(true)
        val controller = controller(context, CarPlayTransport.WIRELESS, MfiTarget.USB_CH341)
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(statuses.contains(CarPlayStatus.WaitingForMfi))
        assertEquals(1, context.receivers.size)
        assertFalse(context.receivers.values.any { it.hasAction("android.hardware.usb.action.USB_DEVICE_ATTACHED") })
        controller.close()
        assertTrue(controller.awaitClosed(2_000))
        assertEquals(0, context.receivers.size)
        assertEquals(1, context.unregistered)
    }

    @Test fun aMissingCh341IsReportedWithWhatAndroidListsOnce() {
        // #792: after a session the adapter was gone, and the report said only "MFi coprocessor not present".
        val context = UsbContext(true)
        val usb = context.getSystemService(UsbManager::class.java)
        shadowOf(usb).addOrUpdateUsbDevice(usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/002"), false)
        val logs = mutableListOf<String>()
        val controller = controller(context, CarPlayTransport.WIRELESS, MfiTarget.USB_CH341, logs)
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf("mfi CH341 not listed by Android; USB devices=[05ac:12a8 /dev/bus/usb/001/002]"),
            logs.filter { it.startsWith("mfi CH341") })

        // The adapter comes back: the next poll names it and asks for access.
        shadowOf(usb).addOrUpdateUsbDevice(usbDevice(0x1a86, 0x7523, "/dev/bus/usb/001/005"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals("mfi CH341 listed 1a86:7523 /dev/bus/usb/001/005", logs.filter { it.startsWith("mfi CH341") }.last())
        assertTrue(statuses.contains(CarPlayStatus.RequestingMfiPermission))
    }

    @Test fun aMissingIphoneIsReportedWithWhatAndroidListsOnce() {
        // #763: the report said only "iPhone USB device not present", whether Android saw the port or not.
        val context = UsbContext(true)
        val usb = context.getSystemService(UsbManager::class.java)
        val logs = mutableListOf<String>()
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL, logs)
        // Local MFi needs the private identity of a real build, so the attempt starts at the iPhone step.
        ReflectionHelpers.setField(controller, "phase",
            Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController\$Phase").enumConstants
                .single { (it as Enum<*>).name == "IPHONE" })
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "checkIphoneAvailability")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(statuses.contains(CarPlayStatus.WaitingForIphone))
        assertEquals(listOf("wired iPhone not listed by Android; USB devices=[]"),
            logs.filter { it.startsWith("wired iPhone") })

        // A USB stick in the port is listed, once.
        shadowOf(usb).addOrUpdateUsbDevice(usbDevice(0x0781, 0x5567, "/dev/bus/usb/001/003"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(listOf("wired iPhone not listed by Android; USB devices=[]",
            "wired iPhone not listed by Android; USB devices=[0781:5567 /dev/bus/usb/001/003]"),
            logs.filter { it.startsWith("wired iPhone") })
        // The report keeps the lines as they are.
        logs.filter { it.startsWith("wired iPhone not listed") }.forEach { assertEquals(it, DiagnosticRedactor.redact(it)) }

        // Then the iPhone: found as before.
        shadowOf(usb).addOrUpdateUsbDevice(usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004"), false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals("wired iPhone discovered vid=0x5ac pid=0x12a8", logs.filter { it.startsWith("wired iPhone") }[2])
        assertTrue(statuses.contains(CarPlayStatus.RequestingIphonePermission))
    }

    // #335: after Save and reconnect an iPhone still in CarPlay mode ignored the mode request, and only a USB
    // attach ended the wait, so wired CarPlay sat on "waiting for the CarPlay USB configuration" for minutes.
    @Test fun anUnansweredModeRequestLooksAtTheIphoneAgainAfterTheWait() {
        val context = UsbContext(true)
        val usb = context.getSystemService(UsbManager::class.java)
        val iphone = usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004")
        shadowOf(usb).addOrUpdateUsbDevice(iphone, true)
        val logs = mutableListOf<String>()
        val controller = waitingForReenumeration(context, logs)

        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CarPlayController.REENUMERATION_WAIT_MILLIS - 100))
        assertTrue(logs.none { it.contains("USB transition unanswered") || it.contains("permission already granted") })

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue(logs.any { it.contains("USB transition unanswered after 10000ms count=1; checking the iPhone again") })
        assertTrue(logs.any { it == "wired iPhone USB permission already granted" })
    }

    @Test fun anAttachOrAnIphoneThatLeftTheBusEndsTheWaitWithoutALook() {
        val context = UsbContext(true)
        val usb = context.getSystemService(UsbManager::class.java)
        val iphone = usbDevice(0x05ac, 0x12a8, "/dev/bus/usb/001/004")
        shadowOf(usb).addOrUpdateUsbDevice(iphone, true)
        val logs = mutableListOf<String>()
        val controller = waitingForReenumeration(context, logs)

        // The iPhone came back on its own: its attach answered the request.
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        ReflectionHelpers.getField<java.util.concurrent.atomic.AtomicInteger>(controller, "availabilityPollGeneration")
            .incrementAndGet()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(logs.none { it.contains("USB transition unanswered") })

        // The iPhone is off the bus while it re-enumerates: its attach will end the wait.
        shadowOf(usb).removeUsbDevice(iphone)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "watchReenumeration",
            ReflectionHelpers.ClassParameter.from(UsbDevice::class.java, iphone))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertTrue(logs.none { it.contains("USB transition unanswered") })
    }

    private fun waitingForReenumeration(context: Context, logs: MutableList<String>): CarPlayController {
        val controller = controller(context, CarPlayTransport.WIRED, MfiTarget.LOCAL, logs)
        ReflectionHelpers.setField(controller, "phase",
            Class.forName("com.shilapi.xcertplay.orchestration.CarPlayController\$Phase").enumConstants
                .single { (it as Enum<*>).name == "REENUMERATION" })
        ReflectionHelpers.setField(controller, "reenumerationAttempts", 1)
        return controller
    }

    private fun usbDevice(vendor: Int, product: Int, node: String): UsbDevice =
        ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", node)
            ReflectionHelpers.setField(this, "mVendorId", vendor)
            ReflectionHelpers.setField(this, "mProductId", product)
        }

    private fun missingServiceFailsNormally(transport: CarPlayTransport, target: MfiTarget) {
        val context = UsbContext(false)
        val controller = controller(context, transport, target)
        controller.start()
        shadowOf(Looper.getMainLooper()).idle()
        val failure = statuses.single() as CarPlayStatus.Failed
        assertTrue(failure.message.contains("USB service"))
        assertEquals(0, context.receivers.size)
    }

    private fun controller(context: Context, transport: CarPlayTransport, target: MfiTarget,
        logs: MutableList<String>? = null): CarPlayController =
        CarPlayController(context, CarPlayRuntimeConfig(
            mfiTarget = target, transport = transport,
            ch341Devices = if (target == MfiTarget.USB_CH341) listOf(UsbDeviceId(0x1a86, 0x7523)) else emptyList(),
            identification = Iap2IdentificationConfig(name = "test", modelIdentifier = "test", manufacturer = "test",
                serialNumber = "test", firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3),
        ), AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(),
            object : AirPlaySessionListener {
                override fun onDebugLog(message: String) { logs?.add(message) }
            }, object : AirPlayMediaHandler {}, statuses::add).also(controllers::add)

    private class UsbContext(private val hasUsb: Boolean) : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val receivers = mutableMapOf<BroadcastReceiver, IntentFilter>()
        var unregistered = 0
        override fun getApplicationContext(): Context = this
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean = false
        override fun getSystemService(name: String): Any? =
            if (name == USB_SERVICE && !hasUsb) null else super.getSystemService(name)
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            if (receiver != null) receivers[receiver] = filter
            return super.registerReceiver(receiver, filter)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            if (receivers.remove(receiver) != null) unregistered++
            super.unregisterReceiver(receiver)
        }
    }
}
