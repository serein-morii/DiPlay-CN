package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UsbAutoConfirmServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun isEnabledReturnsFalseByDefault() {
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            null,
        )
        assertFalse(UsbAutoConfirmService.isEnabled(context))
    }

    @Test
    fun isEnabledReturnsTrueWhenConfiguredInSecureSettings() {
        val serviceName = "${context.packageName}/${UsbAutoConfirmService::class.java.name}"
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.package/other.Service:$serviceName",
        )
        assertTrue(UsbAutoConfirmService.isEnabled(context))
    }
    @Test fun missingAccessibilitySettingsDoesNotCrash() {
        val unavailable = org.mockito.Mockito.mock(Context::class.java)
        org.mockito.Mockito.doThrow(android.content.ActivityNotFoundException())
            .`when`(unavailable).startActivity(org.mockito.ArgumentMatchers.any(android.content.Intent::class.java))
        assertFalse(UsbAutoConfirmService.openSettings(unavailable))
    }

    @Test fun onlySystemUsbActivitiesAreAccepted() {
        assertTrue(UsbAutoConfirmService.isSystemUsbWindow("com.android.systemui", "com.android.systemui.usb.UsbPermissionActivity"))
        assertFalse(UsbAutoConfirmService.isSystemUsbWindow("evil.app", "com.android.systemui.usb.UsbPermissionActivity"))
        assertFalse(UsbAutoConfirmService.isSystemUsbWindow("com.android.systemui", "android.app.AlertDialog"))
        assertFalse(UsbAutoConfirmService.isSystemUsbWindow("com.android.systemui", "com.android.systemui.media.MediaProjectionPermissionActivity"))
    }

    @Test fun theUsbDialogIsSeenUnderTheNameAlertActivityReports() {
        // AlertActivity reports UsbPermissionActivity as android.app.Dialog (#409).
        assertTrue(UsbAutoConfirmService.isSystemUsbWindow("com.android.systemui", "android.app.Dialog"))
        assertFalse(UsbAutoConfirmService.isSystemUsbWindow("evil.app", "android.app.Dialog"))
    }

    @Test fun otherSystemDialogsNamingDiPlayAreLeftAlone() {
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Start recording or casting with DiPlay?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt(
            "DiPlay will have access to all of the information that is visible on your screen", "DiPlay",
        ))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("要开始使用DiPlay录制或投放内容吗？", "DiPlay"))
    }

    @Test fun promptMustNameDiPlayInEveryAndroidWording() {
        // Android 7.1 and 10+ wording, in English and Chinese (#409, #521).
        assertTrue(UsbAutoConfirmService.isTargetPrompt("Allow the app DiPlay to access the USB device?", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("允许应用“DiPlay”访问该USB设备吗？", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("Allow DiPlay to access iPhone?", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("要允许DiPlay访问iPhone吗？", "DiPlay"))
        assertTrue(UsbAutoConfirmService.isTargetPrompt("Always open DiPlay when iPhone is connected", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow CarPlay access to iPhone?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow FakeDiPlay to access iPhone?", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("要允许DiPlayer访问iPhone吗？", "DiPlay"))
        assertFalse(UsbAutoConfirmService.isTargetPrompt("Allow USB access?", ""))
    }

    @Test fun bydAccessibilityScreenIsUsedWhenTheStandardActionIsMissing() {
        val context = org.mockito.Mockito.mock(Context::class.java)
        val opened = mutableListOf<android.content.Intent>()
        org.mockito.Mockito.doAnswer { call ->
            val intent = call.getArgument<android.content.Intent>(0)
            if (intent.action == Settings.ACTION_ACCESSIBILITY_SETTINGS) throw android.content.ActivityNotFoundException()
            opened += intent
            null
        }.`when`(context).startActivity(org.mockito.ArgumentMatchers.any(android.content.Intent::class.java))
        assertTrue(UsbAutoConfirmService.openSettings(context))
        assertEquals(
            listOf("com.byd.systemsettings/com.byd.systemsettings.accessibility.AccessibilityMainActivity"),
            opened.map { it.component!!.flattenToString() },
        )
    }

    @Test fun systemUsbPromptClicksOnlyWhenTheNamedAppMatches() {
        val service = org.mockito.Mockito.spy(org.robolectric.Robolectric.buildService(UsbAutoConfirmService::class.java).create().get())
        val root = org.mockito.Mockito.mock(android.view.accessibility.AccessibilityNodeInfo::class.java)
        val button = org.mockito.Mockito.mock(android.view.accessibility.AccessibilityNodeInfo::class.java)
        org.mockito.Mockito.doReturn(root).`when`(service).rootInActiveWindow
        org.mockito.Mockito.`when`(root.windowId).thenReturn(42)
        org.mockito.Mockito.`when`(root.packageName).thenReturn("com.android.systemui")
        org.mockito.Mockito.`when`(root.childCount).thenReturn(1)
        org.mockito.Mockito.`when`(root.getChild(0)).thenReturn(button)
        org.mockito.Mockito.`when`(button.isEnabled).thenReturn(true)
        org.mockito.Mockito.`when`(button.isClickable).thenReturn(true)
        org.mockito.Mockito.`when`(button.viewIdResourceName).thenReturn("android:id/button1")
        org.mockito.Mockito.`when`(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        val event = org.mockito.Mockito.mock(android.view.accessibility.AccessibilityEvent::class.java)
        org.mockito.Mockito.`when`(event.eventType).thenReturn(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        org.mockito.Mockito.`when`(event.packageName).thenReturn("com.android.systemui")
        org.mockito.Mockito.`when`(event.className).thenReturn("com.android.systemui.usb.UsbPermissionActivity")
        org.mockito.Mockito.`when`(event.windowId).thenReturn(42)
        try {
            org.mockito.Mockito.`when`(root.text).thenReturn("Allow OtherApp to access this USB device?")
            service.onAccessibilityEvent(event)
            org.mockito.Mockito.verify(button, org.mockito.Mockito.never()).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            val label = service.applicationInfo.loadLabel(service.packageManager)
            org.mockito.Mockito.`when`(root.text).thenReturn("Allow $label to access this USB device?")
            service.onAccessibilityEvent(event)
            org.mockito.Mockito.verify(button).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            event.recycle()
        }
    }
}
