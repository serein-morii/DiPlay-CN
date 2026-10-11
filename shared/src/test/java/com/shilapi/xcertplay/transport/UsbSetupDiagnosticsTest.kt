package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.network.CarPlayVpnService
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSetupDiagnosticsTest {
    @Test
    fun ncmSetupRetriesALostClaimAndPausesBetweenAttempts() {
        val pauses = mutableListOf<Long>()
        var calls = 0
        assertEquals(3, NcmUsbBridge.retryUsbSetup(pause = { pauses += it }) { ++calls == 3 })
        assertEquals(listOf(100L, 100L), pauses)

        pauses.clear()
        assertNull(NcmUsbBridge.retryUsbSetup(pause = { pauses += it }) { false })
        assertEquals(NcmUsbBridge.USB_SETUP_ATTEMPTS - 1, pauses.size)

        pauses.clear()
        assertEquals(1, NcmUsbBridge.retryUsbSetup(pause = { pauses += it }) { true })
        assertTrue(pauses.isEmpty())
    }

    @Test
    fun rejectedModeRequestRecordsTheIphonesModeAndConfigurations() {
        val mode = byteArrayOf(3, 3, 3, 0)
        assertEquals(
            "CarPlay USB mode request rejected; current mode=3:3:3:0 usbVersion=15.04 configurations=[1[0/0:6.1.1x3]]",
            IphoneUsbHost.usbModeDiagnostic(4, mode, "15.04", listOf("1[0/0:6.1.1x3]")),
        )
        assertTrue(IphoneUsbHost.usbModeDiagnostic(-1, ByteArray(4), null, emptyList()).contains("current mode=unreadable(-1) usbVersion=unknown"))
        // iOS 12 and 15 reply with three bytes.
        assertTrue(IphoneUsbHost.usbModeDiagnostic(3, byteArrayOf(1, 2, 3, 0), "7.01", emptyList())
            .contains("current mode=1:2:3 (short:3) usbVersion=7.01"))
    }

    @Test
    fun configurationSwitchDetachesDriversOnlyAfterAndroidRefusesIt() {
        val detached = mutableListOf<Int>()
        assertNull(UsbConfigurationSelection.select(6, listOf(0, 1), { true }, { 6 }) { detached += it; true })
        assertTrue(detached.isEmpty())

        // The 8227L kernel's snd-usb-audio held interface 0 of configuration 2 (#518).
        var active = 2
        var attempts = 0
        val outcome = UsbConfigurationSelection.select(6, listOf(2, 0, 1, 0, 3), {
            (++attempts == 2).also { if (it) active = 6 }
        }, { active }) { detached += it; it != 3 }
        assertEquals(listOf(0, 1, 2, 3), detached)
        assertEquals(
            "USB configuration select target=6 ok=true before=2 detached=0,1,2 after=6",
            outcome?.diagnostic(),
        )
        assertFalse(outcome!!.wrongConfiguration)
    }

    @Test
    fun configurationSwitchReportsADriverItCouldNotMove() {
        val stuck = UsbConfigurationSelection.select(6, listOf(0), { false }, { 2 }) { false }
        assertTrue(stuck!!.wrongConfiguration)
        assertEquals("USB configuration select target=6 ok=false before=2 detached=none after=2", stuck.diagnostic())

        // Already in configuration 6: the forced claims later detach cdc_ncm; nothing to switch here.
        val detached = mutableListOf<Int>()
        val current = UsbConfigurationSelection.select(6, listOf(0, 3), { false }, { 6 }) { detached += it; true }
        assertFalse(current!!.wrongConfiguration)
        assertTrue(detached.isEmpty())

        // An unreadable configuration keeps the earlier claim-anyway behavior.
        assertFalse(UsbConfigurationSelection.select(6, listOf(0), { false }, { null }) { true }!!.wrongConfiguration)
    }

    @Test
    fun vpnAttachFailureNamesTheExceptionChain() {
        val failure = IllegalStateException("Cannot set address", IOException("Invalid argument"))
        val detail = CarPlayVpnService.attachFailure(failure)
        assertTrue(detail, detail.startsWith("IllegalStateException: Cannot set address <- IOException: Invalid argument at "))
    }
}
