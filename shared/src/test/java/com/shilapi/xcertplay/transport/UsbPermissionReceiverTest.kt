package com.shilapi.xcertplay.transport

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29], manifest = Config.NONE)
class UsbPermissionReceiverTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val usb get() = context.getSystemService(UsbManager::class.java)
    private val iphoneAction get() = "${context.packageName}.IPHONE_USB_PERMISSION"
    private val ch341Action get() = "${context.packageName}.CH341_USB_PERMISSION"
    private val senderPermission get() = "${context.packageName}.USB_PERMISSION_RESULT"

    private fun device(vendor: Int, product: Int): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/002")
        ReflectionHelpers.setField(this, "mVendorId", vendor)
        ReflectionHelpers.setField(this, "mProductId", product)
    }

    private fun result(action: String, device: UsbDevice, granted: Boolean) = Intent(action)
        .putExtra(UsbManager.EXTRA_DEVICE, device)
        .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted)

    private fun deliver(intent: Intent) {
        context.sendBroadcast(intent)
        ShadowLooper.idleMainLooper()
    }

    private fun registeredPermission(action: String): String? =
        shadowOf(context as android.app.Application).registeredReceivers
            .single { it.intentFilter.hasAction(action) }.broadcastPermission

    @Test fun iphonePermissionReceiverRequiresASenderPermission() {
        IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor()).registerPermissionReceiver { }
        assertNotNull("receiver accepts a result from any app", registeredPermission(iphoneAction))
    }

    @Test fun ch341PermissionReceiverRequiresASenderPermission() {
        Ch341UsbHost(context, usb, Ch341DeviceMatcher(listOf(UsbDeviceId(0x1A86, 0x5512)))).registerPermissionReceiver { }
        assertNotNull("receiver accepts a result from any app", registeredPermission(ch341Action))
    }

    @Test fun iphoneResultFromAnotherAppIsNotDelivered() {
        val results = CopyOnWriteArrayList<IphoneUsbHost.PermissionResult>()
        IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor()).registerPermissionReceiver { results.add(it) }
        deliver(result(iphoneAction, device(IphoneUsbMatcher.APPLE_VENDOR_ID, 0x12a8), granted = true))
        assertTrue("forged result was delivered to the app (${results.size} callbacks)", results.isEmpty())
    }

    @Test fun iphoneResultFromThisAppIsDelivered() {
        shadowOf(context as android.app.Application).grantPermissions(senderPermission)
        val results = CopyOnWriteArrayList<IphoneUsbHost.PermissionResult>()
        IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor()).registerPermissionReceiver { results.add(it) }
        val device = device(IphoneUsbMatcher.APPLE_VENDOR_ID, 0x12a8)
        deliver(result(iphoneAction, device, granted = true))
        assertEquals(listOf<IphoneUsbHost.PermissionResult>(IphoneUsbHost.PermissionResult.Granted(device)), results)
    }

    @Test fun ch341ResultFromThisAppIsDelivered() {
        shadowOf(context as android.app.Application).grantPermissions(senderPermission)
        val results = CopyOnWriteArrayList<Ch341UsbHost.PermissionResult>()
        Ch341UsbHost(context, usb, Ch341DeviceMatcher(listOf(UsbDeviceId(0x1A86, 0x5512)))).registerPermissionReceiver { results.add(it) }
        val device = device(0x1A86, 0x5512)
        deliver(result(ch341Action, device, granted = false))
        assertEquals(listOf<Ch341UsbHost.PermissionResult>(Ch341UsbHost.PermissionResult.Denied(device)), results)
    }
}
