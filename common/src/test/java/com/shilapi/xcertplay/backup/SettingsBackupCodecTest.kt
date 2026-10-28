package com.shilapi.xcertplay.backup

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayPicture
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.shilapi.xcertplay.network.CarHotspotSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsBackupCodecTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun navigationPromptAudioFocusTravelsWithTheAudioFocusSettings() {
        AirPlayPersistence.saveNavigationPromptAudioFocus(context, true)
        val document = JSONObject(String(SettingsBackupCodec.export(context, "0.2.22")))
        val airplay = document.getJSONObject("settings").getJSONObject("xcertplay_airplay")
        assertTrue(airplay.getBoolean("navigation_prompt_audio_focus"))
    }

    @Test
    fun exportOmitsSensitiveAndNavigationKeys() {
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 60)
            .putInt("black_corner_radius_dp", 24)
            .putString("identity_private", "secret")
            .putString("manual_hotspot_passphrase", "wifi-pass")
            .putStringSet("pairing_ids", setOf("pair-1"))
            .putString("pairing.0F7A", "a1b2")
            .putString("manual_hotspot_ssid", "car-ap")
            .putString("mfi_target", "REMOTE")
            .putBoolean("cluster_map_enabled", true)
            .apply()
        prefs("diplay").edit()
            .putBoolean("auto_connect", true)
            .putString("page", "settings")
            .putString("phone_name", "iphone-of-owner")
            .apply()
        prefs("carplay_picture").edit().putInt("brightness", 80).apply()
        val document = JSONObject(String(SettingsBackupCodec.export(context, "0.2.17")))
        assertEquals(SettingsBackupCodec.FORMAT, document.getString("format"))
        assertEquals(SettingsBackupCodec.VERSION, document.getInt("version"))
        val airplay = document.getJSONObject("settings").getJSONObject("xcertplay_airplay")
        assertEquals(60, airplay.getInt("display_fps"))
        assertEquals(24, airplay.getInt("black_corner_radius_dp"))
        assertTrue(airplay.getBoolean("cluster_map_enabled"))
        assertFalse(airplay.has("manual_hotspot_ssid"))
        assertFalse(airplay.has("pairing.0F7A"))
        assertFalse(airplay.has("mfi_target"))
        assertFalse(airplay.has("identity_private"))
        assertFalse(airplay.has("manual_hotspot_passphrase"))
        assertFalse(airplay.has("pairing_ids"))
        val diplay = document.getJSONObject("settings").getJSONObject("diplay")
        assertTrue(diplay.getBoolean("auto_connect"))
        assertFalse(diplay.has("page"))
        assertFalse(diplay.has("phone_name"))
        assertEquals(80, document.getJSONObject("settings").getJSONObject("carplay_picture").getInt("brightness"))
        assertFalse(document.getJSONObject("settings").has("diplay_car_hotspot"))
    }

    @Test
    fun importAppliesKnownKeysAndSkipsUnknownOrForbidden() {
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"display_fps": 48, "brand_new_key": 7, "manual_hotspot_passphrase": "x",
                "pairing.attacker": "00ff"},
              "diplay": {"auto_connect": true, "page": "about"},
              "carplay_picture": {"brightness": 66}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(false, outcome.invalid)
        assertEquals(3, outcome.applied)
        assertEquals(4, outcome.skipped)
        assertEquals(48, prefs("xcertplay_airplay").getInt("display_fps", 0))
        assertFalse(prefs("xcertplay_airplay").contains("manual_hotspot_passphrase"))
        // Only listed settings travel: an imported file cannot add an iPhone pairing key or any unknown key.
        assertFalse(prefs("xcertplay_airplay").contains("pairing.attacker"))
        assertFalse(prefs("xcertplay_airplay").contains("brand_new_key"))
        assertEquals(true, prefs("diplay").getBoolean("auto_connect", false))
        assertFalse(prefs("diplay").contains("page"))
        assertEquals(66, prefs("carplay_picture").getInt("brightness", 0))
    }

    // Each of these is saved under a name the list did not have, so an export left it behind: the
    // resolution percentage (the list had only its older key) and settings added after the list.
    @Test
    fun aFreshUnitGetsBackTheResolutionAndTheNewerSettings() {
        AirPlayPersistence.saveDisplayScalePercent(context, 85)
        AirPlayPersistence.saveAmbientDelaySeconds(context, 10)
        AirPlayPersistence.saveNavigationAudioAttenuationDb(context, -8)
        AirPlayPersistence.saveCallSpeakerphone(context, true)
        AirPlayPersistence.saveLegacyClusterEnabled(context, true)
        AirPlayPersistence.saveLauncherBubble(context, true)
        AirPlayPersistence.saveLauncherBubbleSize(context, 16)
        val document = SettingsBackupCodec.export(context, "0.2.21")
        prefs("xcertplay_airplay").edit().clear().commit()

        assertFalse(SettingsBackupCodec.import(context, document).invalid)

        assertEquals(85, AirPlayPersistence.loadDisplayScalePercent(context))
        assertEquals(10, AirPlayPersistence.loadAmbientDelaySeconds(context))
        assertEquals(-8, AirPlayPersistence.loadNavigationAudioAttenuationDb(context))
        assertTrue(AirPlayPersistence.loadCallSpeakerphone(context))
        assertTrue(AirPlayPersistence.loadLegacyClusterEnabled(context))
        assertTrue(AirPlayPersistence.loadLauncherBubble(context))
        assertEquals(16, AirPlayPersistence.loadLauncherBubbleSize(context))
    }

    @Test
    fun importedPictureAndHotspotFilesTakeOnlyTheirOwnKeys() {
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "carplay_picture": {"brightness": 10, "contrast": 120, "saturation": 90, "warmth": -5, "planted_key": "x"},
              "diplay_car_hotspot": {"auto_enable": true, "config_write": true, "planted_key": true}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(6, outcome.applied)
        assertEquals(2, outcome.skipped)
        assertEquals(10, prefs("carplay_picture").getInt("brightness", 0))
        assertEquals(120, prefs("carplay_picture").getInt("contrast", 0))
        assertEquals(90, prefs("carplay_picture").getInt("saturation", 0))
        assertEquals(-5, prefs("carplay_picture").getInt("warmth", 0))
        assertTrue(prefs("diplay_car_hotspot").getBoolean("auto_enable", false))
        assertTrue(prefs("diplay_car_hotspot").getBoolean("config_write", false))
        assertFalse(prefs("carplay_picture").contains("planted_key"))
        assertFalse(prefs("diplay_car_hotspot").contains("planted_key"))
    }

    @Test
    fun anImportedPictureValueOfAnotherTypeNeverReachesTheReaderOfThatKey() {
        // On a fresh install the key does not exist yet, so nothing tells the codec its type. The reader calls getInt.
        for (wrong in listOf("\"x\"", "1.5", "99999999999", "true")) {
            prefs("carplay_picture").edit().clear().commit()
            SettingsBackupCodec.import(context, """{"format": "diplay-settings", "version": 1, "settings": {"carplay_picture": {"brightness": $wrong}}}""".toByteArray())
            CarPlayPicture.value(prefs("carplay_picture"), "brightness") // ClassCastException when a String, Float or Long was stored
        }
    }

    @Test
    fun anImportedHotspotValueOfAnotherTypeNeverReachesTheReaderOfThatKey() {
        for (wrong in listOf("\"yes\"", "1", "1.5")) {
            prefs("diplay_car_hotspot").edit().clear().commit()
            SettingsBackupCodec.import(context, """{"format": "diplay-settings", "version": 1, "settings": {"diplay_car_hotspot": {"auto_enable": $wrong, "config_write": $wrong}}}""".toByteArray())
            CarHotspotSettings.enabled(context) // ClassCastException when a String, Int or Float was stored
            CarHotspotSettings.configWrite(context)
        }
    }

    @Test
    fun exportKeepsUnlistedKeysOfPictureAndHotspotFilesOut() {
        prefs("carplay_picture").edit().putInt("brightness", 5).putString("stray", "x").apply()
        prefs("diplay_car_hotspot").edit().putBoolean("auto_enable", true).putString("stray", "x").apply()
        val settings = JSONObject(String(SettingsBackupCodec.export(context, "0.2.22"))).getJSONObject("settings")
        assertEquals(5, settings.getJSONObject("carplay_picture").getInt("brightness"))
        assertTrue(settings.getJSONObject("diplay_car_hotspot").getBoolean("auto_enable"))
        assertFalse(settings.getJSONObject("carplay_picture").has("stray"))
        assertFalse(settings.getJSONObject("diplay_car_hotspot").has("stray"))
    }

    @Test
    fun importRejectsForeignOrNewerDocuments() {
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"other\",\"version\":1,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":99,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "not json".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{}".toByteArray()).invalid)
    }

    @Test
    fun importMatchesTypesAgainstStoredValues() {
        prefs("xcertplay_airplay").edit().putInt("display_fps", 60).putBoolean("hevc_enabled", false).apply()
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"display_fps": "48", "hevc_enabled": true, "display_scale_tenths": 85}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(2, outcome.applied)
        assertEquals(1, outcome.skipped)
        assertEquals(60, prefs("xcertplay_airplay").getInt("display_fps", 0))
        assertEquals(true, prefs("xcertplay_airplay").getBoolean("hevc_enabled", true))
        assertEquals(85, prefs("xcertplay_airplay").getInt("display_scale_tenths", 0))
    }

    @Test
    fun importRejectsMissingOrUnsupportedVersions() {
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":0,\"settings\":{}}".toByteArray()).invalid)
        assertTrue(SettingsBackupCodec.import(context, "{\"format\":\"diplay-settings\",\"version\":-1,\"settings\":{}}".toByteArray()).invalid)
    }

    @Test
    fun importRejectsOutOfRangeOrFractionalNumbers() {
        prefs("xcertplay_airplay").edit()
            .putLong("cluster_test_counter", 5L)
            .putFloat("ui_scale_percent", 1f)
            .putInt("display_fps", 60)
            .apply()
        val document = """
            {"format": "diplay-settings", "version": 1, "settings": {
              "xcertplay_airplay": {"cluster_test_counter": 1.5, "ui_scale_percent": 1e300, "display_fps": 3000000000}
            }}
        """.trimIndent()
        val outcome = SettingsBackupCodec.import(context, document.toByteArray())
        assertEquals(0, outcome.applied)
        assertEquals(3, outcome.skipped)
        assertEquals(5L, prefs("xcertplay_airplay").getLong("cluster_test_counter", 0L))
        assertEquals(1f, prefs("xcertplay_airplay").getFloat("ui_scale_percent", 0f))
        assertEquals(60, prefs("xcertplay_airplay").getInt("display_fps", 0))
    }

    @Test
    fun exportImportRoundTripRestoresValues() {
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 60)
            .putBoolean("temporary_system_bars", true)
            .putBoolean("hevc_enabled", true)
            .putString("manual_hotspot_ssid", "car-ap")
            .putFloat("ui_scale_percent", 100f)
            .apply()
        prefs("diplay_car_hotspot").edit().putBoolean("auto_enable", true).apply()
        val document = SettingsBackupCodec.export(context, "0.2.17")
        prefs("xcertplay_airplay").edit()
            .putInt("display_fps", 30)
            .putBoolean("temporary_system_bars", false)
            .putBoolean("hevc_enabled", false)
            .putString("manual_hotspot_ssid", "changed")
            .putFloat("ui_scale_percent", 120f)
            .apply()
        prefs("diplay_car_hotspot").edit().putBoolean("auto_enable", false).apply()
        val outcome = SettingsBackupCodec.import(context, document)
        assertEquals(false, outcome.invalid)
        val airplay = prefs("xcertplay_airplay")
        assertEquals(60, airplay.getInt("display_fps", 0))
        assertTrue(airplay.getBoolean("temporary_system_bars", false))
        assertEquals(true, airplay.getBoolean("hevc_enabled", false))
        // The car's hotspot name is not part of a backup.
        assertEquals("changed", airplay.getString("manual_hotspot_ssid", ""))
        assertEquals(100f, airplay.getFloat("ui_scale_percent", 0f))
        assertEquals(true, prefs("diplay_car_hotspot").getBoolean("auto_enable", false))
        assertFalse(airplay.contains("identity_private"))
    }
}
