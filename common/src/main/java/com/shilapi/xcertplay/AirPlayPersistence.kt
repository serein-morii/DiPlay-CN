package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.airplay.SafeAreaCodec
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.network.WifiP2pChannels
import com.shilapi.xcertplay.transport.LockdownPairRecord
import java.io.File

/** SharedPreferences persistence for the accessory identity and paired controllers. */
object AirPlayPersistence {
    /** 0 uses usage-based routing; 1–20 select stream types supported by the head unit. */
    val AUDIO_CHANNELS = 0..20
    private const val PREFS = "xcertplay_airplay"
    private const val KEY_IDENT_PRIVATE = "identity_private"
    private const val KEY_IDENT_PUBLIC = "identity_public"
    private const val KEY_PAIRING_ID = "pairing_id"
    private const val KEY_PAIRING_IDS = "pairing_ids"
    private const val KEY_LOCKDOWN_HOST_ID = "lockdown_host_id"
    private const val KEY_LOCKDOWN_SYSTEM_BUID = "lockdown_system_buid"
    private const val KEY_LOCKDOWN_WIFI_MAC = "lockdown_wifi_mac"
    private const val KEY_LOCKDOWN_DEVICE_PUBLIC = "lockdown_device_public"
    private const val KEY_LOCKDOWN_DEVICE_CERT = "lockdown_device_cert"
    private const val KEY_LOCKDOWN_HOST_PRIVATE = "lockdown_host_private"
    private const val KEY_LOCKDOWN_HOST_CERT = "lockdown_host_cert"
    private const val KEY_LOCKDOWN_ROOT_PRIVATE = "lockdown_root_private"
    private const val KEY_LOCKDOWN_ROOT_CERT = "lockdown_root_cert"
    private const val KEY_DISPLAY_SCALE_TENTHS = "display_scale_tenths"
    private const val KEY_UI_SCALE_PERCENT = "ui_scale_percent"
    private const val KEY_HEVC_ENABLED = "hevc_enabled"
    private const val KEY_HEVC_SOFTWARE_DECODER = "hevc_software_decoder"
    private const val KEY_ADVANCED_AUDIO_CHANNEL_MAPPING = "advanced_audio_channel_mapping"
    private const val KEY_AUDIO_FOCUS_ENABLED = "audio_focus_enabled"
    private const val KEY_AUDIO_FOCUS_AUTO_YIELD = "audio_focus_auto_yield"
    private const val KEY_MEDIA_AUDIO_CHANNEL = "media_audio_channel"
    private const val KEY_NAVIGATION_AUDIO_CHANNEL = "navigation_audio_channel"
    private const val KEY_NAVIGATION_STREAM_TYPE = "navigation_stream_type"
    private const val KEY_WIRELESS_ENABLED = "wireless_enabled"
    private const val KEY_WIRELESS_HOTSPOT_MODE = "wireless_hotspot_mode"
    private const val KEY_WIFI_P2P_PREFERRED_CHANNEL = "wifi_p2p_preferred_channel"
    private const val KEY_MANUAL_HOTSPOT_SSID = "manual_hotspot_ssid"
    private const val KEY_MANUAL_HOTSPOT_PASSPHRASE = "manual_hotspot_passphrase"
    private const val KEY_MANUAL_HOTSPOT_BAND = "manual_hotspot_band"
    private const val KEY_MANUAL_HOTSPOT_CHANNEL = "manual_hotspot_channel"
    private const val KEY_MANUAL_HOTSPOT_SECURITY = "manual_hotspot_security"
    private const val KEY_DEBUG_LOGS_ENABLED = "debug_logs_enabled"
    private const val KEY_MANUFACTURER = "manufacturer"
    private const val KEY_MODEL = "model"
    private const val KEY_OEM_LABEL = "oem_label"
    private const val KEY_APP_APPEARANCE = "app_appearance"
    private const val KEY_CARPLAY_NIGHT_MODE = "carplay_night_mode"
    private const val KEY_CARPLAY_NIGHT_START = "carplay_night_start_minute"
    private const val KEY_CARPLAY_NIGHT_END = "carplay_night_end_minute"
    private const val KEY_AMBIENT_LUX_THRESHOLD = "ambient_lux_threshold"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MEDIA_BUFFER_MS = "media_buffer_ms"
    private const val KEY_MAIN_BUFFERED_AUDIO = "main_buffered_audio"
    private const val KEY_CAR_BLUETOOTH_AUDIO = "car_bluetooth_audio"
    private const val KEY_CALL_ECHO_CANCELLATION = "call_echo_cancellation"
    private const val KEY_CALL_VOICE_FILTER = "call_voice_filter"
    private const val KEY_SMOOTH_VIDEO = "smooth_video"
    private const val KEY_DIRECT_VIDEO_OUTPUT = "direct_video_output"
    private const val KEY_LOW_LATENCY_DECODER = "low_latency_decoder"
    private const val KEY_FPS_COUNTER = "fps_counter"
    private const val KEY_CLUSTER_MAP = "cluster_map_enabled"
    private const val KEY_ADB_CLUSTER_ACTIVITY = "adb_cluster_activity_enabled"
    private const val KEY_CENTER_MAP_OVERLAY = "center_map_overlay"
    private const val KEY_CENTER_MAP_AUTO_HIDE = "center_map_auto_hide"
    private const val KEY_LAUNCHER_MAP_SHARING = "launcher_map_sharing"
    private const val KEY_CLUSTER_MAP_SCALE = "cluster_map_scale_percent"
    private const val KEY_CLUSTER_CONTENT = "cluster_content"
    private const val KEY_CLUSTER_MARKER_X = "cluster_marker_horizontal_step"
    private const val KEY_CLUSTER_MARKER_Y = "cluster_marker_vertical_step"
    private const val KEY_CLUSTER_MARKER_X_PERCENT = "cluster_marker_x_percent"
    private const val KEY_CLUSTER_MARKER_Y_PERCENT = "cluster_marker_y_percent"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION = "cluster_turn_card_overlay_position"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE = "cluster_turn_card_overlay_size"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_X = "cluster_turn_card_overlay_x_percent"
    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_Y = "cluster_turn_card_overlay_y_percent"
    private const val KEY_CENTER_MAP_FOLLOWS_DASHBOARD = "center_map_follows_dashboard"
    private const val KEY_SETTINGS_GESTURE_FINGERS = "settings_gesture_fingers"
    private const val KEY_SWIPE_OPENS_FULL_SETTINGS = "swipe_opens_full_settings"
    private const val KEY_LAUNCHER_RETURNS_TO_CARPLAY = "launcher_returns_to_carplay"
    private const val KEY_WIDTH_PHYSICAL_MM = "display_width_physical_mm"
    private const val KEY_PHYSICAL_SIZE_BASIS = "display_physical_size_basis"
    private const val KEY_MAX_DETECTED_WIDTH = "display_max_detected_width"
    private const val KEY_MAX_DETECTED_HEIGHT = "display_max_detected_height"
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_HIDE_TOP_BAR = "hide_top_bar"
    private const val KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar"
    private const val KEY_SAFE_AREA_DRAW_OUTSIDE = "safe_area_draw_outside"
    private const val KEY_ADAPT_PIP_RESOLUTION = "adapt_pip_resolution"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_BT_SUSPEND_DURING_CARPLAY = "bt_suspend_during_carplay"
    private const val KEY_BT_SUSPEND_DELAY = "bt_suspend_delay_seconds"
    private const val KEY_LOCATION_REPORTING_ENABLED = "location_reporting_enabled"
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_MFI_I2C_PATH = "mfi_i2c_path"
    private const val KEY_REMOTE_MFI_SERVER = "remote_mfi_server"
    private const val KEY_REMOTE_MFI_TOKEN = "remote_mfi_token"
    private const val SAFE_AREA_KEY_PREFIX = "safe_area_"
    private const val CUSTOM_ICON_FILE = "airplay-icon.png"

    const val DEFAULT_MANUFACTURER = "DiPlay"
    const val DEFAULT_MODEL = "DiPlay"
    const val DEFAULT_OEM_LABEL = "BYD"
    const val DEFAULT_MFI_I2C_PATH = "/dev/i2c-1"

    fun loadAmbientDelaySeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("ambient_delay_seconds", 2).coerceIn(0, 60)

    fun saveAmbientDelaySeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("ambient_delay_seconds", seconds.coerceIn(0, 60)).apply()
    }

    fun loadDisplayScalePercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("display_scale_percent", loadDisplayScaleTenths(context) * 10)
            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)

    fun saveDisplayScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                "display_scale_percent",
                percent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT),
            ).apply()
    }
    /** Applied by the CarPlay host so overlay position/size updates without reconnecting. */
    @Volatile var overlaySettingsListener: (() -> Unit)? = null

    private const val KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT = "cluster_turn_card_overlay_size_percent"
    private const val KEY_CLUSTER_TURN_CARD_OPACITY = "cluster_turn_card_opacity_percent"
    private const val KEY_CLUSTER_MARKER_X_PERCENT = "cluster_marker_x_percent"
    private const val KEY_CLUSTER_MARKER_Y_PERCENT = "cluster_marker_y_percent"
    private const val KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT = "cluster_small_window_marker_x_percent"
    private const val KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT = "cluster_small_window_marker_y_percent"
    private const val KEY_CLUSTER_TURN_CARD_THEME = "cluster_turn_card_theme"
    private const val KEY_CLUSTER_SMALL_WINDOW_CARD_THEME = "cluster_small_window_card_theme"
    private const val KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE = "cluster_small_window_card_size"
    private const val KEY_CLUSTER_SMALL_WINDOW_CARD_X = "cluster_small_window_card_x"
    private const val KEY_CLUSTER_SMALL_WINDOW_CARD_Y = "cluster_small_window_card_y"
    private const val KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY = "cluster_small_window_card_opacity_percent"

    fun loadDisplayScaleTenths(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CarPlayDisplayScale.sanitize(
            prefs.getInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.DEFAULT_TENTHS),
        )
    }

    fun saveDisplayScaleTenths(context: Context, tenths: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_DISPLAY_SCALE_TENTHS, CarPlayDisplayScale.sanitize(tenths))
            .apply()
    }

    fun loadHevcEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_ENABLED, false)

    fun loadUiScalePercent(context: Context): Int = CarPlayUiScale.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.DEFAULT),
    )

    fun saveUiScalePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_UI_SCALE_PERCENT, CarPlayUiScale.sanitize(percent)).apply()
    }

    fun saveHevcEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_ENABLED, enabled)
            .apply()
    }

    fun loadHevcSoftwareDecoderEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HEVC_SOFTWARE_DECODER, false)

    fun saveHevcSoftwareDecoderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HEVC_SOFTWARE_DECODER, enabled)
            .apply()
    }

    fun loadAdvancedAudioChannelMapping(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, false)

    fun saveAdvancedAudioChannelMapping(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADVANCED_AUDIO_CHANNEL_MAPPING, enabled)
            .apply()
    }

    fun loadNavigationStreamType(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NAVIGATION_STREAM_TYPE, 14)

    fun saveNavigationStreamType(context: Context, streamType: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_STREAM_TYPE, streamType)
            .apply()
    }

    fun loadAudioFocusEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_ENABLED, false)

    fun saveAudioFocusEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_ENABLED, enabled)
            .apply()
    }

    fun loadAudioFocusAutoYield(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, true)

    fun saveAudioFocusAutoYield(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUDIO_FOCUS_AUTO_YIELD, enabled)
            .apply()
    }

    fun loadMediaAudioChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_AUDIO_CHANNEL, 0)
            .takeIf { it in AUDIO_CHANNELS } ?: 0

    fun saveMediaAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_AUDIO_CHANNEL, channel.takeIf { it in AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadNavigationAudioChannel(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Inherit the legacy value only when the new key is absent; preserve fresh-install and explicit 0 defaults.
        return prefs.getInt(KEY_NAVIGATION_AUDIO_CHANNEL, prefs.getInt(KEY_NAVIGATION_STREAM_TYPE, 0))
            .takeIf { it in AUDIO_CHANNELS } ?: 0
    }

    fun saveNavigationAudioChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_NAVIGATION_AUDIO_CHANNEL, channel.takeIf { it in AUDIO_CHANNELS } ?: 0)
            .apply()
    }

    fun loadWirelessEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIRELESS_ENABLED, true)

    fun saveWirelessEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_WIRELESS_ENABLED, enabled)
            .apply()
    }

    fun loadMfiTarget(context: Context): MfiTarget {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_TARGET, null)
        return MfiTarget.entries.firstOrNull { it.name == stored } ?: MfiTarget.LOCAL
    }

    fun saveMfiTarget(context: Context, target: MfiTarget) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_TARGET, target.name)
            .apply()
    }

    fun loadMfiI2cPath(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MFI_I2C_PATH, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MFI_I2C_PATH

    fun saveMfiI2cPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MFI_I2C_PATH, path.trim())
            .apply()
    }

    fun loadRemoteMfiServer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_SERVER, null)
            .orEmpty()

    fun saveRemoteMfiServer(context: Context, server: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_SERVER, server)
            .apply()
    }

    fun loadRemoteMfiToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REMOTE_MFI_TOKEN, null)
            .orEmpty()

    fun saveRemoteMfiToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_REMOTE_MFI_TOKEN, token)
            .apply()
    }

    fun loadWirelessHotspotMode(context: Context): WirelessHotspotMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_WIRELESS_HOTSPOT_MODE, null)
        val mode = WirelessHotspotMode.entries.firstOrNull { it.name == stored }
            ?: WirelessHotspotMode.MANUAL
        val supported = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) WirelessHotspotMode.MANUAL else mode
        if (stored != supported.name) saveWirelessHotspotMode(context, supported)
        return supported
    }

    fun saveWirelessHotspotMode(context: Context, mode: WirelessHotspotMode) {
        val supported = if (mode == WirelessHotspotMode.LOCAL_ONLY_HOTSPOT) WirelessHotspotMode.MANUAL else mode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_WIRELESS_HOTSPOT_MODE, supported.name)
            .apply()
    }

    fun loadWifiP2pPreferredChannel(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, WifiP2pChannels.AUTO)
            .takeIf(WifiP2pChannels::isValid) ?: WifiP2pChannels.AUTO
    }.getOrDefault(WifiP2pChannels.AUTO)

    fun saveWifiP2pPreferredChannel(context: Context, channel: Int) {
        require(WifiP2pChannels.isValid(channel))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_WIFI_P2P_PREFERRED_CHANNEL, channel).apply()
    }

    fun loadExistingWifiSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("existing_wifi_ssid", "").orEmpty()

    fun loadExistingWifiPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("existing_wifi_passphrase", "").orEmpty()

    fun saveExistingWifiCredentials(context: Context, ssid: String, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("existing_wifi_ssid", ssid)
            .putString("existing_wifi_passphrase", passphrase).apply()
    }

    fun loadManualHotspotSsid(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_SSID, null)
            .orEmpty()

    fun saveManualHotspotSsid(context: Context, ssid: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SSID, ssid)
            .apply()
    }

    fun loadManualHotspotPassphrase(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_PASSPHRASE, null)
            .orEmpty()

    fun saveManualHotspotPassphrase(context: Context, passphrase: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_PASSPHRASE, passphrase)
            .apply()
    }

    fun loadManualHotspotBand(context: Context): ManualHotspotBand {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUAL_HOTSPOT_BAND, null)
        return ManualHotspotBand.entries.firstOrNull { it.name == stored }
            ?: ManualHotspotBand.AUTO
    }

    fun saveManualHotspotBand(context: Context, band: ManualHotspotBand) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_BAND, band.name)
            .apply()
    }

    fun loadManualHotspotChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MANUAL_HOTSPOT_CHANNEL, 0)
            .coerceIn(0, 196)

    fun saveManualHotspotChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MANUAL_HOTSPOT_CHANNEL, channel.coerceIn(0, 196))
            .apply()
    }

    fun loadManualHotspotSecurity(context: Context): ManualHotspotSecurity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MANUAL_HOTSPOT_SECURITY, null)
        return ManualHotspotSecurity.entries.firstOrNull { it.name == stored }
            ?: if (loadManualHotspotPassphrase(context).isEmpty()) {
                ManualHotspotSecurity.OPEN
            } else {
                ManualHotspotSecurity.WPA2
            }
    }

    fun saveManualHotspotSecurity(context: Context, security: ManualHotspotSecurity) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUAL_HOTSPOT_SECURITY, security.name)
            .apply()
    }

    fun loadDebugLogsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_LOGS_ENABLED, false)

    fun saveDebugLogsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DEBUG_LOGS_ENABLED, enabled)
            .apply()
    }

    fun loadAutoStartOnBoot(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_START_ON_BOOT, false)

    fun saveAutoStartOnBoot(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
            .apply()
    }

    fun loadBtSuspendDuringCarplay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BT_SUSPEND_DURING_CARPLAY, false)

    fun saveBtSuspendDuringCarplay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BT_SUSPEND_DURING_CARPLAY, enabled).apply()
    }

    fun loadBtSuspendDelaySeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BT_SUSPEND_DELAY, 10).let { if (it in listOf(5, 10, 15, 30)) it else 10 }

    fun saveBtSuspendDelaySeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BT_SUSPEND_DELAY, if (seconds in listOf(5, 10, 15, 30)) seconds else 10).apply()
    }

    fun loadLocationReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCATION_REPORTING_ENABLED, false)

    fun saveLocationReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LOCATION_REPORTING_ENABLED, enabled)
            .apply()
    }

    fun loadManufacturer(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANUFACTURER, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANUFACTURER

    fun saveManufacturer(context: Context, manufacturer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANUFACTURER, manufacturer)
            .apply()
    }

    fun loadModel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MODEL

    fun saveModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODEL, model)
            .apply()
    }

    fun loadOemLabel(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)
            // iOS hides the car icon without a label.
            .orEmpty().ifBlank { DEFAULT_OEM_LABEL }

    fun saveOemLabel(context: Context, oemLabel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OEM_LABEL, oemLabel)
            .apply()
    }

    fun loadAmbientLightThreshold(context: Context): AmbientLightThreshold = AmbientLightThreshold.fromStored(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_AMBIENT_LUX_THRESHOLD, AmbientLightThreshold.DEFAULT_LUX),
    )

    fun saveAmbientLightThreshold(context: Context, threshold: AmbientLightThreshold) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AMBIENT_LUX_THRESHOLD, threshold.lux).apply()
    }

    fun loadAppAppearance(context: Context): AppAppearance = AppAppearance.fromKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APP_APPEARANCE, null),
    )

    fun saveAppAppearance(context: Context, appearance: AppAppearance) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APP_APPEARANCE, appearance.key).apply()
    }

    fun loadCarPlayNightMode(context: Context): CarPlayNightMode = CarPlayNightMode.fromKey(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CARPLAY_NIGHT_MODE, null),
    )

    fun saveCarPlayNightMode(context: Context, mode: CarPlayNightMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CARPLAY_NIGHT_MODE, mode.key).apply()
    }

    fun loadCarPlayNightSchedule(context: Context): CarPlayNightSchedule {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaults = CarPlayNightSchedule()
        val start = preferences.getInt(KEY_CARPLAY_NIGHT_START, defaults.startMinute)
        val end = preferences.getInt(KEY_CARPLAY_NIGHT_END, defaults.endMinute)
        return CarPlayNightSchedule(
            start.takeIf { it in 0 until 24 * 60 } ?: defaults.startMinute,
            end.takeIf { it in 0 until 24 * 60 } ?: defaults.endMinute,
        )
    }

    fun saveCarPlayNightSchedule(context: Context, schedule: CarPlayNightSchedule) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CARPLAY_NIGHT_START, schedule.startMinute)
            .putInt(KEY_CARPLAY_NIGHT_END, schedule.endMinute)
            .apply()
    }

    fun loadFps(context: Context): Int = AirPlayDisplaySettings.sanitizeFps(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, 30),
    )

    fun loadMediaBufferMillis(context: Context): Int = com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.DEFAULT_MILLIS),
    )

    /** CarPlay's buffered music (Apple Music sends ahead over TCP); off by default, applies at reconnect. */
    fun loadMainBufferedAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MAIN_BUFFERED_AUDIO, false)

    fun saveMainBufferedAudio(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_MAIN_BUFFERED_AUDIO, enabled).apply()
    }

    /** Offer no CarPlay audio, so the iPhone keeps audio on its Bluetooth link with the car; applies at reconnect. */
    fun loadCarBluetoothAudio(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAR_BLUETOOTH_AUDIO, false)

    fun saveCarBluetoothAudio(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CAR_BLUETOOTH_AUDIO, enabled).apply()
    }

    fun loadSmoothVideo(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SMOOTH_VIDEO, false)

    fun saveSmoothVideo(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SMOOTH_VIDEO, enabled).apply()
    }

    fun loadDirectVideoOutput(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DIRECT_VIDEO_OUTPUT, false)

    fun saveDirectVideoOutput(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DIRECT_VIDEO_OUTPUT, enabled).apply()
    }

    fun loadFpsCounter(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FPS_COUNTER, false)

    fun saveFpsCounter(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FPS_COUNTER, enabled).apply()
    }

    fun loadLowLatencyDecoder(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOW_LATENCY_DECODER, false)

    fun saveLowLatencyDecoder(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOW_LATENCY_DECODER, enabled).apply()
    }

    fun saveMediaBufferMillis(context: Context, millis: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MEDIA_BUFFER_MS, com.shilapi.xcertplay.media.MediaAudioBuffer.sanitize(millis)).apply()
    }

    /** DiPlay's own experimental echo canceller on CarPlay call audio; opt-in, applies at reconnect. */
    fun loadCallEchoCancellation(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_ECHO_CANCELLATION, false)

    fun saveCallEchoCancellation(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_ECHO_CANCELLATION, enabled).apply()
    }

    /** Experimental bass cut on CarPlay call audio; opt-in, applies at reconnect. */
    fun loadCallVoiceFilter(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CALL_VOICE_FILTER, false)

    fun saveCallVoiceFilter(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CALL_VOICE_FILTER, enabled).apply()
    }

    fun saveFps(context: Context, fps: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_FPS, AirPlayDisplaySettings.sanitizeFps(fps))
            .apply()
    }

    fun loadWidthPhysicalMm(context: Context): Int =
        AirPlayDisplaySettings.sanitizeWidthPhysicalMm(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(
                KEY_WIDTH_PHYSICAL_MM,
                com.shilapi.xcertplay.airplay.CarPlaySize.DEFAULT.widthMillimeters,
            ),
        )

    fun saveWidthPhysicalMm(context: Context, widthPhysicalMm: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_WIDTH_PHYSICAL_MM,
                AirPlayDisplaySettings.sanitizeWidthPhysicalMm(widthPhysicalMm),
            )
            .apply()
    }

    fun loadPhysicalSizeBasis(context: Context): AirPlayPhysicalSizeBasis {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PHYSICAL_SIZE_BASIS, null)
        return AirPlayPhysicalSizeBasis.entries.firstOrNull { it.name == stored }
            ?: AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    }

    fun savePhysicalSizeBasis(context: Context, basis: AirPlayPhysicalSizeBasis) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PHYSICAL_SIZE_BASIS, basis.name)
            .apply()
    }

    fun loadMaximumDetectedDisplay(context: Context): Pair<Int, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_DETECTED_WIDTH, 0) to
            prefs.getInt(KEY_MAX_DETECTED_HEIGHT, 0)
    }

    fun saveMaximumDetectedDisplay(
        context: Context,
        widthPixels: Int,
        heightPixels: Int,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_DETECTED_WIDTH, widthPixels.coerceAtLeast(0))
            .putInt(KEY_MAX_DETECTED_HEIGHT, heightPixels.coerceAtLeast(0))
            .apply()
    }

    fun loadClusterMapEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CLUSTER_MAP, false)

    fun loadAdbClusterEnabled(context: Context): Boolean = loadClusterMapEnabled(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADB_CLUSTER_ACTIVITY, false)

    fun saveAdbClusterEnabled(context: Context, enabled: Boolean) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ADB_CLUSTER_ACTIVITY, enabled)
            .putBoolean("platform21_cluster_enabled", false)
        if (enabled) edit.putBoolean(KEY_CLUSTER_MAP, true)
        edit.apply()
    }

    fun loadLegacyClusterEnabled(context: Context): Boolean = loadClusterMapEnabled(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("platform21_cluster_enabled", false)

    fun saveLegacyClusterEnabled(context: Context, enabled: Boolean) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("platform21_cluster_enabled", enabled)
        if (enabled) edit.putBoolean(KEY_CLUSTER_MAP, true).putBoolean(KEY_ADB_CLUSTER_ACTIVITY, false)
        edit.apply()
    }

    fun saveClusterMapEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CLUSTER_MAP, enabled).apply()
    }

    /** The dashboard map as a card on the centre screen while DiPlay is in the background. */
    fun loadCenterMapOverlay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CENTER_MAP_OVERLAY, false)

    /** Automatically hide the floating card when non-launcher apps are in the foreground. */
    fun loadCenterMapAutoHide(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CENTER_MAP_AUTO_HIDE, true)

    fun saveCenterMapAutoHide(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CENTER_MAP_AUTO_HIDE, enabled).apply()
    }

    /** Other launchers may show the live dashboard map in their own screen (MapEmbedService). */
    fun loadLauncherMapSharing(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LAUNCHER_MAP_SHARING, false)

    fun saveLauncherMapSharing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAUNCHER_MAP_SHARING, enabled).apply()
    }

    /** Observe consent changes for already attached launcher maps; call the returned function to unregister. */
    internal fun observeLauncherMapSharing(context: Context, changed: (Boolean) -> Unit): () -> Unit {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_LAUNCHER_MAP_SHARING) changed(loadLauncherMapSharing(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun saveCenterMapOverlay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CENTER_MAP_OVERLAY, enabled).apply()
    }

    /** Whether to renegotiate resolution when entering/exiting freeform floating windows or launcher PiP. */
    fun loadAdaptPipResolution(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ADAPT_PIP_RESOLUTION, false)

    fun saveAdaptPipResolution(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ADAPT_PIP_RESOLUTION, enabled).apply()
    }

    fun loadClusterContent(context: Context): CarPlayClusterDisplay.Content =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CLUSTER_CONTENT, null)
            ?.let { name -> CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == name } }
            ?: if (AdbClusterRouter.enabled(context) && !loadLegacyClusterEnabled(context)) CarPlayClusterDisplay.Content.INSTRUMENTS else CarPlayClusterDisplay.Content.MAP

    fun saveClusterContent(context: Context, content: CarPlayClusterDisplay.Content) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CLUSTER_CONTENT, content.name).apply()
        overlaySettingsListener?.invoke()
    }

    /** Fingers for the swipe-down that opens settings; some head units reserve three. */
    fun loadSettingsGestureFingers(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_SETTINGS_GESTURE_FINGERS, 3).let { if (it == 0) 0 else it.coerceIn(2, 4) }

    fun saveSettingsGestureFingers(context: Context, fingers: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SETTINGS_GESTURE_FINGERS, if (fingers == 0) 0 else fingers.coerceIn(2, 4)).apply()
    }

    /** When true, the CarPlay swipe-down opens the home settings page instead of the in-session overlay. */
    fun loadSwipeOpensFullSettings(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SWIPE_OPENS_FULL_SETTINGS, false)

    fun saveSwipeOpensFullSettings(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SWIPE_OPENS_FULL_SETTINGS, enabled).apply()
    }

    /** When true, tapping the DiPlay launcher icon while CarPlay runs returns to CarPlay. */
    fun loadLauncherReturnsToCarPlay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LAUNCHER_RETURNS_TO_CARPLAY, true)

    fun saveLauncherReturnsToCarPlay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_LAUNCHER_RETURNS_TO_CARPLAY, enabled).apply()
    }

    fun loadCenterMapFollowsDashboard(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CENTER_MAP_FOLLOWS_DASHBOARD, true)

    fun saveCenterMapFollowsDashboard(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CENTER_MAP_FOLLOWS_DASHBOARD, enabled).apply()
    }

    fun loadClusterTurnCardOverlaySizePercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT, ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT),
                ClusterTurnCardOverlay.sizePercents,
            )
        }
        return when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE, null)) {
            "SMALL" -> 40
            "LARGE" -> 70
            else -> ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
        }
    }

    fun saveClusterTurnCardOverlaySizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.sizePercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_TURN_CARD_OPACITY, ClusterTurnCardOverlay.DEFAULT_OPACITY_PERCENT)
            .coerceIn(20, 100)

    /**
     * Full-screen marker on the same 1 % grid as the small-window one. The old 10 % steps migrate
     * around the centre the sliders show (50 / 45), so an untouched marker reads as the default.
     */
    fun loadClusterMarkerXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_MARKER_X_PERCENT)) {
            return ClusterTurnCardOverlay.snap(prefs.getInt(KEY_CLUSTER_MARKER_X_PERCENT, 50), CarPlayClusterDisplay.markerXPercents)
        }
        val step = prefs.getInt(KEY_CLUSTER_MARKER_X, 0)
        return ClusterTurnCardOverlay.snap(
            50 + step * CarPlayClusterDisplay.MARKER_STEP_PERCENT,
            CarPlayClusterDisplay.markerXPercents,
        )
    }

    fun saveClusterMarkerXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerXPercents)).apply()
    }

    fun loadClusterMarkerYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_MARKER_Y_PERCENT)) {
            return ClusterTurnCardOverlay.snap(prefs.getInt(KEY_CLUSTER_MARKER_Y_PERCENT, 45), CarPlayClusterDisplay.markerYPercents)
        }
        val step = prefs.getInt(KEY_CLUSTER_MARKER_Y, 0)
        return ClusterTurnCardOverlay.snap(
            45 + step * CarPlayClusterDisplay.MARKER_STEP_PERCENT,
            CarPlayClusterDisplay.markerYPercents,
        )
    }

    fun saveClusterMarkerYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerYPercents)).apply()
    }

    /**
     * Right of centre by default: the small navi window sits on the right half of the panel.
     * The old step values migrate onto the 1 % grid.
     */
    fun loadClusterSmallWindowMarkerXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT, 80),
                CarPlayClusterDisplay.markerXPercents,
            )
        }
        val legacyStep = prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X, 3)
        val legacyPercent = (Math.round((49.5 + legacyStep * 10) / 5) * 5).toInt()
        return ClusterTurnCardOverlay.snap(legacyPercent, CarPlayClusterDisplay.markerXPercents)
    }

    fun saveClusterSmallWindowMarkerXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerXPercents)).apply()
    }

    fun loadClusterSmallWindowMarkerYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT, 45),
                CarPlayClusterDisplay.markerYPercents,
            )
        }
        val legacyStep = prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y, 0)
        val legacyPercent = (Math.round((45.5 + legacyStep * 10) / 5) * 5).toInt()
        return ClusterTurnCardOverlay.snap(legacyPercent, CarPlayClusterDisplay.markerYPercents)
    }

    fun saveClusterSmallWindowMarkerYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerYPercents)).apply()
    }


    /** Small-window card theme: 0 follow the full-screen card theme, 1 always day, 2 always night. */
    fun loadClusterSmallWindowCardTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_THEME, 0).coerceIn(0, 2)

    fun saveClusterSmallWindowCardTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_THEME, theme.coerceIn(0, 2)).apply()
        overlaySettingsListener?.invoke()
    }

    /**
     * The custom turn card keeps a second rect for the small window: x/y/size, panel percents. Its
     * defaults sit right of centre and smaller, where the small navi window is. Until the driver sets
     * the small-window card, a full-screen card value the driver chose still applies, as it did before.
     */
    fun loadClusterSmallWindowCardSizePercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val size = when {
            prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE) -> prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE, 40)
            prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT) || prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE) ->
                loadClusterTurnCardOverlaySizePercent(context)
            else -> 40
        }
        return ClusterTurnCardOverlay.snap(size, ClusterTurnCardOverlay.sizePercents)
    }

    fun saveClusterSmallWindowCardSizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.sizePercents)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterSmallWindowCardXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val x = when {
            prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_X) -> prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_X, 80)
            prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_X) || prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION) ->
                loadClusterTurnCardOverlayXPercent(context)
            else -> 80
        }
        return ClusterTurnCardOverlay.snap(x, ClusterTurnCardOverlay.smallWindowXPercents)
    }

    fun saveClusterSmallWindowCardXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_X, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.smallWindowXPercents)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterSmallWindowCardYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val y = when {
            prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_Y) -> prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_Y, 25)
            prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_Y) -> loadClusterTurnCardOverlayYPercent(context)
            else -> 25
        }
        return ClusterTurnCardOverlay.snap(y, ClusterTurnCardOverlay.smallWindowYPercents)
    }

    fun saveClusterSmallWindowCardYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_Y, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.smallWindowYPercents)).apply()
        overlaySettingsListener?.invoke()
    }

    /** The small-window card falls back to the shared opacity until it gets its own value. */
    fun loadClusterSmallWindowCardOpacityPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY)) {
            return prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY, 85).coerceIn(20, 100)
        }
        return loadClusterTurnCardOpacityPercent(context)
    }

    fun saveClusterSmallWindowCardOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    /** Turn-card glass theme: 0 follow the head unit, 1 always day, 2 always night. */
    fun loadClusterTurnCardTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_TURN_CARD_THEME, 0).coerceIn(0, 2)

    fun saveClusterTurnCardTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_TURN_CARD_THEME, theme.coerceIn(0, 2)).apply()
        overlaySettingsListener?.invoke()
    }

    fun saveClusterTurnCardOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_TURN_CARD_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_X)) {
            prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_X, ClusterTurnCardOverlay.DEFAULT_X_PERCENT)
        } else when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_POSITION, null)) {
            "LEFT" -> 20
            "CENTER" -> 50
            else -> ClusterTurnCardOverlay.DEFAULT_X_PERCENT
        }
        return ClusterTurnCardOverlay.snap(raw, ClusterTurnCardOverlay.xPercents)
    }

    fun saveClusterTurnCardOverlayXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_X,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.xPercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardOverlayYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_Y)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_Y, ClusterTurnCardOverlay.DEFAULT_Y_PERCENT),
                ClusterTurnCardOverlay.yPercents,
            )
        }
        return ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    }

    fun saveClusterTurnCardOverlayYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_Y,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.yPercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterMapScalePercent(context: Context): Int = CarPlayClusterDisplay.STREAM_SCALE_PERCENT.let { default ->
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MAP_SCALE, default)
            .takeIf { it in CarPlayClusterDisplay.scalePresets } ?: default
    }

    fun saveClusterMapScalePercent(context: Context, percent: Int) {
        if (percent !in CarPlayClusterDisplay.scalePresets) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CLUSTER_MAP_SCALE, percent).apply()
    }

    fun loadClusterMarkerHorizontalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_X, 0)
            .coerceIn(CarPlayClusterDisplay.horizontalSteps)

    fun saveClusterMarkerHorizontalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X, step.coerceIn(CarPlayClusterDisplay.horizontalSteps)).apply()
    }

    fun loadClusterMarkerVerticalStep(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CLUSTER_MARKER_Y, 0)
            .coerceIn(CarPlayClusterDisplay.verticalSteps)

    fun saveClusterMarkerVerticalStep(context: Context, step: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y, step.coerceIn(CarPlayClusterDisplay.verticalSteps)).apply()
    }

    /**
     * Small-window navi mode: 0 off, 1 always use the small-window positions, 2 follow the
     * cluster automatically (experimental, needs Usage Access, applies on the next connection).
     */
    fun loadClusterSmallWindowMode(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_MODE)) {
            return prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MODE, 0).coerceIn(0, 2)
        }
        return if (prefs.getBoolean(KEY_CLUSTER_SMALL_WINDOW_MARKER, false)) 1 else 0
    }

    fun saveClusterSmallWindowMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MODE, mode.coerceIn(0, 2)).apply()
    }

    /** Keep the cn.3–cn.6 boolean API working for callers that have not migrated. */
    fun loadClusterSmallWindowMarker(context: Context): Boolean =
        loadClusterSmallWindowMode(context) != 0

    /**
     * Full-screen marker on the same 1 % grid as the small-window one; the old 10 % steps
     * (around x 49.5 / y 45.5) migrate onto it.
     */
    fun loadClusterMarkerXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_MARKER_X_PERCENT)) {
            return ClusterTurnCardOverlay.snap(prefs.getInt(KEY_CLUSTER_MARKER_X_PERCENT, 50), CarPlayClusterDisplay.markerXPercents)
        }
        val step = prefs.getInt(KEY_CLUSTER_MARKER_X, 0)
        return ClusterTurnCardOverlay.snap(
            Math.round(49.5 + step * CarPlayClusterDisplay.MARKER_STEP_PERCENT).toInt(),
            CarPlayClusterDisplay.markerXPercents,
        )
    }

    fun saveClusterMarkerXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_X_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerXPercents)).apply()
    }

    fun loadClusterMarkerYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_MARKER_Y_PERCENT)) {
            return ClusterTurnCardOverlay.snap(prefs.getInt(KEY_CLUSTER_MARKER_Y_PERCENT, 45), CarPlayClusterDisplay.markerYPercents)
        }
        val step = prefs.getInt(KEY_CLUSTER_MARKER_Y, 0)
        return ClusterTurnCardOverlay.snap(
            Math.round(45.5 + step * CarPlayClusterDisplay.MARKER_STEP_PERCENT).toInt(),
            CarPlayClusterDisplay.markerYPercents,
        )
    }

    fun saveClusterMarkerYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_MARKER_Y_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerYPercents)).apply()
    }

    /** Small-window card theme: 0 follow the full-screen card theme, 1 always day, 2 always night. */
    fun loadClusterSmallWindowCardTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_THEME, 0).coerceIn(0, 2)

    fun saveClusterSmallWindowCardTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_THEME, theme.coerceIn(0, 2)).apply()
        overlaySettingsListener?.invoke()
    }

    /**
     * Right of centre by default: the small navi window sits on the right half of the panel.
     * Placement is a 5 % grid; the cn.3/cn.4 step values migrate onto it.
     */
    fun loadClusterSmallWindowMarkerXPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT, 80),
                CarPlayClusterDisplay.markerXPercents,
            )
        }
        val legacyStep = prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X, 3)
        val legacyPercent = (Math.round((49.5 + legacyStep * 10) / 5) * 5).toInt()
        return ClusterTurnCardOverlay.snap(legacyPercent, CarPlayClusterDisplay.markerXPercents)
    }

    fun saveClusterSmallWindowMarkerXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_X_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerXPercents)).apply()
    }

    fun loadClusterSmallWindowMarkerYPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT, 45),
                CarPlayClusterDisplay.markerYPercents,
            )
        }
        val legacyStep = prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y, 0)
        val legacyPercent = (Math.round((45.5 + legacyStep * 10) / 5) * 5).toInt()
        return ClusterTurnCardOverlay.snap(legacyPercent, CarPlayClusterDisplay.markerYPercents)
    }

    fun saveClusterSmallWindowMarkerYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_MARKER_Y_PERCENT,
                ClusterTurnCardOverlay.snap(percent, CarPlayClusterDisplay.markerYPercents)).apply()
    }

    /** Turn-card glass theme: 0 follow the head unit, 1 always day, 2 always night. */
    fun loadClusterTurnCardTheme(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_TURN_CARD_THEME, 0).coerceIn(0, 2)

    fun saveClusterTurnCardTheme(context: Context, theme: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_TURN_CARD_THEME, theme.coerceIn(0, 2)).apply()
        overlaySettingsListener?.invoke()
    }

    /** The custom turn card also keeps a second rect for the small window: x/y/size, panel percents. */
    fun loadClusterSmallWindowCardXPercent(context: Context): Int = ClusterTurnCardOverlay.snap(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_X, 80),
        ClusterTurnCardOverlay.smallWindowXPercents,
    )

    fun saveClusterSmallWindowCardXPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_X, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.smallWindowXPercents)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterSmallWindowCardYPercent(context: Context): Int = ClusterTurnCardOverlay.snap(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_Y, 25),
        ClusterTurnCardOverlay.smallWindowYPercents,
    )

    fun saveClusterSmallWindowCardYPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_Y, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.smallWindowYPercents)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterSmallWindowCardSizePercent(context: Context): Int = ClusterTurnCardOverlay.snap(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE, 40),
        ClusterTurnCardOverlay.sizePercents,
    )

    fun saveClusterSmallWindowCardSizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_SIZE, ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.sizePercents)).apply()
        overlaySettingsListener?.invoke()
    }

    // Cluster mapping has its own key; never reuse the main display mapping at the same resolution.
    fun loadClusterSafeAreaRect(context: Context): SafeAreaRect? =
        SafeAreaCodec.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cluster_safe_area_1920x720", null))?.clampTo(1920, 720)

    fun saveClusterSafeAreaRect(context: Context, rect: SafeAreaRect) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cluster_safe_area_1920x720", SafeAreaCodec.encode(rect.clampTo(1920, 720))).apply()
    }

    fun clearClusterSafeAreaRect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("cluster_safe_area_1920x720").apply()
    }

    fun loadRightHandDrive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RIGHT_HAND_DRIVE, false)

    fun saveRightHandDrive(context: Context, rightHandDrive: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_RIGHT_HAND_DRIVE, rightHandDrive)
            .apply()
    }

    fun loadHideTopBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_TOP_BAR, true)

    fun saveHideTopBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_TOP_BAR, hide)
            .apply()
    }

    fun loadHideBottomBar(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_BOTTOM_BAR, true)

    fun saveHideBottomBar(context: Context, hide: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_HIDE_BOTTOM_BAR, hide)
            .apply()
    }

    fun loadSafeAreaDrawOutside(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, true)

    fun saveSafeAreaDrawOutside(context: Context, drawOutside: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SAFE_AREA_DRAW_OUTSIDE, drawOutside)
            .apply()
    }

    fun loadSafeAreaRect(context: Context, widthPixels: Int, heightPixels: Int): SafeAreaRect? {
        require(widthPixels > 0 && heightPixels > 0) { "Activity dimensions must be positive" }
        return SafeAreaCodec.decode(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(safeAreaKey(widthPixels, heightPixels), null),
        )
    }

    fun saveSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        rect: SafeAreaRect,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(
                safeAreaKey(activityWidthPixels, activityHeightPixels),
                SafeAreaCodec.encode(rect.clampTo(activityWidthPixels, activityHeightPixels)),
            )
        if (commit) editor.commit() else editor.apply()
    }

    fun clearSafeAreaRect(
        context: Context,
        activityWidthPixels: Int,
        activityHeightPixels: Int,
        commit: Boolean = false,
    ) {
        require(activityWidthPixels > 0 && activityHeightPixels > 0) {
            "Activity dimensions must be positive"
        }
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(safeAreaKey(activityWidthPixels, activityHeightPixels))
        if (commit) editor.commit() else editor.apply()
    }

    fun loadCustomAirPlayIconFile(context: Context): File? =
        File(context.filesDir, CUSTOM_ICON_FILE).takeIf { it.isFile }

    fun saveCustomAirPlayIcon(context: Context, encodedImage: ByteArray) {
        require(encodedImage.isNotEmpty()) { "AirPlay icon data must not be empty" }
        File(context.filesDir, CUSTOM_ICON_FILE).outputStream().use { output ->
            output.write(encodedImage)
        }
    }

    fun clearCustomAirPlayIcon(context: Context) {
        File(context.filesDir, CUSTOM_ICON_FILE).delete()
    }

    fun loadIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val privateKey = prefs.getString(KEY_IDENT_PRIVATE, null)
        val publicKey = prefs.getString(KEY_IDENT_PUBLIC, null)
        val pairingId = prefs.getString(KEY_PAIRING_ID, null)
        if (privateKey != null && publicKey != null && pairingId != null) {
            return AirPlayIdentity(privateKey.decodeHex(), publicKey.decodeHex(), pairingId)
        }
        return AirPlayIdentity.generate().also { identity ->
            prefs.edit()
                .putString(KEY_IDENT_PRIVATE, identity.privateKey.toHex())
                .putString(KEY_IDENT_PUBLIC, identity.publicKey.toHex())
                .putString(KEY_PAIRING_ID, identity.pairingId)
                .apply()
        }
    }

    fun loadPairings(context: Context, onSave: (String, ByteArray) -> Unit): PairingStore {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val store = PairingStore(onSave)
        for (identifier in prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty()) {
            prefs.getString("pairing.$identifier", null)?.let { store.save(identifier, it.decodeHex()) }
        }
        return store
    }

    fun savePairing(context: Context, identifier: String, longTermPublicKey: ByteArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val identifiers = prefs.getStringSet(KEY_PAIRING_IDS, emptySet()).orEmpty().toMutableSet()
        identifiers.add(identifier)
        prefs.edit()
            .putString("pairing.$identifier", longTermPublicKey.toHex())
            .putStringSet(KEY_PAIRING_IDS, identifiers)
            .apply()
    }

    fun loadLockdownRecord(context: Context): LockdownPairRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hostId = prefs.getString(KEY_LOCKDOWN_HOST_ID, null) ?: return null
        val systemBuid = prefs.getString(KEY_LOCKDOWN_SYSTEM_BUID, null) ?: return null
        val wifiMac = prefs.getString(KEY_LOCKDOWN_WIFI_MAC, null) ?: return null
        val devicePublic = prefs.getString(KEY_LOCKDOWN_DEVICE_PUBLIC, null) ?: return null
        val deviceCert = prefs.getString(KEY_LOCKDOWN_DEVICE_CERT, null) ?: return null
        val hostPrivate = prefs.getString(KEY_LOCKDOWN_HOST_PRIVATE, null) ?: return null
        val hostCert = prefs.getString(KEY_LOCKDOWN_HOST_CERT, null) ?: return null
        val rootPrivate = prefs.getString(KEY_LOCKDOWN_ROOT_PRIVATE, null) ?: return null
        val rootCert = prefs.getString(KEY_LOCKDOWN_ROOT_CERT, null) ?: return null
        return try {
            LockdownPairRecord.restore(
                hostId = hostId,
                systemBuid = systemBuid,
                wifiMacAddress = wifiMac,
                devicePublicKeyPem = devicePublic.decodeHex(),
                deviceCertificatePem = deviceCert.decodeHex(),
                hostPrivateKeyPem = hostPrivate.decodeHex(),
                hostCertificatePem = hostCert.decodeHex(),
                rootPrivateKeyPem = rootPrivate.decodeHex(),
                rootCertificatePem = rootCert.decodeHex(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun saveLockdownRecord(context: Context, record: LockdownPairRecord) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LOCKDOWN_HOST_ID, record.hostId)
            .putString(KEY_LOCKDOWN_SYSTEM_BUID, record.systemBuid)
            .putString(KEY_LOCKDOWN_WIFI_MAC, record.wifiMacAddress)
            .putString(KEY_LOCKDOWN_DEVICE_PUBLIC, record.devicePublicKeyPem.toHex())
            .putString(KEY_LOCKDOWN_DEVICE_CERT, record.deviceCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_HOST_PRIVATE, record.hostPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_HOST_CERT, record.hostCertificatePem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_PRIVATE, record.rootPrivateKeyPem.toHex())
            .putString(KEY_LOCKDOWN_ROOT_CERT, record.rootCertificatePem.toHex())
            .apply()
    }

    fun clearLockdownRecord(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_LOCKDOWN_HOST_ID)
            .remove(KEY_LOCKDOWN_SYSTEM_BUID)
            .remove(KEY_LOCKDOWN_WIFI_MAC)
            .remove(KEY_LOCKDOWN_DEVICE_PUBLIC)
            .remove(KEY_LOCKDOWN_DEVICE_CERT)
            .remove(KEY_LOCKDOWN_HOST_PRIVATE)
            .remove(KEY_LOCKDOWN_HOST_CERT)
            .remove(KEY_LOCKDOWN_ROOT_PRIVATE)
            .remove(KEY_LOCKDOWN_ROOT_CERT)
            .apply()
    }

    /** Optional: park the car Bluetooth while CarPlay runs so calls ring on CarPlay only. */
    fun loadBtSuspendDuringCarplay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BT_SUSPEND_DURING_CARPLAY, false)

    fun saveBtSuspendDuringCarplay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BT_SUSPEND_DURING_CARPLAY, enabled).apply()
    }

    fun loadBtSuspendDelaySeconds(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_BT_SUSPEND_DELAY, 10).let { if (it in listOf(5, 10, 15, 30)) it else 10 }

    fun saveBtSuspendDelaySeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BT_SUSPEND_DELAY, if (seconds in listOf(5, 10, 15, 30)) seconds else 10).apply()
    }

    fun loadUpdateChannel(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_UPDATE_CHANNEL, 1).coerceIn(0, 3)

    fun saveUpdateChannel(context: Context, channel: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_UPDATE_CHANNEL, channel.coerceIn(0, 3)).apply()
    }

    fun loadClusterTurnCardOverlaySizePercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT)) {
            return ClusterTurnCardOverlay.snap(
                prefs.getInt(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT, ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT),
                ClusterTurnCardOverlay.sizePercents,
            )
        }
        // Legacy Small/Medium/Large presets map onto the slider.
        return when (prefs.getString(KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE, null)) {
            "SMALL" -> 40
            "LARGE" -> 70
            else -> ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
        }
    }

    fun saveClusterTurnCardOverlaySizePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(
                KEY_CLUSTER_TURN_CARD_OVERLAY_SIZE_PERCENT,
                ClusterTurnCardOverlay.snap(percent, ClusterTurnCardOverlay.sizePercents),
            ).apply()
        overlaySettingsListener?.invoke()
    }

    /** Card opacity 20..100 %; lower shows more of the map behind the glass. */
    fun loadClusterTurnCardOpacityPercent(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CLUSTER_TURN_CARD_OPACITY, 85).coerceIn(20, 100)

    /** The small-window card falls back to the shared opacity until it gets its own value. */
    fun loadClusterSmallWindowCardOpacityPercent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY)) {
            return prefs.getInt(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY, 85).coerceIn(20, 100)
        }
        return loadClusterTurnCardOpacityPercent(context)
    }

    fun saveClusterTurnCardOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_TURN_CARD_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    fun saveClusterSmallWindowCardOpacityPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLUSTER_SMALL_WINDOW_CARD_OPACITY, percent.coerceIn(20, 100)).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardShowLanes(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_TURN_CARD_SHOW_LANES, true)

    fun saveClusterTurnCardShowLanes(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_TURN_CARD_SHOW_LANES, enabled).apply()
        overlaySettingsListener?.invoke()
    }

    fun loadClusterTurnCardShowArrival(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUSTER_TURN_CARD_SHOW_ARRIVAL, true)

    fun saveClusterTurnCardShowArrival(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CLUSTER_TURN_CARD_SHOW_ARRIVAL, enabled).apply()
        overlaySettingsListener?.invoke()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "hex string must have even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun safeAreaKey(widthPixels: Int, heightPixels: Int): String =
        "$SAFE_AREA_KEY_PREFIX${widthPixels}x$heightPixels"
}
