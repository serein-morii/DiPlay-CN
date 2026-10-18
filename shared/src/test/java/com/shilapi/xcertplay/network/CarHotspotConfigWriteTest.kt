package com.shilapi.xcertplay.network

import android.net.wifi.WifiConfiguration
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.shilapi.xcertplay.network.CarHotspotConfigWrite.Result

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarHotspotConfigWriteTest {
    private val logs = mutableListOf<String>()
    private fun log(message: String) { logs += message }
    private val credentials = HotspotCredentials("DiPlay", "password1", ManualHotspotSecurity.WPA2)

    @Test fun onlyAndroidSevenPointOneAndElevenPlusAreSupported() {
        listOf(23, 24, 26, 27, 28, 29).forEach { assertFalse(CarHotspotConfigWrite.supported(it)) }
        listOf(25, 30, 31, 33, 35).forEach { assertTrue(CarHotspotConfigWrite.supported(it)) }
    }

    @Test fun unsupportedSdkIsReportedBeforeAnythingElse() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.UNSUPPORTED_SDK),
            CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(), credentials, sdk = 29, log = ::log))
        assertTrue(logs.single().contains("unsupported Android 29"))
    }

    @Test fun wpa3IsNeverDowngraded() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.SKIPPED), CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
            credentials.copy(security = ManualHotspotSecurity.WPA3), sdk = 25, log = ::log))
    }

    @Test fun credentialsWithLineBreaksAreSkipped() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.SKIPPED), CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
            credentials.copy(passphrase = "pass\nword"), sdk = 25, log = ::log))
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.SKIPPED), CarHotspotConfigWrite.write(RuntimeEnvironment.getApplication(),
            credentials.copy(ssid = "car\rhotspot"), sdk = 30, log = ::log))
    }

    @Test fun writeAppliesRawSsidAndWpa2Credentials() {
        val config = WifiConfiguration()
        CarHotspotConfigWrite.applyTo(config, credentials)
        assertEquals("DiPlay", config.SSID)
        assertEquals("password1", config.preSharedKey)
        assertTrue(config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK))
    }

    @Test fun openHotspotsStayKeyless() {
        val config = WifiConfiguration()
        CarHotspotConfigWrite.applyTo(config, credentials.copy(security = ManualHotspotSecurity.OPEN))
        assertNull(config.preSharedKey)
        assertTrue(config.allowedKeyManagement.isEmpty)
        assertTrue(CarHotspotConfigWrite.matches(config, credentials.copy(security = ManualHotspotSecurity.OPEN)))
        assertFalse(CarHotspotConfigWrite.matches(config, credentials))
    }

    @Test fun fiveGhzIsWrittenThroughReflectionAndAutoKeepsTheDefault() {
        val auto = WifiConfiguration()
        CarHotspotConfigWrite.applyTo(auto, credentials)
        assertEquals(0, auto.javaClass.getField("apBand").get(auto))
        val five = WifiConfiguration()
        CarHotspotConfigWrite.applyTo(five, credentials.copy(band = ManualHotspotBand.GHZ_5))
        assertEquals(WifiConfiguration::class.java.getField("AP_BAND_5GHZ").getInt(null),
            five.javaClass.getField("apBand").get(five))
    }

    @Test fun appWriteVerifiesTheReadback() {
        var written: WifiConfiguration? = null
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "DiPlay"),
            CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { written = it; true },
            readBack = { written },
            log = ::log))
    }

    @Test fun appWriteKeepsTheCarSsidWhenOneIsReadable() {
        val existing = WifiConfiguration().apply { SSID = "Car-Original" }
        var written: WifiConfiguration? = null
        var reads = 0
        val outcome = CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { written = it; true },
            readBack = { reads++; if (reads == 1) existing else written },
            log = ::log)
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "Car-Original"), outcome)
        assertEquals("Car-Original", written?.SSID)
        assertEquals("password1", written?.preSharedKey)
    }

    @Test fun appWriteUsesTheGeneratedSsidWithoutAReadableConfiguration() {
        var written: WifiConfiguration? = null
        val outcome = CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { written = it; true },
            readBack = { written },
            log = ::log)
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "DiPlay"), outcome)
        assertEquals("DiPlay", written?.SSID)
    }

    @Test fun aRejectedWriteFails() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { false },
            readBack = { null },
            log = ::log))
    }

    @Test fun aMissingReadbackFails() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { true },
            readBack = { null },
            log = ::log))
    }

    @Test fun aReadbackWithoutTheWrittenCredentialsFails() {
        val stale = WifiConfiguration().apply { SSID = "Other" }
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { true },
            readBack = { stale },
            log = ::log))
        assertTrue(logs.last().contains("readback does not match"))
    }

    @Test fun reflectionFailuresAreReportedAsFailed() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { throw NoSuchMethodException("setWifiApConfiguration") },
            write = { true },
            readBack = { null },
            log = ::log))
        assertTrue(logs.last().contains("app write failed"))
    }

    @Test fun shellWriteAcceptsTheHelperOkLine() {
        var command: String? = null
        var stdin: ByteArray? = null
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "Car-Original"), CarHotspotConfigWrite.writeAdb(
            { sent, _, pushed -> command = sent; stdin = pushed;
                "noise\nDIPLAY_HOTSPOT_APCONFIG_V1|ok|Q2FyLU9yaWdpbmFs\n" },
            "/data/app/diplay.apk", credentials, 4_000, ::log))
        command!!.let {
            assertTrue(it.contains("app_process /system/bin com.shilapi.xcertplay.network.CarHotspotStartMain"))
            assertTrue(it.contains(" apconfig "))
            assertTrue(it.contains(" 'DiPlay' AUTO"))
            assertFalse(it.contains("password1"))
        }
        assertEquals("9\npassword1", String(stdin!!, Charsets.UTF_8))
    }

    @Test fun anOpenWriteIsRejectedWhenTheCarHasAPassword() {
        val secured = WifiConfiguration().apply { SSID = "Car-Original"; preSharedKey = "carpass" }
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.OPEN_REJECTED),
            CarHotspotConfigWrite.writeApp(credentials.copy(security = ManualHotspotSecurity.OPEN),
            newConfig = { WifiConfiguration() },
            write = { throw AssertionError("no write should happen") },
            readBack = { secured },
            log = ::log))
    }

    @Test fun anOpenWriteOverAnOpenCarHotspotStillWrites() {
        val open = WifiConfiguration().apply {
            SSID = "Car-Original"
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }
        var written: WifiConfiguration? = null
        var reads = 0
        val outcome = CarHotspotConfigWrite.writeApp(credentials.copy(security = ManualHotspotSecurity.OPEN),
            newConfig = { WifiConfiguration() },
            write = { written = it; true },
            readBack = { reads++; if (reads == 1) open else written },
            log = ::log)
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "Car-Original"), outcome)
        assertTrue(written?.preSharedKey.isNullOrEmpty())
        assertTrue(written?.allowedKeyManagement?.isEmpty == true)
    }

    @Test fun isSecuredDistinguishesOpenAndSecuredConfigurations() {
        assertFalse(CarHotspotConfigWrite.isSecured(null))
        assertFalse(CarHotspotConfigWrite.isSecured(WifiConfiguration()))
        val none = WifiConfiguration().apply { allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) }
        assertFalse(CarHotspotConfigWrite.isSecured(none))
        assertTrue(CarHotspotConfigWrite.isSecured(WifiConfiguration().apply { preSharedKey = "carpass" }))
        val wpa2 = WifiConfiguration().apply { allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK) }
        assertTrue(CarHotspotConfigWrite.isSecured(wpa2))
    }

    @Test fun shellWriteRefusesToStripTheCarPassword() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.OPEN_REJECTED), CarHotspotConfigWrite.writeAdb(
            { _, _, _ -> "DIPLAY_HOTSPOT_APCONFIG_V1|error open" }, "", credentials, 4_000, ::log))
    }

    @Test fun shellWriteRejectsFailuresAndSilence() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeAdb(
            { _, _, _ -> "DIPLAY_HOTSPOT_APCONFIG_V1|error rejected" }, "", credentials, 4_000, ::log))
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.FAILED), CarHotspotConfigWrite.writeAdb(
            { _, _, _ -> null }, "", credentials, 4_000, ::log))
        assertTrue(logs.last().contains("no reply"))
    }

    @Test fun shellWriteAcceptsAnOkLineWithoutASsid() {
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, null), CarHotspotConfigWrite.writeAdb(
            { _, _, _ -> "DIPLAY_HOTSPOT_APCONFIG_V1|ok" }, "", credentials, 4_000, ::log))
    }

    @Test fun parsesTheApconfigProtocolLine() {
        assertEquals("ok|Car-Original",
            CarHotspotStartMain.parseApConfig("x\nDIPLAY_HOTSPOT_APCONFIG_V1|ok|Car-Original\nmore"))
        assertEquals("error rejected", CarHotspotStartMain.parseApConfig("DIPLAY_HOTSPOT_APCONFIG_V1|error rejected"))
        assertNull(CarHotspotStartMain.parseApConfig("nothing"))
        assertNull(CarHotspotStartMain.parseApConfig(null))
    }

    @Test fun readsTheSavedSsidFromTheHelperReply() {
        assertEquals("Car-Original", CarHotspotStartMain.apConfigSsid("ok|Q2FyLU9yaWdpbmFs"))
        assertNull(CarHotspotStartMain.apConfigSsid("ok"))
        assertNull(CarHotspotStartMain.apConfigSsid("ok|"))
        assertNull(CarHotspotStartMain.apConfigSsid("ok|Car-Original"))
        assertNull(CarHotspotStartMain.apConfigSsid("error rejected"))
        assertNull(CarHotspotStartMain.apConfigSsid(null))
        assertEquals("pass|word", CarHotspotStartMain.apConfigPassphrase("ok|Q2Fy|cGFzc3x3b3Jk|1"))
        assertEquals("", CarHotspotStartMain.apConfigPassphrase("ok|Q2Fy"))
        assertEquals("", CarHotspotStartMain.apConfigPassphrase("ok|Q2Fy||1"))
        assertEquals(1, CarHotspotStartMain.apConfigSecurity("ok|Q2Fy|cGFzc3x3b3Jk|1"))
        assertNull(CarHotspotStartMain.apConfigSecurity("ok|Q2Fy|cGFzc3x3b3Jk"))
        assertNull(CarHotspotStartMain.apConfigSecurity("ok|Q2Fy|cGFzc3x3b3Jk|x"))
        assertTrue(CarHotspotStartMain.apConfigAccepted("ok|Car-Original"))
        assertTrue(CarHotspotStartMain.apConfigAccepted("ok"))
        assertFalse(CarHotspotStartMain.apConfigAccepted("error rejected"))
        assertFalse(CarHotspotStartMain.apConfigAccepted(null))
    }

    @Test fun ssidQuotesAreShellEscaped() {
        val command = CarHotspotStartMain.apConfigCommand("/a/b.apk",
            HotspotCredentials("it's", "pass'word", ManualHotspotSecurity.WPA2))
        assertTrue(command.contains(" 'it'\"'\"'s' AUTO"))
        assertFalse(command.contains("pass'word"))
    }

    @Test fun appWriteKeepsTheSavedHiddenAndBandFields() {
        val written = mutableListOf<WifiConfiguration>()
        val current = WifiConfiguration().apply {
            SSID = "Car-Original"
            hiddenSSID = true
            javaClass.getField("apBand").setInt(this, WifiConfiguration::class.java.getField("AP_BAND_2GHZ").getInt(null))
            javaClass.getField("apChannel").setInt(this, 6)
        }
        val outcome = CarHotspotConfigWrite.writeApp(credentials,
            newConfig = { WifiConfiguration() },
            write = { written += it; true },
            readBack = { written.lastOrNull() ?: current },
            log = ::log)
        assertEquals(CarHotspotConfigWrite.WriteOutcome(Result.WRITTEN, "Car-Original"), outcome)
        val saved = written.single()
        assertTrue(saved.hiddenSSID)
        assertEquals(6, saved.javaClass.getField("apChannel").getInt(saved))
        assertEquals(WifiConfiguration::class.java.getField("AP_BAND_2GHZ").getInt(null),
            saved.javaClass.getField("apBand").getInt(saved))
    }

    @Test fun appWriteMovesToFiveGhzOnlyWhenAskedAndTheCarIsNotThere() {
        val written = mutableListOf<WifiConfiguration>()
        val current = WifiConfiguration().apply {
            SSID = "Car-Original"
            javaClass.getField("apBand").setInt(this, WifiConfiguration::class.java.getField("AP_BAND_2GHZ").getInt(null))
            javaClass.getField("apChannel").setInt(this, 6)
        }
        val fiveGhzCredentials = credentials.copy(band = ManualHotspotBand.GHZ_5)
        CarHotspotConfigWrite.writeApp(fiveGhzCredentials,
            newConfig = { WifiConfiguration() },
            write = { written += it; true },
            readBack = { current },
            log = ::log)
        val saved = written.single()
        assertEquals(WifiConfiguration::class.java.getField("AP_BAND_5GHZ").getInt(null),
            saved.javaClass.getField("apBand").getInt(saved))
    }
}
