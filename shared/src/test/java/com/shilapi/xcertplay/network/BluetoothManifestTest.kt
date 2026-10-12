package com.shilapi.xcertplay.network

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

class BluetoothManifestTest {
    @Test fun legacyBluetoothPermissionsStayRequestedOnAndroid12AndNewer() {
        // #671: MIUI 13 (Android 12) refused the iPhone's RFCOMM connection without android.permission.BLUETOOTH.
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val permissions = document.getElementsByTagName("uses-permission")
        val declared = (0 until permissions.length).map { permissions.item(it) as Element }
            .associateBy { it.getAttributeNS(ANDROID, "name") }
        for (name in listOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN")) {
            assertEquals(name, "", declared.getValue(name).getAttributeNS(ANDROID, "maxSdkVersion"))
        }
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
