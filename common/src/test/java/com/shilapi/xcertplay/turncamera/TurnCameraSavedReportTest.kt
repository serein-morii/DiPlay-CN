package com.shilapi.xcertplay.turncamera

import android.os.Build
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TurnCameraSavedReportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun report(cancelled: Boolean = false, key: String = "camera2_ids") = TurnCameraCapabilities.Report(
        123L, 5L, false, cancelled,
        listOf(TurnCameraCapabilities.ProbeItem(key, TurnCameraCapabilities.Status.UNCONFIRMED, "token=private-value")),
    )

    @Before fun clear() {
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        ReflectionHelpers.setStaticField(Build::class.java, "FINGERPRINT", "test/firmware/one")
        controllerVersion("test-controller-1")
    }

    private fun controllerVersion(value: String) {
        // Robolectric sandbox properties only; never writes a head-unit property.
        Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
            .invoke(null, "apps.setting.product.outswver", value)
    }

    @Test fun controllerOtaOrUnreadableControllerInvalidatesAnUnchangedAndroidBuild() {
        TurnCameraSettings.saveReport(context, report())
        assertTrue(TurnCameraSettings.savedReport(context)!!.firmwareMatches)
        controllerVersion("test-controller-2")
        assertFalse(TurnCameraSettings.savedReport(context)!!.firmwareMatches)
        controllerVersion("")
        TurnCameraSettings.saveReport(context, report())
        assertFalse(TurnCameraSettings.savedReport(context)!!.firmwareMatches)
    }

    @Test fun reportSurvivesReopenButNotFirmwareChangeAndNeverSavesCredentials() {
        TurnCameraSettings.saveReport(context, report())
        val saved = TurnCameraSettings.savedReport(context)!!
        assertTrue(saved.firmwareMatches)
        assertFalse(saved.text.contains("private-value"))
        assertTrue(saved.text.contains("<redacted>"))
        assertEquals(123L, saved.probe.atMillis)
        ReflectionHelpers.setStaticField(Build::class.java, "FINGERPRINT", "test/firmware/two")
        val stale = TurnCameraSettings.savedReport(context)!!
        assertFalse(stale.firmwareMatches)
        assertEquals(saved.text, stale.text)
    }

    @Test fun cancelledBusyAndLegacySavesCannotPretendToBeCurrentEvidence() {
        TurnCameraSettings.saveReport(context, report())
        val saved = TurnCameraSettings.savedReport(context)
        TurnCameraSettings.saveReport(context, report(cancelled = true))
        TurnCameraSettings.saveReport(context, report(key = TurnCameraCapabilities.BUSY_ITEM_KEY))
        assertEquals(saved, TurnCameraSettings.savedReport(context))
        ReflectionHelpers.setStaticField(Build::class.java, "FINGERPRINT", Build.UNKNOWN)
        assertFalse(TurnCameraSettings.savedReport(context)!!.firmwareMatches)
        TurnCameraSettings.saveLastProbe(context, TurnCameraSettings.LastProbe(456L, "legacy summary"))
        assertNull(TurnCameraSettings.savedReport(context))
    }

    @Test fun compatibilityEvidenceIsExcludedFromPortableSettingsBackup() {
        TurnCameraSettings.saveReport(context, report())
        val document = com.shilapi.xcertplay.backup.SettingsBackupCodec.export(context, "test").toString(Charsets.UTF_8)
        assertFalse(document.contains("turn_camera"))
        assertFalse(document.contains("test/firmware"))
    }

    @Test fun detailedCameraMetadataSurvivesSavingWithoutLosingSanitizationOrItsBound() {
        val longDetail = (1..100).joinToString("; ") { "size$it=640x480" } + "; password=private; physical-ids=left"
        TurnCameraSettings.saveReport(context, report().copy(items = listOf(
            TurnCameraCapabilities.ProbeItem("camera2_ids", TurnCameraCapabilities.Status.OK, longDetail))))
        val saved = TurnCameraSettings.savedReport(context)!!.text
        assertTrue(saved.contains("physical-ids=left"))
        assertFalse(saved.contains("password=private"))
        assertTrue(saved.contains("password=<redacted>"))
        assertEquals(8_001, TurnCameraCapabilities.redactDetail("camera2_ids", (1..2000).joinToString(" ") { "size=$it" }).length)
        assertEquals(401, TurnCameraCapabilities.redactDetail("other", longDetail).length)
    }
}
