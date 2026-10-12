package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [25, 26, 27, 28],
    manifest = Config.NONE,
    shadows = [PermissionRequestUsbManagerShadow::class],
)
class UsbPermissionActivityProbeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val component = ComponentName.unflattenFromString(UsbPermissionActivityProbe.COMPONENT)!!

    @Before fun reset() {
        shadowOf(context.packageManager).removeActivity(component)
        PermissionRequestUsbManagerShadow.requests = 0
    }

    private fun installPermissionActivity() {
        val info = shadowOf(context.packageManager).addActivityIfNotPresent(component)
        info.enabled = true
        info.exported = false // system_server can open it even when apps cannot.
        info.applicationInfo.enabled = true
        shadowOf(context.packageManager).addOrUpdateActivity(info)
    }

    @Test fun checksTheActualActivityAndItsEnabledState() {
        assertEquals(UsbPermissionActivityProbe.State.UNAVAILABLE, UsbPermissionActivityProbe.probe(context.packageManager))
        installPermissionActivity()
        assertEquals(UsbPermissionActivityProbe.State.AVAILABLE, UsbPermissionActivityProbe.probe(context.packageManager))
        context.packageManager.setComponentEnabledSetting(
            component,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0,
        )
        assertEquals(UsbPermissionActivityProbe.State.UNAVAILABLE, UsbPermissionActivityProbe.probe(context.packageManager))
    }

    @Test fun aDisabledSystemUiApplicationMakesTheActivityUnavailable() {
        installPermissionActivity()
        context.packageManager.setApplicationEnabledSetting(
            component.packageName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0,
        )
        assertEquals(UsbPermissionActivityProbe.State.UNAVAILABLE, UsbPermissionActivityProbe.probe(context.packageManager))
    }

    @Test fun componentStateIsProbedLive() {
        assertEquals(UsbPermissionActivityProbe.State.UNAVAILABLE, UsbPermissionActivityProbe.inspect(context).state)
        installPermissionActivity()
        assertEquals(UsbPermissionActivityProbe.State.AVAILABLE, UsbPermissionActivityProbe.inspect(context).state)
    }

    @Test fun missingUiUsesAdbWithoutLaunchingTheSystemRequest() {
        val usb = context.getSystemService(UsbManager::class.java)
        val diagnostics = mutableListOf<String>()
        for (name in listOf("initial", "reenumerated")) {
            val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor(), onDiagnostic = diagnostics::add)
            assertTrue(host.requestPermission(device(name)) is IphoneUsbHost.PermissionRequest.AdbRequired)
        }
        assertEquals(0, PermissionRequestUsbManagerShadow.requests)
        assertTrue(diagnostics.all { it.contains("state=UNAVAILABLE") })
        assertTrue(diagnostics.none { it.contains("cached=") })
    }

    @Test fun failedAdbWithMissingUiFallsBackToTheSystemRequest() {
        val usb = context.getSystemService(UsbManager::class.java)
        val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor())

        val request = host.requestPermission(device("initial"), allowAdbFallback = false)

        assertTrue(request is IphoneUsbHost.PermissionRequest.Requested)
        assertTrue((request as IphoneUsbHost.PermissionRequest.Requested).afterMissingUiAdbFailure)
        assertEquals(1, PermissionRequestUsbManagerShadow.requests)
    }

    @Test fun availableUiPreservesTheSystemPermissionFlow() {
        installPermissionActivity()
        val usb = context.getSystemService(UsbManager::class.java)
        val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor())

        val request = host.requestPermission(device("initial"))
        assertTrue(request is IphoneUsbHost.PermissionRequest.Requested)
        assertFalse((request as IphoneUsbHost.PermissionRequest.Requested).afterMissingUiAdbFailure)
        assertEquals(1, PermissionRequestUsbManagerShadow.requests)
    }

    @Test fun existingPermissionDoesNotProbeAdbOrTheUi() {
        val usb = context.getSystemService(UsbManager::class.java)
        val device = device("granted")
        shadowOf(usb).addOrUpdateUsbDevice(device, true)
        val diagnostics = mutableListOf<String>()
        val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor(), onDiagnostic = diagnostics::add)

        assertTrue(host.requestPermission(device) is IphoneUsbHost.PermissionRequest.AlreadyGranted)
        assertEquals(0, PermissionRequestUsbManagerShadow.requests)
        assertTrue(diagnostics.isEmpty())
    }

    private fun device(name: String): UsbDevice = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", name)
        ReflectionHelpers.setField(this, "mVendorId", IphoneUsbMatcher.APPLE_VENDOR_ID)
        ReflectionHelpers.setField(this, "mProductId", 0x12a8)
    }
}

@Implements(UsbManager::class)
class PermissionRequestUsbManagerShadow : ShadowUsbManager() {
    companion object { var requests = 0 }

    @Implementation
    protected fun requestPermission(device: UsbDevice, result: PendingIntent) { requests++ }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [PermissionRequestUsbManagerShadow::class])
class UsbPermissionActivityProbeModernTest {
    @Before fun reset() { PermissionRequestUsbManagerShadow.requests = 0 }

    @Test fun androidTenDoesNotInferUiAbsenceFromTheLegacyComponent() {
        val context = RuntimeEnvironment.getApplication()
        val usb = context.getSystemService(UsbManager::class.java)
        val host = IphoneUsbHost(context, usb, IphoneUsbMatcher.appleVendor())
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java).apply {
            ReflectionHelpers.setField(this, "mName", "modern")
            ReflectionHelpers.setField(this, "mVendorId", IphoneUsbMatcher.APPLE_VENDOR_ID)
            ReflectionHelpers.setField(this, "mProductId", 0x12a8)
        }

        assertTrue(host.requestPermission(device) is IphoneUsbHost.PermissionRequest.Requested)
        assertEquals(1, PermissionRequestUsbManagerShadow.requests)
    }
}
