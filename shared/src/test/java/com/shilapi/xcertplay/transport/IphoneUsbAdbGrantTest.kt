package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneUsbAdbGrantTest {
    @Test fun fallbackCoversTheSupportedPreOverlayAndroidVersions() {
        for (sdk in 25..28) assertTrue("sdk=$sdk", IphoneUsbAdbGrant.supported(sdk))
        assertFalse(IphoneUsbAdbGrant.supported(24))
        assertFalse(IphoneUsbAdbGrant.supported(29))
    }

    @Test fun commandQuotesTheApkAndUsesTheInstalledPackage() {
        assertEquals(
            "CLASSPATH='/data/app/it'\"'\"'s/base.apk' app_process /system/bin " +
                "com.shilapi.xcertplay.transport.IphoneUsbGrantTool " +
                "com.shihab.diplay 1010001 1452 4776",
            IphoneUsbAdbGrant.command(
                "/data/app/it's/base.apk",
                "com.shihab.diplay",
                1_010_001,
                IphoneUsbMatcher.APPLE_VENDOR_ID,
                0x12a8,
            ),
        )
    }

    @Test fun parsesBoundedHelperResults() {
        val granted = IphoneUsbAdbGrant.parse("DIPLAY_USB_GRANT_V1|GRANTED|1|1")
        assertEquals(IphoneUsbAdbGrant.Code.GRANTED, granted.code)
        assertTrue(granted.permissionGranted)

        for (invalid in listOf(
            null,
            "DIPLAY_USB_GRANT_V1|GRANTED|1|0",
            "DIPLAY_USB_GRANT_V1|GRANTED|1|2",
            "DIPLAY_USB_GRANT_V1|UNKNOWN|1|1",
            "DIPLAY_USB_GRANT_V1|GRANTED|-1|0",
        )) {
            assertEquals(IphoneUsbAdbGrant.Code.ERROR, IphoneUsbAdbGrant.parse(invalid).code)
        }
    }

    @Test fun packageMustBelongToTheExactApplicationUid() {
        assertTrue(IphoneUsbAdbGrant.packageMatchesUid(
            "com.shihab.diplay", 10_001, arrayOf("com.shihab.diplay"),
        ))
        assertTrue(IphoneUsbAdbGrant.packageMatchesUid(
            "com.shihab.diplay", 1_010_001, arrayOf("shared.uid.peer", "com.shihab.diplay"),
        ))
        assertFalse(IphoneUsbAdbGrant.packageMatchesUid(
            "com.shihab.diplay", 1_010_001, arrayOf("different.package"),
        ))
        assertFalse(IphoneUsbAdbGrant.packageMatchesUid("com.shihab.diplay", 1_010_001, null))
        assertFalse(IphoneUsbAdbGrant.packageMatchesUid(
            "com.shihab.diplay", 9_999, arrayOf("com.shihab.diplay"),
        ))
    }
}
