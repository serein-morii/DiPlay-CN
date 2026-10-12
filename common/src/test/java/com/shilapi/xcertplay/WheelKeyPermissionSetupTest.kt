package com.shilapi.xcertplay

import android.content.ComponentName
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class WheelKeyPermissionSetupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val ours get() = ComponentName(context, WheelKeyService::class.java).flattenToString()
    private val other = "car.package/.Accessibility"
    private val commands = mutableListOf<String>()
    private var reading = other
    private var reply: String? = "\nDIPLAY_WHEEL_EXIT:0"
    private var allow = true

    @Before fun setup() {
        context.getSharedPreferences("diplay_navigation_wheel", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        WheelZoomSettings.setEnabled(context, false)
        WheelZoomSettings.setJoystick(context, false)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, other)
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }

    @Test fun nullEmptyOrFailedReadDoesNotWriteTheServiceList() {
        for (output in listOf(null, "", "$other\nDIPLAY_WHEEL_EXIT:1")) {
            commands.clear()
            assertFalse(WheelKeyService.applyServiceSettings(context, { commands += it; output }))
            assertEquals(1, commands.size)
            assertEquals(other, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        }
    }

    @Test fun malformedOrShellActiveValuesAreRejectedBeforeAnyWrite() {
        for (value in listOf("Permission denied", "$other:'; touch /tmp/unsafe; '", "$other::bad", "$other\nbad")) {
            reading = value
            commands.clear()
            assertFalse(WheelKeyService.applyServiceSettings(context, ::shell))
            assertEquals(1, commands.size)
        }
    }

    @Test fun stoppedServiceIsReboundWhileOtherServicesKeepTheirOrder() {
        reading = "$other:$ours:another.package/.Service"
        assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
        val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
        assertEquals(2, writes.size)
        assertTrue(writes[0].contains("'$other:another.package/.Service'"))
        // Android clears its crashed mark only if its observer sees the list without the service (#606).
        assertTrue(writes[0].contains("&& sleep 1"))
        assertFalse(writes[1].contains("sleep"))
        assertEquals("$other:another.package/.Service:$ours",
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun healthyServiceIsNotRemovedForRebinding() {
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            reading = "$other:$ours"
            assertTrue(WheelKeyService.applyServiceSettings(context, ::shell))
            val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
            assertEquals(1, writes.size)
            assertTrue(writes.single().contains("'$other:$ours'"))
        } finally { service.onDestroy() }
    }

    @Test fun serviceBindingAfterTheReadIsNotRemovedForRebinding() {
        val service = Robolectric.buildService(WheelKeyService::class.java).create().get()
        reading = "$other:$ours"
        var continuationChecks = 0
        try {
            assertTrue(WheelKeyService.applyServiceSettings(context, ::shell) {
                if (++continuationChecks == 2) {
                    service.javaClass.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
                }
                true
            })
            val writes = commands.filter { it.contains("settings put secure enabled_accessibility_services") }
            assertEquals(1, writes.size)
            assertTrue(writes.single().contains("'$other:$ours'"))
        } finally { service.onDestroy() }
    }

    @Test fun shellFailureStopsFurtherWritesAndDoesNotClaimSuccess() {
        reply = "DIPLAY_WHEEL_EXIT:1"
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell))
        assertEquals(2, commands.size)
        assertFalse(commands.any { it.contains("settings put secure accessibility_enabled") })
    }

    @Test fun successfulShellWithoutActualPermissionIsNotSuccess() {
        assertFalse(WheelKeyService.applyServiceSettings(context, { command ->
            if (command.contains("settings get")) "$other\nDIPLAY_WHEEL_EXIT:0" else "DIPLAY_WHEEL_EXIT:0"
        }))
    }

    @Test fun disablingTheFeaturesDuringTheRestoreGraceStopsTheRequest() {
        WheelZoomSettings.setEnabled(context, true)
        assertTrue(WheelKeyService.needsRestore(context))
        WheelZoomSettings.setEnabled(context, false)
        assertFalse(WheelKeyService.needsRestore(context))
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell) { WheelKeyService.needsRestore(context) })
        assertTrue(commands.isEmpty())
    }

    @Test fun disablingAfterTheReadStopsAllWrites() {
        assertFalse(WheelKeyService.applyServiceSettings(context, { command -> shell(command).also { allow = false } }) { allow })
        assertEquals(1, commands.size)
    }

    @Test fun navigationOnlyRestorationRequiresRecordedAuthorizationAndStopsWhenDisabled() {
        val prefs = context.getSharedPreferences("diplay_navigation_wheel", android.content.Context.MODE_PRIVATE)
        assertFalse(NavigationWheelSettings.enabled(context))
        prefs.edit().putBoolean("enabled", true).commit()
        assertFalse(WheelKeyService.needsRestore(context))
        NavigationWheelSettings.recordAuthorization(context)
        assertTrue(WheelKeyService.needsRestore(context))
        prefs.edit().putBoolean("enabled", false).commit()
        assertFalse(WheelKeyService.needsRestore(context))
        assertFalse(WheelKeyService.applyServiceSettings(context, ::shell) { WheelKeyService.needsRestore(context) })
        assertTrue(commands.isEmpty())
    }

    private fun shell(command: String): String? {
        commands += command
        if (command.contains("settings get")) return "$reading\nDIPLAY_WHEEL_EXIT:0"
        if (reply?.trim() != "DIPLAY_WHEEL_EXIT:0") return reply
        if (command.contains("settings put secure enabled_accessibility_services")) {
            val value = command.substringAfter("enabled_accessibility_services '").substringBefore("'")
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        } else if (command.contains("settings put secure accessibility_enabled 1")) {
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        return reply
    }
}
