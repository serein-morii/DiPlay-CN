package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import android.util.Base64
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotConfigReadTest {
    private val logs = mutableListOf<String>()
    private fun log(message: String) { logs += message }

    private fun reply(ssid: String, passphrase: String, security: Int): String {
        val b64 = { value: String -> Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP) }
        return "ok|${b64(ssid)}|${b64(passphrase)}|$security"
    }

    @Test fun adbReadCarriesTheSavedCredentials() {
        val outcome = CarHotspotConfigRead.readAdb(
            { _, _ -> "noise\nDIPLAY_HOTSPOT_APCONFIG_READ_V1|${reply("Car-Hotspot", "pass|word", 1)}\n" },
            "/data/app/diplay.apk", 4_000, ::log)
        assertEquals(CarHotspotSavedCredentials("Car-Hotspot", "pass|word", ManualHotspotSecurity.WPA2), outcome.credentials)
        assertNull(outcome.reason)
    }

    @Test fun adbReadMapsEverySecurityTypeTheCarCanSave() {
        listOf(
            0 to ManualHotspotSecurity.OPEN,
            1 to ManualHotspotSecurity.WPA2,
            2 to ManualHotspotSecurity.WPA3_TRANSITION,
            3 to ManualHotspotSecurity.WPA3,
        ).forEach { (type, security) ->
            val outcome = CarHotspotConfigRead.readAdb(
                { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_READ_V1|${reply("Car", if (security == ManualHotspotSecurity.OPEN) "" else "pass", type)}" },
                "", 4_000, ::log)
            assertEquals(security, outcome.credentials?.security)
        }
    }

    @Test fun securedHotspotsWithoutAPassphraseAreRejected() {
        val outcome = CarHotspotConfigRead.readAdb(
            { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_READ_V1|${reply("Car", "", 1)}" }, "", 4_000, ::log)
        assertNull(outcome.credentials)
        assertEquals(CarHotspotConfigRead.Reason.UNAVAILABLE, outcome.reason)
        assertTrue(logs.last().contains("without a passphrase"))
    }

    @Test fun openHotspotsWithAPassphraseAreRejected() {
        val outcome = CarHotspotConfigRead.readAdb(
            { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_READ_V1|${reply("Car", "pass", 0)}" }, "", 4_000, ::log)
        assertNull(outcome.credentials)
        assertTrue(logs.last().contains("an open hotspot with a passphrase"))
    }

    @Test fun unnamedOrUnknownSecurityRepliesAreRejected() {
        val unnamed = CarHotspotConfigRead.readAdb(
            { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_READ_V1|${reply("", "pass", 1)}" }, "", 4_000, ::log)
        assertNull(unnamed.credentials)
        val unknownSecurity = CarHotspotConfigRead.readAdb(
            { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_READ_V1|${reply("Car", "pass", 9)}" }, "", 4_000, ::log)
        assertNull(unknownSecurity.credentials)
        assertTrue(logs.last().contains("unsupported security mode"))
    }

    @Test fun shellFailuresAndSilenceAreUnavailable() {
        val failed = CarHotspotConfigRead.readAdb(
            { _, _ -> "DIPLAY_HOTSPOT_APCONFIG_READ_V1|error rejected" }, "", 4_000, ::log)
        assertEquals(CarHotspotConfigRead.Outcome(reason = CarHotspotConfigRead.Reason.UNAVAILABLE), failed)
        val silent = CarHotspotConfigRead.readAdb({ _, _ -> null }, "", 4_000, ::log)
        assertEquals(CarHotspotConfigRead.Outcome(reason = CarHotspotConfigRead.Reason.UNAVAILABLE), silent)
        assertTrue(logs.last().contains("no reply"))
    }

    @Test fun appReadReturnsTheStoredConfiguration() {
        val config = WifiConfiguration().apply {
            SSID = "Car-Hotspot"
            preSharedKey = "password1"
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
        }
        val outcome = CarHotspotConfigRead.readApp({ config }, ::log)
        assertEquals(CarHotspotSavedCredentials("Car-Hotspot", "password1", ManualHotspotSecurity.WPA2), outcome.credentials)
        assertNull(outcome.reason)
    }

    @Test fun appReadMapsOpenNetworksAndRejectsExoticKeyManagement() {
        val open = WifiConfiguration().apply {
            SSID = "Car"
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }
        assertEquals(CarHotspotSavedCredentials("Car", "", ManualHotspotSecurity.OPEN),
            CarHotspotConfigRead.readApp({ open }, ::log).credentials)
        val enterprise = WifiConfiguration().apply {
            SSID = "Car"
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X)
        }
        assertNull(CarHotspotConfigRead.readApp({ enterprise }, ::log).credentials)
        assertNull(CarHotspotConfigRead.readApp({ null }, ::log).credentials)
    }

    @Test fun unsupportedSecurityTypesDoNotMap() {
        assertNull(CarHotspotConfigRead.securityOf(9))
        assertEquals(ManualHotspotSecurity.WPA3_TRANSITION, CarHotspotConfigRead.securityOf(2))
    }
}
