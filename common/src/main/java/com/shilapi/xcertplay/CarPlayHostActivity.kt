package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.SafeAreaRect
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayTouchMapper
import com.shilapi.xcertplay.media.CarPlayVideoLayout
import com.shilapi.xcertplay.network.CarPlayVpnService
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.network.WirelessStartupPolicy
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.isManualHotspotChannelCompatible
import com.shilapi.xcertplay.settings.ConnectionSettingsSection
import com.shilapi.xcertplay.settings.DisplaySettingsSection
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.settings.SettingsWidgets
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.UsbDeviceId
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaRects: MutableMap<DisplaySize, SafeAreaRect?>,
        val customIconBytes: ByteArray?,
    )

    private var connectionPanel: View? = null
    private var wifiRecoveryButton: View? = null
    private var reconnectAttempts = 0
    private val siriKey = WheelSiriKey()
    private val siriKeyPresses = WheelKeyPresses()
    private val legacySiriPresses = mutableSetOf<Triple<Int, Int, Int>>()
    private val startupRetryBudget = WirelessStartupRetryBudget()
    private var startupRetryStopped = false
    private var startupRetryButton: View? = null
    private var startupFailureGeneration = -1
    private lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "DiPlay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "DIPLAY-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", ""),
            firmwareVersion = "0.1.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
            vehicleStatusEnabled = com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this),
            chargingConnectors = com.shilapi.xcertplay.hud.BydOutputSettings.chargingConnectors(this),
            vehicleSpeedEnabled = locationReportingEnabled && com.shilapi.xcertplay.hud.BydOutputSettings.wheelSpeedToIphoneActive(this),
        ),
        label = "DiPlay",
        hostName = "diplay-" + DiPlayBootstrap.deviceId(airPlayIdentity).replace(":", "").lowercase(),
        hostMac = DiPlayBootstrap.deviceId(airPlayIdentity).split(":").map { it.toInt(16).toByte() }.toByteArray(),
        wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(this),
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        existingWifiSsid = existingWifiSsid,
        existingWifiPassphrase = existingWifiPassphrase,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus(getString(R.string.vpn_consent_was_denied))
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent(), ::cropSelectedImage)
    private val imageDocumentPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument(), ::cropSelectedImage)
    private fun cropSelectedImage(uri: Uri?) {
        if (uri == null) {
            externalActivityInProgress = false
            return
        }
        imageCrop.launch(
            Intent(this, ImageCropActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }

    // The full-window viewport stays independent of the letterboxed SurfaceView dimensions.
    private var videoView: View? = null
    private var fallbackVideoView: SurfaceView? = null
    private var fallbackVideoBounds: CarPlaySurfaceBounds? = null
    // Smooth video (a setting): SurfaceView output with frames released at the iPhone's frame time.
    private var smoothVideo = false
    // Sinks whose sessions are being torn down; their decoders may still render to the current surface
    // until they have released their codecs, so a destroyed surface is detached from them too. A restart
    // and a shutdown can overlap, so this is a set.
    private val retiringSinks = java.util.concurrent.CopyOnWriteArraySet<AndroidMediaSink>()
    internal var sinkReleaseWaitMillis = SINK_RELEASE_WAIT_MILLIS
    private var videoSurfaceProbe: ViewTreeObserver.OnPreDrawListener? = null
    private var pictureBinding: CarPlayPicture.Binding? = null
    private var picturePanel: View? = null
    private var picturePanelGeneration = 0
    private var gestureOverlay: View? = null
    private var settingsMenu: View? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: Switch? = null
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var stageHintView: TextView? = null
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var existingWifiFields: View? = null
    private var existingWifiErrorView: TextView? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private val videoSurfaceOwner = CarPlayVideoSurfaceOwner<Surface>(
        detach = { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
        },
        release = { it.release() },
    )
    private val currentSurface: Surface? get() = videoSurfaceOwner.current
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var clusterPresentation: ClusterMapPresentation? = null
    private var clusterSurface: Surface? = null
    private var clusterMonitor: DiLink51ClusterMonitor? = null
    private var detectedCluster = ClusterActivityState.Snapshot(null, false)
    // Keep one surface per layer alive, including while its map card is hidden.
    private val clusterLayers = mutableMapOf<Boolean, ClusterMapPresentation>()
    private var clusterTurnGuidance: com.shilapi.xcertplay.hud.ClusterTurnGuidance? = null
    private val clusterTurnOverlayListener: (com.shilapi.xcertplay.hud.ClusterTurnGuidance?) -> Unit = { guidance ->
        runOnUiThread {
            clusterTurnGuidance = guidance
            applyClusterTurnOverlay()
        }
    }
    // Copies of stream 111 outside the dashboard (centre card, launcher maps) each get their own decoder.
    private val mirrorSink: (String, Surface?) -> Unit = { key, surface -> sink?.setMirrorSurface(SCREEN_TYPE_ALT, key, surface) }
    private val mirrorsChanged: () -> Unit = {
        updateClusterMapShown()
        if (MapMirrors.launcherShowsMap) CenterMapOverlay.hide()
    }
    // With Usage Access the card shows only over a home screen; null = not known (no monitor).
    private var homeMonitor: HomeScreenMonitor? = null
    private var homeScreenVisible: Boolean? = null
    private var isActivityStarted = false
    private val hideIdleCenterMap = Runnable {
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) CenterMapOverlay.hide()
    }
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var sessionDisplay: CarPlaySessionDisplay? = null
    private var touchOutsideContent = false
    private var displayScalePercent = 100
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    private var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    private var navigationStreamType = 14
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var carPlayDock = CarPlayDock.AUTOMATIC
    /** The view areas the next session declares (see createAirPlayConfig). */
    private var pendingViewAreas: CarPlayViewAreas? = null
    /** The dock the running session declared; null before the first connection. */
    private var sessionDock: CarPlayDock? = null
    private var hideTopBar = true
    private var hideBottomBar = true
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.LOCAL
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var overlayMenuCategoryTitle: String? = null
    private var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var appNight = true
    private var hostAppearanceResumed = false
    private var paintWaitingScreen: () -> Unit = {}
    private var carPlayNightMode = CarPlayNightMode.SYSTEM
    private var nightSchedule = CarPlayNightSchedule()
    private var ambientLightThreshold = AmbientLightThreshold()
    private var ambientDelaySeconds = 2
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val nightModeController by lazy {
        CarPlayNightModeController(
            light = AndroidAmbientLight(this),
            scheduler = MainThreadNightModeScheduler(),
            initialNight = darkMode,
            onNightChanged = { night ->
                darkMode = night
                if (hostAppearanceResumed) AppAppearanceRuntime.publishHost(this, night)
                refreshAppAppearance()
                paintWaitingScreen()
                applyClusterTurnOverlay()
                appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
                logThemeState(nightModeDiagnosticSource, resources.configuration)
                syncAirPlayDarkMode(nightModeDiagnosticSource)
            },
        )
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var lastConfiguration: Configuration? = null
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()
    private var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    // The marker safe area is fixed when stream 111 is requested. Compare it with the
    // observed instrument mode so a late Usage Access result or a live mode change is applied.
    private var requestedSmallWindowForStream: Boolean? = null
    /** Wheel-menu navi mode from ADB (DiLink 5). Null until a successful read. */
    @Volatile private var adbNaviMode: com.shilapi.xcertplay.hud.BydClusterNaviMode? = null
    private var sessionLog: SessionLogFile? = null
    private var gestureFingerCount = 3
    private var swipeOpensFullSettings = false
    private var settingsGestureHint: TextView? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var recoveryPendingAfterMenu = false
    private var failurePendingAfterMenu: CarPlayStatus.Failed? = null
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sidePanel: LinearLayout? = null
    private var sidePanelBattery: TextView? = null
    private var sidePanelShown = false
    private val sidePanelTick = object : Runnable {
        override fun run() {
            refreshSidePanel()
            if (sidePanelShown) mainHandler.postDelayed(this, SIDE_PANEL_REFRESH_MILLIS)
        }
    }
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<LogEntry>()
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private val refreshTurnOverlay = object : Runnable {
        override fun run() {
            com.shilapi.xcertplay.hud.BydNavigationOutputs.refreshTurnOverlay()
            mainHandler.postDelayed(this, 1_000L)
        }
    }
    // Some head units (e.g. BYD DiLink) update resources.configuration for day/night
    // without delivering onConfigurationChanged, so poll while the activity is visible.
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    videoSurfaceOwner.replace(it, releaseOnDetach = true)
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            updateVideoLayout(width, height)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let(videoSurfaceOwner::clear)
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    private val fallbackSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (isDestroyed || holder !== fallbackVideoView?.holder) return
            val surface = holder.surface
            videoSurfaceOwner.replace(surface, releaseOnDetach = false)
            appendLog("SurfaceView video surface created valid=${surface.isValid}")
            attachSurface(surface)
            // A still CarPlay screen sends no frames, so ask for one instead of showing the parked gap.
            if (smoothVideo) sink?.refreshPicture(SCREEN_TYPE_MAIN)
            videoView?.let { updateVideoLayout(it.width, it.height) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (holder !== fallbackVideoView?.holder) return
            // Holder dimensions describe the fitted video, not the host window/CarPlay canvas.
            videoView?.let { updateVideoLayout(it.width, it.height) }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val surface = holder.surface
            // The SurfaceHolder contract: nothing may render to the surface once this returns. Every decoder
            // is asked at once, then waited for against one deadline; each confirms once it has moved,
            // parked or released its codec, and closing decoders count once their workers exit. With
            // smooth video the live session's main decoder parks off screen and keeps its state.
            val live = sink
            val detaches = (listOfNotNull(live) + retiringSinks).distinct()
                .map { owner -> owner.beginSurfaceDetach(surface, parkMain = smoothVideo && owner === live) }
            val confirmed = detaches.map { it.await() }.all { it }
            videoSurfaceOwner.clear(surface)
            appendLog("SurfaceView video surface destroyed detachConfirmed=$confirmed")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavigationWidgetUpdater.attach(applicationContext)
        CarPlayCallKeys.install(applicationContext)
        CenterMapOverlay.requestShow = ::showCenterMap
        MapMirrors.sink = mirrorSink
        MapMirrors.onChanged = mirrorsChanged
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (isIphoneUsbAttachment(intent)) {
            AirPlayPersistence.saveWirelessEnabled(this, false)
        }
        if (runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.isFailure) {
            startActivity(Intent(this, DiPlayActivity::class.java))
            finish(); return
        }
        WheelKeyService.restoreIfNeeded(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.registerDisplayListener(clusterDisplayListener, mainHandler)
        initializeSessionLog()
        lastConfiguration = Configuration(resources.configuration)
        darkMode = savedInstanceState?.getBoolean("carplay_night_active")
            ?: nightModeOrNull(resources.configuration.uiMode) ?: false
        appNight = resolveAppNightNow(darkMode)
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        setContentView(buildContentView())
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (picturePanel != null) {
                        closePicturePanel()
                    } else if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        carPlayDock = CarPlayDock.load(this)
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        ambientLightThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        ambientDelaySeconds = AirPlayPersistence.loadAmbientDelaySeconds(this)
        nightSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: false,
            ambientLightThreshold,
            ambientDelaySeconds,
            nightSchedule,
        )
        gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        swipeOpensFullSettings = AirPlayPersistence.loadSwipeOpensFullSettings(this)
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationPermissionAvailable = hasFineLocationPermission()
        loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    /**
     * Connection settings owned by the settings screen ([DiPlayActivity]). It saves them in this
     * process while this screen keeps running, so they are re-read on every resume instead of
     * relying on the snapshot [onCreate] took: otherwise the next handshake would keep using the
     * mode, hotspot and MFI settings that were current when this screen was first opened.
     */
    private fun loadConnectionSettings() {
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        if (awaitingVpnConsent) return
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            vpnReady = false
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isIphoneUsbAttachment(intent)) {
            if (wirelessEnabled) {
                if (menuOpen) cancelSettingsEdits()
                AirPlayPersistence.saveWirelessEnabled(this, false)
                wirelessEnabled = false
                wirelessPermissionsReady = true
                if (controller != null) {
                    restartCarPlay("switching to USB")
                }
                requestStartupPrerequisites()
            } else if (controller == null) {
                requestStartupPrerequisites()
            }
        }
    }

    private fun isIphoneUsbAttachment(intent: Intent): Boolean {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return false
        val device = androidx.core.content.IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        return device?.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.post(pollConfiguration)
        CenterMapOverlay.onDiPlayScreenShown()
        homeMonitor?.stop()
        homeScreenVisible = null
    }

    override fun onResume() {
        super.onResume()
        hostAppearanceResumed = true
        val savedNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        val savedThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        val savedDelay = AirPlayPersistence.loadAmbientDelaySeconds(this)
        val savedSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: false
        if (savedNightMode != carPlayNightMode || savedThreshold != ambientLightThreshold ||
            savedDelay != ambientDelaySeconds || savedSchedule != nightSchedule) {
            carPlayNightMode = savedNightMode
            ambientLightThreshold = savedThreshold
            ambientDelaySeconds = savedDelay
            nightSchedule = savedSchedule
            nightModeController.configure(carPlayNightMode, systemNight, ambientLightThreshold, ambientDelaySeconds,
                nightSchedule)
        }
        nightModeController.resume(systemNight)
        AppAppearanceRuntime.publishHost(this, darkMode)
        refreshAppAppearance()
        if (!menuOpen) {
            displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
            displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
            updateResolutionMenu()
        }
        if (intent.getBooleanExtra("picture_controls", false)) {
            intent.removeExtra("picture_controls")
            openPicturePanel()
        }
        val languagePreference = AppLocale.preference(this)
        if (Build.VERSION.SDK_INT < 33 && languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        // The settings screen returns here with FLAG_ACTIVITY_REORDER_TO_FRONT, so this screen is
        // resumed, not recreated: refresh what that screen can change before it is used again.
        if (!menuOpen) loadConnectionSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        // Usage Access is the fallback for small-window auto; ADB navi mode is preferred on DiLink 5.
        val followClusterUsage = DiLink51ClusterLayout.automatic(this) ||
            (AirPlayPersistence.loadClusterSmallWindowMode(this) == CLUSTER_SMALL_WINDOW_AUTO &&
                adbNaviMode == null)
        if (followClusterUsage && clusterMonitor == null) {
            clusterMonitor = DiLink51ClusterMonitor(this, ::onClusterActivityState).also { it.start() }
        } else if (!followClusterUsage) {
            clusterMonitor?.stop()
            clusterMonitor = null
        }
        val followAdbNavi = AirPlayPersistence.loadClusterSmallWindowMode(this) == CLUSTER_SMALL_WINDOW_AUTO
        if (followAdbNavi) {
            com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterNaviModeListener { mode ->
                mainHandler.post { onAdbNaviMode(mode) }
            }
        } else {
            com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterNaviModeListener(null)
            adbNaviMode = null
        }
        if (!menuOpen) {
            gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
            swipeOpensFullSettings = AirPlayPersistence.loadSwipeOpensFullSettings(this)
        }
        settingsGestureHint?.text = getString(R.string.open_diplay_settings_hint, gestureFingerCount)
        ensureClusterPresentation()
        AirPlayPersistence.overlaySettingsListener = { runOnUiThread { applyClusterTurnOverlay() } }
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(clusterTurnOverlayListener)
        mainHandler.removeCallbacks(refreshTurnOverlay)
        mainHandler.post(refreshTurnOverlay)
        var systemBarsChanged = false
        if (!menuOpen) {
            val savedHideTopBar = AirPlayPersistence.loadHideTopBar(this)
            val savedHideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
            systemBarsChanged = hideTopBar != savedHideTopBar || hideBottomBar != savedHideBottomBar
            hideTopBar = savedHideTopBar
            hideBottomBar = savedHideBottomBar
        }
        maybeStartCarPlay()
        applyFullscreenMode()
        if (systemBarsChanged) refreshDisplaySizeAfterLayout()
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }
    }


    // Experimental: the CarPlay instrument-cluster stream on the BYD cluster projection display.
    private fun effectiveClusterTheme(): DiLink51ClusterLayout.Theme =
        if (DiLink51ClusterLayout.automatic(this)) detectedCluster.theme ?: DiLink51ClusterLayout.theme(this)
        else DiLink51ClusterLayout.theme(this)

    private fun onAdbNaviMode(mode: com.shilapi.xcertplay.hud.BydClusterNaviMode?) {
        val previous = adbNaviMode
        if (mode == previous) return
        adbNaviMode = mode
        appendLog("Cluster map: ADB navi mode=${mode?.label ?: "unknown"}")
        if (AirPlayPersistence.loadClusterSmallWindowMode(this) != CLUSTER_SMALL_WINDOW_AUTO) return
        if (mode == com.shilapi.xcertplay.hud.BydClusterNaviMode.SMALL ||
            mode == com.shilapi.xcertplay.hud.BydClusterNaviMode.FULL) {
            applyClusterTurnOverlay()
            if (CarPlayBackgroundSession.active && requestedSmallWindowForStream != smallWindowActive()) {
                if (menuOpen) recoveryPendingAfterMenu = true
                else reconnectAfterLoss("Cluster small-window navi ${if (smallWindowActive()) "on" else "off"}")
            }
        }
    }

    private fun onClusterActivityState(state: ClusterActivityState.Snapshot) {
        val previous = detectedCluster
        if (state != previous) appendLog("Cluster map: detected theme=${state.theme} mapVisible=${state.mapVisible} smallWindow=${state.smallWindow}")
        detectedCluster = state
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) { dismissClusterPresentation(); return }
        ensureClusterPresentation()
        if (AirPlayPersistence.loadClusterSmallWindowMode(this) == CLUSTER_SMALL_WINDOW_AUTO &&
            adbNaviMode == null &&
            (previous.smallWindow != state.smallWindow || previous.mapVisible != state.mapVisible)) {
            // A covered map (360 camera on the turn signal, another cluster page) hides the map
            // activities momentarily; that is not a real full/small switch — do not reconnect.
            if (!state.mapVisible) {
                appendLog("Cluster map: map covered; keeping the stream at smallWindow=$requestedSmallWindowForStream")
            } else {
                appendLog("Cluster map: instrument switched to ${if (state.smallWindow) "small" else "full"} window; stream requested=$requestedSmallWindowForStream")
                if (CarPlayBackgroundSession.active && ClusterActivityState.shouldReconnectSmallWindowStream(
                        previous, state, requestedSmallWindowForStream)) {
                    reconnectAfterLoss("Cluster small-window navi ${if (state.smallWindow) "on" else "off"}")
                }
            }
        }
    }

    private fun ensureClusterPresentation() {
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) {
            dismissClusterPresentation()
            return
        }
        if (AdbClusterRouter.enabled(this)) {
            ClusterActivityOutput.bind(this, taskId) { onClusterSurface(it) }
            ClusterActivityOutput.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            applyClusterTurnOverlay()
            runCatching { ClusterActivityOutput.ensure(this) }.onFailure {
                appendLog("Cluster activity: launch failed ${it.javaClass.simpleName}: ${it.message}")
            }
            return
        }
        ClusterActivityOutput.stop(this)
        val theme = effectiveClusterTheme()
        if (DiLink51ClusterLayout.supported()) {
            ensureDiLink51ClusterPresentation(theme)
            return
        }
        if (clusterPresentation != null) {
            applyClusterTurnOverlay()
            return
        }
        val display = ClusterMapPresentation.findDisplay(this, theme) ?: run {
            appendLog("Cluster map: no cluster projection display among ${ClusterMapPresentation.describeDisplays(this)}")
            return
        }
        val presentation = ClusterMapPresentation(this, display, theme) { surface -> runOnUiThread { onClusterSurface(surface) } }
        // The system dismisses a presentation when its display goes away; allow a new one on resume.
        presentation.setOnDismissListener {
            if (clusterPresentation === presentation) {
                clusterPresentation = null
                com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
                updateClusterMapShown()
            }
        }
        try {
            presentation.show()
            clusterPresentation = presentation
            updateClusterMapShown()
            presentation.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
            applyClusterTurnOverlay()
            Log.i(ClusterMapPresentation.TAG, "cluster presentation shown display=${display.displayId} name=${display.name}")
            appendLog("Cluster map: presentation shown display=${display.displayId} name=${display.name}")
        } catch (error: RuntimeException) {
            Log.w(ClusterMapPresentation.TAG, "cluster presentation failed", error)
            appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun ensureDiLink51ClusterPresentation(theme: DiLink51ClusterLayout.Theme) {
        val fullMap = theme == DiLink51ClusterLayout.Theme.MAP
        val visible = !DiLink51ClusterLayout.automatic(this) || detectedCluster.mapVisible
        clusterLayers.filterKeys { it != fullMap }.values.forEach { it.setMapVisible(false) }
        var target = clusterLayers[fullMap]
        if (target == null) {
            val display = ClusterMapPresentation.findDisplay(this, theme) ?: return
            lateinit var presentation: ClusterMapPresentation
            presentation = ClusterMapPresentation(this, display, theme) { surface ->
                runOnUiThread {
                    if (clusterPresentation === presentation) onClusterSurface(surface)
                }
            }
            presentation.setOnDismissListener {
                if (clusterLayers[fullMap] === presentation) clusterLayers.remove(fullMap)
                if (clusterPresentation === presentation) clusterPresentation = null
                updateClusterMapShown()
            }
            try {
                presentation.setMapVisible(false)
                clusterPresentation = presentation
                clusterLayers[fullMap] = presentation
                presentation.show()
                appendLog("Cluster map: retained layer display=${display.displayId} fullMap=$fullMap")
                target = presentation
            } catch (error: RuntimeException) {
                clusterLayers.remove(fullMap)
                clusterPresentation = null
                appendLog("Cluster map: presentation failed ${error.javaClass.simpleName}: ${error.message}")
                return
            }
        }
        clusterPresentation = target
        target.outputSurface?.let(::onClusterSurface)
        target.setStreamActive(SCREEN_TYPE_ALT in activeScreenStreamTypes)
        target.setMapVisible(visible)
        updateClusterMapShown()
        applyClusterTurnOverlay()
    }

    /** Small-window positions apply when forced on, or when the cluster reports the small navi. */
    private fun smallWindowActive(): Boolean = when (AirPlayPersistence.loadClusterSmallWindowMode(this)) {
        CLUSTER_SMALL_WINDOW_ON -> true
        CLUSTER_SMALL_WINDOW_AUTO -> when (adbNaviMode) {
            com.shilapi.xcertplay.hud.BydClusterNaviMode.SMALL -> true
            com.shilapi.xcertplay.hud.BydClusterNaviMode.FULL -> false
            else -> detectedCluster.smallWindow
        }
        else -> false
    }

    private fun applyClusterTurnOverlay() {
        val overlay = CarPlayClusterDisplay.usesCustomTurnCard(AirPlayPersistence.loadClusterContent(this))
        // CN: the small-window navi keeps a second card rect, and the card glass has a theme override.
        val smallWindow = smallWindowActive()
        val xPercent = if (smallWindow) AirPlayPersistence.loadClusterSmallWindowCardXPercent(this)
            else AirPlayPersistence.loadClusterTurnCardOverlayXPercent(this)
        val yPercent = if (smallWindow) AirPlayPersistence.loadClusterSmallWindowCardYPercent(this)
            else AirPlayPersistence.loadClusterTurnCardOverlayYPercent(this)
        val sizePercent = if (smallWindow) AirPlayPersistence.loadClusterSmallWindowCardSizePercent(this)
            else AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(this)
        val cardNight = when (AirPlayPersistence.loadClusterTurnCardTheme(this)) {
            1 -> false
            2 -> true
            else -> darkMode
        }
        val smallCardNight = when (AirPlayPersistence.loadClusterSmallWindowCardTheme(this)) {
            1 -> false
            2 -> true
            else -> cardNight
        }
        val effectiveNight = if (smallWindow) smallCardNight else cardNight
        val effectiveOpacity = if (smallWindow) AirPlayPersistence.loadClusterSmallWindowCardOpacityPercent(this)
        else AirPlayPersistence.loadClusterTurnCardOpacityPercent(this)
        ClusterActivityOutput.setTurnCard(if (overlay) clusterTurnGuidance else null,
            xPercent, yPercent, sizePercent, effectiveOpacity, effectiveNight)
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        for (presentation in presentations) {
            presentation.setTurnCardOverlay(xPercent, yPercent, sizePercent)
            presentation.setTurnCardOpacity(effectiveOpacity)
            presentation.setTurnCardNightMode(effectiveNight)
            presentation.setTurnCardGuidance(if (overlay) clusterTurnGuidance else null)
        }
    }

    private fun dismissClusterPresentation() {
        ClusterActivityOutput.stop(this)
        val presentations = (clusterLayers.values + listOfNotNull(clusterPresentation)).distinct()
        clusterLayers.clear()
        clusterPresentation = null
        clusterSurface?.let { sink?.clearSurface(SCREEN_TYPE_ALT, it) }
        clusterSurface = null
        presentations.forEach { runCatching { it.dismiss() } }
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown(false)
        controller?.setDashboardMapOutputVisible(false)
    }

    private fun onClusterSurface(surface: Surface?) {
        if (clusterSurface === surface) return
        // A direct handoff lets MediaCodec.setOutputSurface preserve its reference frames.
        // Clearing first would destroy the decoder and can leave stream 111 waiting for an IDR.
        if ((!DiLink51ClusterLayout.supported() && !AdbClusterRouter.enabled(this)) || surface == null) {
            clusterSurface?.let { old -> sink?.clearSurface(SCREEN_TYPE_ALT, old) }
        }
        clusterSurface = surface
        // Never fall back to the main surface: two decoders must not draw into one Surface.
        if (surface != null) {
            if (AdbClusterRouter.enabled(this) && ClusterActivityOutput.hasConfirmedRoute() &&
                !adbClusterConfigured && controller != null) {
                ClusterActivityOutput.setStreamActive(false)
                reconnectAfterLoss("DiLink 4 cluster confirmed; requesting its native stream")
            } else sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
        updateClusterMapShown()
    }

    private var adbClusterConfigured = false
    // Whether the running session's cluster stream was sized for a physical cluster display.
    private var clusterStreamOnDisplay = false

    // DiLink 3 creates its cluster display only after DiPlay first projects to the cluster, which can
    // finish after the session has already asked for the virtual fallback stream.
    private val clusterDisplayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (isDestroyed || !AirPlayPersistence.loadClusterMapEnabled(this@CarPlayHostActivity)) return
            val display = ClusterMapPresentation.findDisplay(this@CarPlayHostActivity, effectiveClusterTheme())
            if (display?.displayId != displayId) return
            appendLog("Cluster map: display ${display.name} appeared")
            ensureClusterPresentation()
            if (controller != null && !clusterStreamOnDisplay) {
                reconnectAfterLoss("Cluster display appeared; requesting its stream")
            }
        }
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    private fun clusterDisplayConfig(): AirPlayDisplayConfig? {
        adbClusterConfigured = false
        clusterStreamOnDisplay = false
        requestedSmallWindowForStream = null
        if (!AirPlayPersistence.loadClusterMapEnabled(this)) return null
        if (AdbClusterRouter.enabled(this) && ClusterActivityOutput.hasConfirmedRoute()) {
            adbClusterConfigured = true
            clusterStreamOnDisplay = true
            return DiLink4ClusterDisplay.streamConfig(AirPlayPersistence.loadClusterContent(this),
                AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
                AirPlayPersistence.loadClusterMarkerVerticalStep(this),
                AirPlayPersistence.loadClusterSafeAreaRect(this)).also {
                MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                appendLog("Cluster activity: requesting stream 111 at ${it.widthPixels}x${it.heightPixels}; safeArea=${it.safeArea} drawOutside=${it.safeAreaDrawOutside}; ADB task routing")
            }
        }
        val theme = effectiveClusterTheme()
        val display = ClusterMapPresentation.findDisplay(this, theme)
        if (display != null) {
            val size = ClusterMapPresentation.sizeOf(display)
            if (size.x > 0 && size.y > 0) {
                clusterStreamOnDisplay = true
                if (DiLink51ClusterLayout.supported()) {
                    val plan = DiLink51ClusterLayout.plan(size.x, size.y, theme) ?: return null
                    return DiLink51ClusterLayout.streamConfig().also {
                        MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                        appendLog("Cluster map: fixed 1920x720 stream; layout=$theme viewport=$plan")
                    }
                }
                if (DiLink4ClusterDisplay.matches(display.name, size.x, size.y)) {
                    return DiLink4ClusterDisplay.streamConfig(
                        AirPlayPersistence.loadClusterContent(this),
                        AirPlayPersistence.loadClusterMarkerHorizontalStep(this),
                        AirPlayPersistence.loadClusterMarkerVerticalStep(this),
                        AirPlayPersistence.loadClusterSafeAreaRect(this),
                    ).also { MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels }
                }
                // CN: in small-window navi the marker keeps a second position inside the visible window.
                val smallWindow = smallWindowActive()
                requestedSmallWindowForStream = smallWindow
                val requestedScale = AirPlayPersistence.loadClusterMapScalePercent(this)
                fun streamAt(scale: Int) = CarPlayClusterDisplay.config(
                    size.x,
                    size.y,
                    scale,
                    0,
                    0,
                    AirPlayPersistence.loadClusterContent(this),
                    markerXPercent = if (smallWindow) AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(this)
                    else AirPlayPersistence.loadClusterMarkerXPercent(this),
                    markerYPercent = if (smallWindow) AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(this)
                    else AirPlayPersistence.loadClusterMarkerYPercent(this),
                )
                val requested = streamAt(requestedScale)
                // The smaller-map preset enlarges the encoded canvas beyond this panel. Probe
                // the same selected hardware decoder as the main-screen enlargement guard.
                val effective = if (requestedScale > 100) {
                    val support = largerCanvasSupport(requested)
                    appendLog("Cluster map: ${support.details}")
                    if (support.supported) requested else {
                        val native = streamAt(100)
                        val fallback = if (largerCanvasSupport(native).supported) 100
                            else CarPlayClusterDisplay.STREAM_SCALE_PERCENT
                        AirPlayPersistence.saveClusterMapScalePercent(this, fallback)
                        appendLog("Cluster map: scale $requestedScale% refused (${support.reason}); using $fallback%")
                        streamAt(fallback)
                    }
                } else requested
                return effective.also {
                    MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels
                    appendLog("Cluster map: requesting ${it.widthPixels}x${it.heightPixels} on ${size.x}x${size.y} smallWindow=$smallWindow safeArea=${it.safeArea} url=${it.initialUrl}")
                }
            }
        }
        // Fallback for head units without physical cluster projection (e.g. DiLink 3.0/3.5 without digital cluster):
        // Provide standard virtual cluster stream (1280x720, 16:9 aspect) for CenterMapOverlay and MapEmbedService.
        MapMirrors.streamAspect = MapMirrors.VIRTUAL_STREAM_ASPECT
        return CarPlayClusterDisplay.config(
            widthPixels = 1280,
            heightPixels = 720,
            scalePercent = 100,
            content = AirPlayPersistence.loadClusterContent(this),
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
        ).also {
            appendLog("Cluster map: requesting virtual ${it.widthPixels}x${it.heightPixels} (16:9) stream for launcher/center card")
        }
    }

    // Hardware navigation belongs to the iPhone-rendered CarPlay UI, not Android View focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val physicalKey = Triple(event.deviceId, event.keyCode, event.scanCode)
        val downOrUp = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        if (downOrUp && physicalKey in legacySiriPresses) {
            if (event.action == KeyEvent.ACTION_UP) {
                legacySiriPresses.remove(physicalKey)
                requestLegacySiri(event.keyCode)
            }
            return true
        }
        // A release/repeat belongs to its original press even if a call, session or setting changed.
        if (downOrUp && siriKeyPresses.hasConsumedPress(physicalKey)) {
            siriKeyPresses.filter(physicalKey, event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0) {
                WheelZoomKeys.Action.PASS
            }
            return true
        }
        if (!menuOpen && AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this) &&
            CarPlayRemoteKeys.dispatch(event, controller)) {
            if (event.repeatCount == 0) {
                Log.d(
                    TAG,
                    "remote key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}",
                )
            }
            return true
        }

        // During a CarPlay call the wheel's call key answers on the iPhone instead of opening BYD's phone app.
        if (CarPlayCallKeys.onKey(this, event.keyCode, event.action == KeyEvent.ACTION_DOWN, controller)) return true

        // The wheel key service, when it runs, takes an assigned Siri key before this window sees it.
        val assignedKey = WheelZoomSettings.isSiriKey(this, WheelKey.of(event))
        val assignedAction = if (downOrUp) siriKeyPresses.filter(
            physicalKey, event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            if (activeAirPlaySession == null || !assignedKey || inCall(this)) {
                return@filter WheelZoomKeys.Action.PASS
            }
            if (siriKey.opens(event.eventTime)) {
                val message = "Siri: assigned key ${event.keyCode} sent=${controller?.requestSiri() == true}"
                Log.i(WheelKeyService.TAG, message)
                appendLog(message)
            }
            WheelZoomKeys.Action.CONSUME
        } else WheelZoomKeys.Action.PASS
        if (assignedAction != WheelZoomKeys.Action.PASS) return true
        if (downOrUp && assignedKey) return super.dispatchKeyEvent(event)

        // Keep DiPlay's existing steering-wheel/voice-key Siri handling intact.
        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) legacySiriPresses.add(physicalKey)
        if (event.action == KeyEvent.ACTION_UP) {
            requestLegacySiri(event.keyCode)
        }
        return true
    }

    private fun requestLegacySiri(keyCode: Int) {
        val sent = controller?.requestSiri() == true
        appendLog("Siri: voice key $keyCode sent=$sent")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            applyFullscreenMode()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("carplay_night_active", darkMode)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        hostAppearanceResumed = false
        AppAppearanceRuntime.clearHost(this)
        nightModeController.pause()
        super.onPause()
    }

    override fun onStop() {
        closePicturePanel()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        isActivityStarted = false
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) CenterMapOverlay.scheduleShow()
    }

    /** Shows the dashboard map as a card on the centre screen while DiPlay is in the background. */
    private fun showCenterMap() {
        if (isDestroyed || shuttingDown.get() || sink == null || isActivityStarted) return
        if (!AirPlayPersistence.loadCenterMapOverlay(this) || !AirPlayPersistence.loadClusterMapEnabled(this)) return
        if (!AirPlayPersistence.loadCenterMapFollowsDashboard(this)) return
        if (MapMirrors.launcherShowsMap) return // the launcher has the map on its own screen
        // Without the stream the card would stay black; it follows once the stream starts.
        if (SCREEN_TYPE_ALT !in activeScreenStreamTypes) return
        if (!CenterMapOverlay.permitted(this)) {
            appendLog("Centre map: no permission to draw over other apps")
            return
        }
        // Without Usage Access or when auto-hide is disabled, the card shows over any app, as before.
        if (AirPlayPersistence.loadCenterMapAutoHide(this) && HomeScreenMonitor.hasAccess(this)) {
            val monitor = homeMonitor ?: HomeScreenMonitor(this, ::onHomeScreenVisible).also { homeMonitor = it }
            if (!monitor.running) {
                monitor.start() // its first answer shows the card
                return
            }
            if (homeScreenVisible != true) {
                CenterMapOverlay.hide()
                return
            }
        }
        if (CenterMapOverlay.shown) return
        val shown = CenterMapOverlay.show(applicationContext, MapMirrors.streamAspect, ::onCenterMapSurface) {
            startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        appendLog("Centre map: card ${if (shown) "shown" else "failed"} streamActive=${SCREEN_TYPE_ALT in activeScreenStreamTypes}")
    }

    private fun onHomeScreenVisible(visible: Boolean) {
        homeScreenVisible = visible
        appendLog("Centre map: home screen ${if (visible) "in front" else "not in front"}")
        if (!AirPlayPersistence.loadCenterMapAutoHide(this)) {
            homeMonitor?.stop()
            if (!isActivityStarted && !CenterMapOverlay.diPlayInFront()) showCenterMap()
        } else if (!visible) CenterMapOverlay.hide()
        else if (!isActivityStarted && !CenterMapOverlay.diPlayInFront()) showCenterMap()
    }

    private fun onCenterMapSurface(surface: Surface?) {
        MapMirrors.set(MapMirrors.CARD, surface)
        appendLog(if (surface != null) "Centre map: mirroring the dashboard stream" else "Centre map: mirror stopped")
    }

    // The dashboard map pause must not stop the stream while a copy of the map is on screen.
    private fun updateClusterMapShown() {
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterMapShown((clusterPresentation != null ||
            (ClusterActivityOutput.hasConfirmedRoute() && clusterSurface != null)) && !MapMirrors.any)
        // Wheel zoom needs a physical map; centre-screen copies must not suppress that eligibility.
        val surface = clusterSurface?.takeIf { it.isValid }
        val presentation = clusterPresentation
        val presented = surface != null && presentation?.isShowing == true && presentation.mapVisible &&
            presentation.outputSurface === surface
        val direct = surface != null && adbClusterConfigured && ClusterActivityOutput.hasConfirmedRoute()
        controller?.setDashboardMapOutputVisible(presented || direct)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        resetSidePanel()
        hostAppearanceResumed = false
        AppAppearanceRuntime.clearHost(this)
        nightModeController.pause()
        pictureBinding?.close()
        pictureBinding = null
        removeVideoSurfaceProbe()
        fallbackVideoView?.holder?.removeCallback(fallbackSurfaceCallback)
        mainHandler.removeCallbacks(refreshTurnOverlay)
        AirPlayPersistence.overlaySettingsListener = null
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setTurnOverlayListener(null)
        com.shilapi.xcertplay.hud.BydNavigationOutputs.setClusterNaviModeListener(null)
        clusterMonitor?.stop()
        getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.unregisterDisplayListener(clusterDisplayListener)
        mainHandler.removeCallbacks(hideIdleCenterMap)
        homeMonitor?.stop()
        CenterMapOverlay.hide()
        if (CenterMapOverlay.requestShow == (::showCenterMap)) CenterMapOverlay.requestShow = null
        if (MapMirrors.sink === mirrorSink) {
            MapMirrors.sink = null
            MapMirrors.setStreamActive(false)
        }
        if (MapMirrors.onChanged === mirrorsChanged) MapMirrors.onChanged = null
        dismissClusterPresentation()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(pollConfiguration)
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        fallbackVideoView = null
        fallbackVideoBounds = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
        }
        pictureBinding = CarPlayPicture.Binding(video)
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // Measure the preparation content naturally, then fit it inside the safe viewport.
        val viewport = object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                getChildAt(0)?.measure(
                    View.MeasureSpec.makeMeasureSpec((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
            }
        }.apply {
            isClickable = true
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            contentDescription = getString(R.string.carplay)
        }
        panel.addView(icon, LinearLayout.LayoutParams(dp(88), dp(88)))
        val title = TextView(this).apply {
            text = getString(R.string.carplay)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        panel.addView(title)
        val stage = TextView(this).apply {
            text = getString(R.string.getting_carplay_ready)
            gravity = Gravity.CENTER
        }
        panel.addView(stage)
        val stageHint = TextView(this).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.connection_stage_diagnostic_hint)
        }
        panel.addView(stageHint)
        val instructions = TextView(this).apply {
            text = if (wirelessEnabled) getString(R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow)
                else getString(R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an)
            gravity = Gravity.CENTER
        }
        panel.addView(instructions)
        val recovery = Button(this).apply {
            text = getString(R.string.reset_carplay_wi_fi)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { showDiPlayHome("wireless-recovery") }
            wifiRecoveryButton = this
        }
        panel.addView(recovery, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val retry = Button(this).apply {
            text = getString(R.string.retry_carplay_connection)
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener {
                if (!CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) ||
                    shuttingDown.get() || menuOpen || handshakeResetInProgress) return@setOnClickListener
                startupRetryBudget.manualRetry()
                startupRetryStopped = false
                reconnectAttempts = 0
                visibility = View.GONE
                restartCarPlay(getString(R.string.connecting_to_your_iphone))
            }
            startupRetryButton = this
        }
        panel.addView(retry, LinearLayout.LayoutParams(dp(300), dp(64)).apply { bottomMargin = dp(12) })
        val back = Button(this).apply {
            text = getString(R.string.back_to_diplay)
            isAllCaps = false
            setOnClickListener { showDiPlayHome() }
        }
        panel.addView(back, LinearLayout.LayoutParams(dp(300), dp(64)))
        val gestureHint = TextView(this).apply {
            text = getString(R.string.open_diplay_settings_hint, gestureFingerCount)
            gravity = Gravity.CENTER
        }
        panel.addView(gestureHint)
        paintWaitingScreen = {
            val colors = WaitingScreenColors.of(darkMode)
            viewport.setBackgroundColor(colors.background)
            title.setTextColor(colors.text)
            stage.setTextColor(colors.text)
            stageHint.setTextColor(colors.secondary)
            instructions.setTextColor(colors.secondary)
            gestureHint.setTextColor(colors.secondary)
            val buttonPalette = DiPlayPalette.of(darkMode)
            back.setTextColor(buttonPalette.onAccent)
            back.background = GradientDrawable().apply {
                setColor(buttonPalette.accent)
                cornerRadius = dp(20).toFloat()
            }
        }
        paintWaitingScreen()
        viewport.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(viewport, FrameLayout.LayoutParams(-1, -1))
        // Above the video and gesture layer, below the menus.
        sidePanel = buildSidePanel().also { root.addView(it, FrameLayout.LayoutParams(0, 0)) }
        var preparationHeight = -1
        fun updatePreparationLayout() {
            val height = viewport.height - viewport.paddingTop - viewport.paddingBottom
            if (height <= 0) return
            // Interpolate within the short viewport range; keep regular screens at their existing size.
            val fraction = ((height.toFloat() / resources.displayMetrics.density - 240f) / 240f).coerceIn(0f, 1f)
            fun size(short: Float, regular: Float) = short + (regular - short) * fraction
            fun spacing(short: Float, regular: Float) = dp(size(short, regular).toInt())
            val availableWidth = viewport.width - viewport.paddingLeft - viewport.paddingRight - dp(48)
            val buttonWidth = minOf(dp(300), availableWidth.coerceAtLeast(dp(48)))
            for (button in listOf(back, recovery, retry)) {
                if (button.layoutParams.width != buttonWidth) {
                    button.layoutParams = button.layoutParams.apply { width = buttonWidth }
                }
            }
            if (preparationHeight == height) return
            preparationHeight = height
            panel.setPadding(dp(24), spacing(16f, 32f), dp(24), spacing(16f, 32f))
            val iconSize = spacing(54f, 88f)
            icon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            title.textSize = size(26f, 34f)
            title.setPadding(0, spacing(8f, 18f), 0, spacing(6f, 14f))
            stage.textSize = size(19f, 22f)
            stageHint.textSize = size(14f, 16f)
            stageHint.setPadding(0, spacing(5f, 8f), 0, 0)
            instructions.textSize = size(15f, 17f)
            instructions.setPadding(0, spacing(8f, 14f), 0, spacing(12f, 24f))
            for (button in listOf(back, recovery, retry)) {
                button.textSize = size(17f, 18f)
                button.layoutParams = button.layoutParams.apply { this.height = spacing(50f, 64f) }
            }
            gestureHint.textSize = size(12.5f, 13f)
            gestureHint.setPadding(0, spacing(10f, 20f), 0, 0)
        }
        fun fitPreparationContent() {
            val availableHeight = viewport.height - viewport.paddingTop - viewport.paddingBottom
            if (panel.height <= 0 || availableHeight <= 0) return
            val landscape = viewport.width - viewport.paddingLeft - viewport.paddingRight > availableHeight
            val scale = if (landscape) minOf(1f, availableHeight.toFloat() / panel.height) else 1f
            panel.scaleX = scale
            panel.scaleY = scale
        }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreparationContent() }
        viewport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePreparationLayout()
            fitPreparationContent()
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewport) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            viewport.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            viewport.post { updatePreparationLayout() }
            insets
        }
        ViewCompat.requestApplyInsets(viewport)
        settingsMenu = buildSettingsMenu().apply { visibility = View.GONE }
        root.addView(settingsMenu, FrameLayout.LayoutParams(-1, -1))
        safeAreaEditor = buildSafeAreaEditor().apply { visibility = View.GONE }
        root.addView(safeAreaEditor, FrameLayout.LayoutParams(-1, -1))
        videoView = video
        smoothVideo = AirPlayPersistence.loadSmoothVideo(this)
        observeVideoWindow(video)
        gestureOverlay = gestureLayer
        settingsGestureHint = gestureHint
        stageStatusView = stage
        stageHintView = stageHint
        connectionPanel = viewport
        updateDebugOverlays()
        return root
    }

    // DiPlay's side panel: placed over the part of the stream CarPlay leaves black (placeSidePanel), so it
    // follows the video when it is letterboxed; touches elsewhere still reach the gesture layer and CarPlay.
    private fun buildSidePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.GONE
            setBackgroundColor(MENU_BACKGROUND)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        panel.addView(android.widget.TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            textSize = 72f
            setTextColor(MENU_PRIMARY)
            gravity = Gravity.CENTER
        })
        sidePanelBattery = TextView(this).apply {
            textSize = 30f
            setTextColor(MENU_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(32))
        }
        panel.addView(sidePanelBattery)
        panel.addView(Button(this).apply {
            text = getString(R.string.side_panel_full_screen)
            textSize = 22f
            setTextColor(MENU_BUTTON_TEXT)
            backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnClickListener { showSidePanel(false) }
        })
        return panel
    }

    // The panel's strip of the stream, mapped through the video's layout in the window.
    private fun placeSidePanel(viewWidth: Int, viewHeight: Int) {
        val panel = sidePanel ?: return
        val display = sessionDisplay ?: return
        val strip = display.viewAreas?.let { it.panelRect(it.current) } ?: return
        val content = contentRect(viewWidth, viewHeight)
        val scaleX = content.width / display.width
        val scaleY = content.height / display.height
        val left = Math.round(content.left + strip.originX * scaleX)
        val top = Math.round(content.top + strip.originY * scaleY)
        panel.layoutParams = FrameLayout.LayoutParams(
            Math.round(content.left + (strip.originX + strip.width) * scaleX) - left,
            Math.round(content.top + (strip.originY + strip.height) * scaleY) - top,
            // Stream coordinates are physical pixels, independent of the UI's reading direction.
            Gravity.TOP or Gravity.LEFT,
        ).apply { leftMargin = left; topMargin = top }
    }

    private fun showSidePanel(show: Boolean) {
        val areas = sessionDisplay?.viewAreas ?: return
        val view = videoView ?: return
        val split = isMultiWindowActive()
        val portrait = if (split) screenPortrait() else view.height > view.width
        val target = if (show) areas.sidePanel(portrait) else areas.indexFor(view.width, view.height, split, portrait)
        val sent = target != null && controller?.showViewArea(target) == true
        appendLog("Side panel ${if (show) "shown" else "hidden"}: view area $target sent=$sent")
        if (!sent || target == null) return
        areas.use(target)
        sidePanelShown = show
        sidePanel?.visibility = if (show) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
        if (show) sidePanelTick.run()
        updateVideoLayout(view.width, view.height)
    }

    private fun resetSidePanel() {
        sidePanelShown = false
        sidePanel?.visibility = View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
    }

    // The battery shows only where DiPlay already reads it for the iPhone.
    private fun refreshSidePanel() {
        val battery = if (com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this)) {
            com.shilapi.xcertplay.hud.BydNavigationOutputs.batteryStatus(applicationContext).snapshot()
        } else null
        sidePanelBattery?.text = battery?.let { "🔋 ${Math.round(it.batteryPercent)} %  ·  ${it.rangeKm} km" }.orEmpty()
    }

    private val overlayPalette: DiPlayPalette get() = DiPlayPalette.of(appNight)
    private val settingsOverlayTheme: SettingsTheme get() = SettingsTheme.overlay(overlayPalette)
    private val MENU_BACKGROUND: Int get() = overlayPalette.overlayBackground
    private val MENU_PRIMARY: Int get() = overlayPalette.overlayPrimaryText
    private val MENU_SECONDARY: Int get() = overlayPalette.overlaySecondaryText
    private val MENU_ACCENT: Int get() = overlayPalette.overlayAccent
    private val MENU_ACCENT_TRACK: Int get() = overlayPalette.overlayAccentTrack
    private val MENU_TRACK_OFF: Int get() = overlayPalette.overlayTrackOff
    private val MENU_BUTTON_TEXT: Int get() = overlayPalette.overlayOnAccent
    private val MENU_DANGER: Int get() = overlayPalette.overlayDanger

    private fun refreshAppAppearance() {
        val nextNight = resolveAppNightNow(darkMode)
        if (nextNight == appNight) return
        appNight = nextNight

        settingsMenu?.let { previous ->
            val parent = previous.parent as? ViewGroup ?: return@let
            val index = parent.indexOfChild(previous)
            val visible = previous.visibility
            val scrollY = findDescendant<ScrollView>(previous)?.scrollY ?: 0
            val focusDescription = previous.findFocus()?.contentDescription?.toString()
            parent.removeView(previous)
            val replacement = buildSettingsMenu().apply { visibility = visible }
            settingsMenu = replacement
            parent.addView(replacement, index, FrameLayout.LayoutParams(-1, -1))
            replacement.post {
                findDescendant<ScrollView>(replacement)?.scrollTo(0, scrollY)
                if (focusDescription != null) {
                    findDescendants(replacement).firstOrNull {
                        it.contentDescription?.toString() == focusDescription
                    }?.requestFocus()
                }
            }
        }

        safeAreaEditor?.let { overlay ->
            overlay.setBackgroundColor(MENU_BACKGROUND)
            safeAreaEditorView?.applyPalette(overlayPalette)
            (overlay as? ViewGroup)?.let { group ->
                (group.getChildAt(1) as? TextView)?.setTextColor(MENU_PRIMARY)
            }
        }
        sidePanel?.let { panel ->
            panel.setBackgroundColor(MENU_BACKGROUND)
            (panel.getChildAt(0) as? TextView)?.setTextColor(MENU_PRIMARY)
            sidePanelBattery?.setTextColor(MENU_SECONDARY)
            (panel.getChildAt(2) as? Button)?.apply {
                setTextColor(MENU_BUTTON_TEXT)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
            }
        }
        (picturePanel as? CarPlayPicturePanel)?.applyPalette(overlayPalette)
    }

    private inline fun <reified T : View> findDescendant(root: View): T? =
        findDescendants(root).filterIsInstance<T>().firstOrNull()

    private fun findDescendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) yieldAll(findDescendants(root.getChildAt(index)))
        }
    }

    private fun buildSettingsMenu(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
        }
        val panel = FrameLayout(this).apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(MENU_BACKGROUND)
                cornerRadii = floatArrayOf(
                    dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(), dp(28).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(48), dp(36), dp(48), dp(36))
        }
        content.addView(
            menuText(getString(R.string.carplay_settings), 30f, MENU_PRIMARY, bold = true).apply {
                setPadding(dp(4), 0, 0, dp(2))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        // Keep the full settings at the top — the in-session menu covers only a subset.
        content.addView(menuButton(getString(R.string.open_full_settings), MENU_ACCENT, Color.WHITE) {
            cancelSettingsEdits()
            showDiPlayHome("settings")
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        // CN: live small-window state so 自动 mode is observable straight from CarPlay.
        if (AirPlayPersistence.loadClusterMapEnabled(this) &&
            AirPlayPersistence.loadClusterSmallWindowMode(this) != 0) {
            val mode = AirPlayPersistence.loadClusterSmallWindowMode(this)
            val state = when {
                mode == CLUSTER_SMALL_WINDOW_ON -> getString(R.string.settings_cluster_menu_small_state_small)
                smallWindowActive() -> getString(R.string.settings_cluster_menu_small_state_small)
                adbNaviMode == com.shilapi.xcertplay.hud.BydClusterNaviMode.FULL ||
                    detectedCluster.mapVisible -> getString(R.string.settings_cluster_menu_small_state_full)
                else -> getString(R.string.settings_cluster_menu_small_state_unknown)
            }
            content.addView(
                menuText("${getString(R.string.settings_cluster_menu_small_title)} · $state", 15f, MENU_ACCENT).apply {
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x1F0A84FF); cornerRadius = dp(12).toFloat()
                    })
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            if (mode == CLUSTER_SMALL_WINDOW_AUTO && adbNaviMode == null &&
                !DiLink51ClusterMonitor.hasAccess(this)) {
                content.addView(
                    menuText(getString(R.string.cluster_small_window_access_missing), 14f, MENU_DANGER).apply {
                        setPadding(dp(4), dp(4), 0, 0)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
        }
        if (sessionDisplay?.viewAreas?.sidePanel() != null) {
            content.addView(Button(this).apply {
                text = getString(if (sidePanelShown) R.string.side_panel_full_screen else R.string.side_panel_show)
                textSize = 20f
                setOnClickListener {
                    cancelSettingsEdits()
                    showSidePanel(!sidePanelShown)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(
            settingsCategoryHeader(getString(R.string.connection)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )

        content.addView(
            buildMfiTargetSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val wirelessRowResult = ConnectionSettingsSection.createWirelessCarPlayRow(
            context = this,
            checked = wirelessEnabled,
            theme = settingsOverlayTheme,
        ) { checked ->
            if (wirelessEnabled == checked) return@createWirelessCarPlayRow
            wirelessEnabled = checked
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
            updateHotspotStatusBlock()
            appendLog(
                "Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; " +
                    "applies when settings close",
            )
            requestStartupPrerequisites()
        }
        content.addView(
            wirelessRowResult.rowView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        content.addView(
            buildHotspotModeSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )

        content.addView(
            menuText(getString(R.string.hotspot_status), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        content.addView(
            hotspotStatusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        content.addView(
            settingsSwitchRow(
                label = getString(R.string.auto_start_on_boot),
                checked = autoStartOnBoot,
                description = getString(R.string.start_carplay_automatically_after_device_boot),
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "enabled" else "disabled"}")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.settings_navigation)),
        )
        content.addView(
            settingsChoiceRow(
                label = getString(R.string.settings_cluster_menu_small_title),
                options = listOf(
                    0 to getString(R.string.cluster_small_window_off),
                    1 to getString(R.string.cluster_small_window_on),
                    2 to getString(R.string.cluster_small_window_auto),
                ),
                selected = AirPlayPersistence.loadClusterSmallWindowMode(this),
            ) { value ->
                AirPlayPersistence.saveClusterSmallWindowMode(this, value)
                if (CarPlayBackgroundSession.active) {
                    recoveryPendingAfterMenu = true
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildLocationReportingSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        if (advancedAudioChannelMappingSupported) {
            content.addView(
                settingsCategoryHeader(getString(R.string.audio)),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(36) },
            )
            content.addView(
                settingsSwitchRow(
                    label = getString(R.string.advanced_audio_channel_mapping),
                    checked = advancedAudioChannelMapping,
                    description = getString(R.string.use_usage_content_type_routing_instead_of_stream_type),
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content.addView(
            settingsCategoryHeader(getString(R.string.settings_vehicle)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildIdentitySettingsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAirPlayIconSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            buildDrivingSideSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            settingsCategoryHeader(getString(R.string.settings_display)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )

        val resolutionControl = DisplaySettingsSection.createResolutionSlider(
            context = this,
            initialPercent = displayScalePercent,
            theme = settingsOverlayTheme,
        ) { percent ->
            displayScalePercent = percent
            displayScaleTenths = CarPlayDisplayScale.sanitize((percent + 5) / 10)
            updateResolutionMenu()
        }
        val resolutionValue = resolutionControl.valueTextView
        content.addView(
            resolutionControl.container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.frame_rate),
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                    step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { "$it fps" },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            settingsChoiceRow(
                label = getString(R.string.physical_size_basis),
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to getString(R.string.widest_width),
                    AirPlayPhysicalSizeBasis.HEIGHT to getString(R.string.longest_height),
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            buildStepSliderSection(
                title = getString(R.string.physical_length),
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                        AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                    step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { "$it mm" },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        content.addView(
            buildSafeAreaSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )

        content.addView(
            buildFullscreenSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildDockSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.settings_advanced)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        val hevcRow = DisplaySettingsSection.createHevcRow(
            context = this,
            checked = hevcEnabled,
            theme = settingsOverlayTheme,
        ) { checked ->
            if (hevcEnabled == checked) return@createHevcRow
            hevcEnabled = checked
            appendLog(
                "HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; " +
                    "applies when settings close",
            )
            updateResolutionMenu()
        }
        content.addView(
            hevcRow.rowView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        val softwareHevcRow = DisplaySettingsSection.createSoftwareHevcRow(
            context = this,
            checked = hevcSoftwareDecoderEnabled,
            theme = settingsOverlayTheme,
        ) { checked ->
            if (hevcSoftwareDecoderEnabled == checked) return@createSoftwareHevcRow
            hevcSoftwareDecoderEnabled = checked
            appendLog(
                "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                    "applies when settings close",
            )
            updateResolutionMenu()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            content.addView(
                softwareHevcRow.rowView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            content.addView(
                menuText(
                    getString(R.string.settings_android9_compat),
                    16f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content.addView(
            settingsCategoryHeader(getString(R.string.diagnostics)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildDebugLogsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        val preview = menuText("", 17f, MENU_SECONDARY).apply { tag = "settings-chrome" }
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )

        val save = menuButton(getString(R.string.save_and_reconnect), MENU_ACCENT, MENU_BUTTON_TEXT) {
            saveSettingsAndReconnect()
        }.apply { tag = "settings-chrome" }
        content.addView(
            save,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )

        val exitApplicationButton = menuButton(getString(R.string.exit_application), MENU_DANGER, Color.WHITE) {
            exitApplication()
        }.apply { tag = "settings-chrome" }
        content.addView(
            exitApplicationButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader(getString(R.string.carplay_controls)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) },
        )
        content.addView(menuButton(getString(R.string.language_app_language), MENU_TRACK_OFF, Color.WHITE) {
            AppLocale.showPicker(this@CarPlayHostActivity)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val gestureButton = menuButton("", MENU_TRACK_OFF, Color.WHITE) {
            gestureFingerCount = if (gestureFingerCount >= 4) 2 else gestureFingerCount + 1
            it.text = getString(R.string.settings_gesture_fingers, gestureFingerCount)
        }
        gestureButton.text = getString(R.string.settings_gesture_fingers, gestureFingerCount)
        content.addView(gestureButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        content.addView(
            settingsChoiceRow(
                label = getString(R.string.settings_swipe_target_label),
                options = listOf(
                    false to getString(R.string.settings_swipe_target_overlay),
                    true to getString(R.string.settings_swipe_target_full),
                ),
                selected = swipeOpensFullSettings,
            ) { value ->
                swipeOpensFullSettings = value
                AirPlayPersistence.saveSwipeOpensFullSettings(this, value)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            menuText(getString(R.string.settings_swipe_target_hint), 16f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        panel.addView(
            layoutOverlayMenu(content),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        panel.addView(
            Button(this).apply {
                text = "✕"
                isAllCaps = false
                textSize = 19f
                setTextColor(MENU_PRIMARY)
                setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x24000000); shape = android.graphics.drawable.GradientDrawable.OVAL
                    setStroke(dp(1), 0x2EFFFFFF)
                })
                contentDescription = getString(R.string.discard_changes_and_exit_settings)
                minWidth = 0
                minHeight = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener { cancelSettingsEdits() }
            },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(22)
                topMargin = dp(22)
            },
        )
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 94) / 100,
                Gravity.BOTTOM,
            ).apply { leftMargin = dp(72); rightMargin = dp(72) },
        )
        overlay.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val desiredWidth = minOf(view.width - dp(144), MAX_SETTINGS_MENU_WIDTH_PX)
            val desiredHeight = (view.height * 94) / 100
            val params = panel.layoutParams as FrameLayout.LayoutParams
            if (params.width != desiredWidth || params.height != desiredHeight) {
                params.width = desiredWidth
                params.height = desiredHeight
                panel.layoutParams = params
            }
        }

        resolutionValueView = resolutionValue
        resolutionPreviewView = preview
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        updateResolutionMenu()
        return overlay
    }

    private fun persistMenuSettings() {
        AirPlayPersistence.saveSettingsGestureFingers(this, gestureFingerCount)
        AirPlayPersistence.saveSwipeOpensFullSettings(this, swipeOpensFullSettings)
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveExistingWifiCredentials(this, existingWifiSsid, existingWifiPassphrase)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        CarPlayDock.save(this, carPlayDock)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaRects = mutableMapOf<DisplaySize, SafeAreaRect?>().apply {
                safeAreaSize?.let { put(it, AirPlayPersistence.loadSafeAreaRect(this@CarPlayHostActivity, it.width, it.height)) }
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        val previewChangedSystemBars = hideTopBar != AirPlayPersistence.loadHideTopBar(this) ||
            hideBottomBar != AirPlayPersistence.loadHideBottomBar(this)
        loadPersistedSettings()
        baseline.safeAreaRects.forEach { (size, savedRect) ->
            savedRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
        syncMfiSettingsControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        // Closing an unchanged menu must not schedule a display renegotiation. Real bar
        // previews still need to restore the window and re-measure after cancellation.
        if (previewChangedSystemBars) {
            applyFullscreenMode()
            refreshDisplaySizeAfterLayout()
        }
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = ConnectionSettingsSection.createMfiTargetChoice(
            context = this,
            selected = mfiTarget,
            theme = settingsOverlayTheme,
        ) { target ->
            if (mfiTarget == target) return@createMfiTargetChoice
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = targetChoice.radioGroup
        section.addView(
            targetChoice.container,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.i2c_device),
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(getString(R.string.linux_device_path_for_example_dev_i2c_1), 14f, MENU_SECONDARY),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    getString(R.string.server_address),
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    getString(R.string.token_optional),
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    getString(R.string.settings_mfi_address_hint),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.USB_CH341 -> getString(R.string.usb_ch341)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow(getString(R.string.manufacturer), manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow(getString(R.string.model), model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow(getString(R.string.oem_label), oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun overlayMenuWide(): Boolean = resources.displayMetrics.widthPixels >= dp(1000)

    private fun layoutOverlayMenu(content: LinearLayout): View {
        val hero = mutableListOf<View>()
        val groups = mutableListOf<Pair<String, MutableList<View>>>()
        val chrome = mutableListOf<View>()
        var current: MutableList<View>? = null
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            when {
                child.tag == "settings-category" -> {
                    val title = overlayMenuCategoryTitle(child)
                    val group = mutableListOf(child)
                    groups.add(title to group)
                    current = group
                }
                child.tag == "settings-chrome" -> chrome.add(child)
                current != null -> current.add(child)
                else -> hero.add(child)
            }
        }
        for (i in content.childCount - 1 downTo 0) content.removeViewAt(i)
        val pages = groups.map { it.second }
        val selectedTitle = overlayMenuCategoryTitle
            ?.takeIf { title -> groups.any { it.first == title } }
            ?: groups.firstOrNull()?.first
        overlayMenuCategoryTitle = selectedTitle
        val wide = overlayMenuWide() && groups.size > 1
        fun show(title: String?) {
            overlayMenuCategoryTitle = title
            pages.forEachIndexed { index, page ->
                val visible = groups[index].first == title
                page.forEach { child ->
                    child.visibility = when {
                        !visible -> View.GONE
                        wide && child.tag == "settings-category" -> View.GONE
                        else -> View.VISIBLE
                    }
                }
            }
        }
        show(selectedTitle)
        fun rail(onPaint: (ViewGroup) -> Unit = {}): LinearLayout {
            val rail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            groups.forEach { (title, _) ->
                rail.addView(
                    overlayMenuRailItem(title, title == selectedTitle) { selected ->
                        show(selected)
                        onPaint(rail)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(8) },
                )
            }
            return rail
        }
        fun paintRail(rail: ViewGroup) {
            for (i in 0 until rail.childCount) {
                val row = rail.getChildAt(i)
                val selected = row.contentDescription == overlayMenuCategoryTitle
                row.isSelected = selected
                (row as? ViewGroup)?.getChildAt(0)?.setBackgroundColor(
                    if (selected) MENU_ACCENT else Color.TRANSPARENT,
                )
                ((row as? ViewGroup)?.getChildAt(1) as? TextView)?.setTextColor(
                    if (selected) MENU_ACCENT else MENU_PRIMARY,
                )
            }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(28), dp(36), dp(28))
        }
        hero.forEach(body::addView)
        if (wide) {
            val split = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val rail = rail(::paintRail)
            split.addView(
                ScrollView(this).apply { addView(rail) },
                LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            split.addView(View(this).apply { setBackgroundColor(0x26FFFFFF) },
                LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    leftMargin = dp(16); rightMargin = dp(16)
                })
            val pageColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            pages.forEach { page -> page.forEach(pageColumn::addView) }
            split.addView(
                ScrollView(this).apply {
                    isFillViewport = true
                    addView(pageColumn, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            body.addView(split, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            chrome.forEach(body::addView)
            return body
        }
        if (groups.size > 1) {
            body.addView(rail(::paintRail))
            pages.forEach { page ->
                page.forEach { child ->
                    if (child.tag == "settings-category") child.visibility = View.GONE
                }
            }
        }
        pages.forEach { page -> page.forEach(body::addView) }
        chrome.forEach(body::addView)
        return ScrollView(this).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun overlayMenuCategoryTitle(header: View): String {
        val head = (header as? ViewGroup)?.getChildAt(0) as? ViewGroup
        return (head?.getChildAt(1) as? TextView)?.text?.toString().orEmpty()
    }

    private fun overlayMenuRailItem(title: String, selected: Boolean, onSelect: (String) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            isSelected = selected
            contentDescription = title
            setPadding(dp(8), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dp(12).toFloat()
                },
                null,
            )
            addView(View(this@CarPlayHostActivity).apply {
                setBackgroundColor(if (selected) MENU_ACCENT else Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(4), dp(28)).apply { rightMargin = dp(10) })
            addView(
                menuText(title, 16f, if (selected) MENU_ACCENT else MENU_PRIMARY, bold = true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            setOnClickListener { onSelect(title) }
        }

    private fun settingsCategoryHeader(title: String): View = LinearLayout(this).apply {
        tag = "settings-category"
        orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this@CarPlayHostActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
        }
        head.addView(View(this@CarPlayHostActivity).apply {
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(MENU_ACCENT); cornerRadius = dp(2).toFloat()
            })
        }, LinearLayout.LayoutParams(dp(4), dp(18)).apply { rightMargin = dp(10) })
        head.addView(
            menuText(title, 17f, MENU_PRIMARY, bold = true).apply { letterSpacing = 0.04f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(View(this@CarPlayHostActivity).apply {
            setBackgroundColor(0x26FFFFFF)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) })
    }

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText(getString(R.string.report_location_to_iphone), 20f, MENU_SECONDARY),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = Switch(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = getString(R.string.report_android_location_to_the_iphone)
                SettingsWidgets.applyLargeSwitchStyle(this, MENU_ACCENT, MENU_SECONDARY, MENU_ACCENT_TRACK, MENU_TRACK_OFF)
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    getString(R.string.sends_precise_android_location_as_carplay_gps_data_when_th),
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = getString(R.string.debug_logs),
            checked = debugLogsEnabled,
            description = getString(R.string.show_on_screen_debug_logs),
        ) { checked ->
            debugLogsEnabled = checked
            appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
            updateDebugOverlays()
        }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = SeekBar(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            splitTrack = false
            progressTintList = ColorStateList.valueOf(MENU_ACCENT)
            thumbTintList = ColorStateList.valueOf(MENU_ACCENT)
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.airplay_icon), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            Button(this).apply {
                text = getString(R.string.choose_image)
                isAllCaps = false
                setOnClickListener {
                    externalActivityInProgress = true
                    launchCarButtonImagePicker(
                        openDocument = { imageDocumentPicker.launch(arrayOf("image/*")) },
                        getContent = { imagePicker.launch("image/*") },
                        documentPickerIsSystem = documentPickerIsSystem(),
                    ).onFailure {
                        externalActivityInProgress = false
                        appendLog("No image picker: ${it.javaClass.simpleName}")
                        android.widget.Toast.makeText(this@CarPlayHostActivity,
                            getString(R.string.this_head_unit_has_no_image_picker),
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            Button(this).apply {
                text = getString(R.string.default_icon)
                isAllCaps = false
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    // Between the driver's side and the bottom the dock moves at once (and is saved at once) when the
    // running session declared both; to or from automatic waits for Save, which reconnects.
    private fun buildDockSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.carplay_dock), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val labels = mapOf(
            CarPlayDock.AUTOMATIC to R.string.carplay_dock_automatic,
            CarPlayDock.DRIVER_SIDE to R.string.carplay_dock_driver_side,
            CarPlayDock.BOTTOM to R.string.carplay_dock_bottom,
        )
        val buttons = CarPlayDock.entries.associateWith { dock ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = getString(labels.getValue(dock))
                setTextColor(MENU_PRIMARY)
                isChecked = carPlayDock == dock
            }.also { group.addView(it) }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val next = buttons.entries.firstOrNull { it.value.id == checkedId }?.key ?: return@setOnCheckedChangeListener
            carPlayDock = next
            val from = sessionDock
            val areas = sessionDisplay?.viewAreas
            val target = next.edge?.let { areas?.withDock(it) }
            if (from != null && areas != null && target != null && CarPlayDock.movesLive(from, next) &&
                controller?.showViewArea(target) == true
            ) {
                areas.use(target)
                CarPlayDock.save(this, next)
            }
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        section.addView(menuText(getString(R.string.carplay_dock_hint), 16f, MENU_SECONDARY))
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.driving_side), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.left_hand_drive)
            setTextColor(MENU_PRIMARY)
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.right_hand_drive)
            setTextColor(MENU_PRIMARY)
            isChecked = rightHandDrive
        }
        group.addView(left)
        group.addView(right)
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.fullscreen), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addSystemBarControls(
            hideTopBar = hideTopBar,
            hideBottomBar = hideBottomBar,
            onHideTopBarChanged = { checked ->
                hideTopBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            onHideBottomBarChanged = { checked ->
                hideBottomBar = checked
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
        ) { label, checked, onChanged ->
            section.addView(
                settingsSwitchRow(getString(label), checked, getString(label), onChanged),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )
        }
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.safe_area), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.set)
                isAllCaps = false
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            Button(this).apply {
                text = getString(R.string.reset)
                isAllCaps = false
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = getString(R.string.draw_outside_safe_area),
                checked = safeAreaDrawOutside,
                description = getString(R.string.allow_carplay_ui_outside_the_safe_area),
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(MENU_BACKGROUND)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this, overlayPalette)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText(getString(R.string.safe_area), 24f, MENU_PRIMARY, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            Button(this).apply {
                text = getString(R.string.cancel)
                isAllCaps = false
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            Button(this).apply {
                text = getString(R.string.save)
                isAllCaps = false
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_SECONDARY).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 18f
                setTextColor(MENU_PRIMARY)
                setHintTextColor(MENU_SECONDARY)
                backgroundTintList = ColorStateList.valueOf(MENU_ACCENT)
                minHeight = dp(48)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = SettingsWidgets.createSwitchRow(
        context = this,
        label = label,
        description = description,
        checked = checked,
        theme = settingsOverlayTheme,
        contentDescription = description,
        onChanged = onChanged,
    ).rowView

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText(getString(R.string.wi_fi_session), 20f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to getString(R.string.wi_fi_p2p_5_ghz))
            }
            add(WirelessHotspotMode.MANUAL to getString(R.string.built_in_car_hotspot))
            add(WirelessHotspotMode.EXISTING_WIFI to getString(R.string.existing_wifi_title))
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 18f
                setTextColor(MENU_SECONDARY)
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(MENU_ACCENT, MENU_SECONDARY),
                )
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) getString(R.string.hotspot_state_stopped) else getString(R.string.hotspot_state_off))
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            settingsInputRow(getString(R.string.hotspot_ssid), manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.band),
                options = listOf(
                    ManualHotspotBand.AUTO to getString(R.string.auto),
                    ManualHotspotBand.GHZ_2_4 to getString(R.string.s_2_4_ghz),
                    ManualHotspotBand.GHZ_5 to getString(R.string.s_5_ghz),
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.channel_0_auto),
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = getString(R.string.hotspot_password),
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsChoiceRow(
                label = getString(R.string.security),
                options = listOf(
                    ManualHotspotSecurity.OPEN to getString(R.string.open),
                    ManualHotspotSecurity.WPA2 to getString(R.string.wpa2),
                    ManualHotspotSecurity.WPA3_TRANSITION to getString(R.string.wpa3_transition),
                    ManualHotspotSecurity.WPA3 to getString(R.string.wpa3),
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        val existingFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(menuText(getString(R.string.existing_wifi_instructions), 14f, MENU_SECONDARY))
            addView(settingsInputRow(getString(R.string.existing_wifi_ssid), existingWifiSsid) {
                existingWifiSsid = it
                existingWifiErrorView?.visibility = View.GONE
            })
            addView(settingsInputRow(getString(R.string.existing_wifi_password), existingWifiPassphrase, password = true) {
                existingWifiPassphrase = it
                existingWifiErrorView?.visibility = View.GONE
            })
        }
        existingWifiErrorView = menuText("", 14f, MENU_DANGER).apply { visibility = View.GONE }
        existingFields.addView(existingWifiErrorView)
        section.addView(existingFields)
        existingWifiFields = existingFields
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        existingWifiFields?.visibility = if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) View.VISIBLE else View.GONE
        existingWifiErrorView?.visibility = View.GONE
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.LOCAL && runCatching { DiPlayBootstrap.ensure(this, mfiTarget) }.isFailure ->
                getString(R.string.setup_error_auth)
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                getString(R.string.i2c_device_path_is_required)
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                getString(R.string.remote_server_address_is_required)
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                getString(R.string.remote_server_address_must_start_with_http_or_https)
            '\u0000' in mfiI2cPath -> getString(R.string.i2c_device_path_contains_u_0000)
            '\u0000' in remoteMfiServer -> getString(R.string.remote_server_address_contains_u_0000)
            '\u0000' in remoteMfiToken -> getString(R.string.remote_token_contains_u_0000)
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            val error = com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(existingWifiSsid, existingWifiPassphrase)
            existingWifiErrorView?.text = error?.let { getString(it.messageResource()) }.orEmpty()
            existingWifiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
            return error == null
        }
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> getString(R.string.hotspot_ssid_is_required)
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
            '\u0000' in manualHotspotSsid -> getString(R.string.hotspot_ssid_contains_u_0000)
            manualHotspotChannel !in 0..196 -> getString(R.string.channel_must_be_0_or_1_196)
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in manualHotspotPassphrase -> getString(R.string.hotspot_password_contains_u_0000)
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                getString(R.string.password_must_be_empty_when_security_is_open)
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> getString(R.string.wi_fi_p2p_5_ghz)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> getString(R.string.localonlyhotspot)
        WirelessHotspotMode.MANUAL -> getString(R.string.manual_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> getString(R.string.existing_wifi_title)
    }

    /** Rounded in-session menu button: one shape for primary, danger and quiet actions. */
    private fun menuButton(label: String, bgColor: Int, fgColor: Int, onClick: (Button) -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            setTextColor(fgColor)
            setPadding(dp(20), 0, dp(20), 0)
            minHeight = dp(52)
            stateListAnimator = null
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x2EFFFFFF)
            })
            setOnClickListener { onClick(it as Button) }
        }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = getString(R.string.running))
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                hotspotStatus.copy(state = getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    private fun updateHotspotStatusBlock() {
        if (!wirelessEnabled) {
            hotspotStatusView?.text = getString(R.string.wireless_hotspot_off)
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append(getString(R.string.hotspot_wireless_prefix)).append(status.state)
            status.ssid?.let { append(getString(R.string.hotspot_ssid_prefix)).append(it) }
            status.backend?.let { append(getString(R.string.hotspot_backend_prefix)).append(it) }
            status.band?.let { append(getString(R.string.hotspot_band_prefix)).append(it) }
            status.channel?.let {
                append(getString(R.string.hotspot_channel_prefix)).append(if (it == 0) getString(R.string.auto_label) else it.toString())
            }
        }
    }

    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = SettingsWidgets.createChoiceRow(
        context = this,
        label = label,
        options = options,
        selected = selected,
        theme = settingsOverlayTheme,
        onSelected = onSelected,
    ).container

    private fun updateResolutionMenu() {
        resolutionValueView?.text = "${displayScalePercent}%"
        val native = activeDisplaySize ?: currentActivitySize()
        val resolution = if (native == null) {
            getString(R.string.handshake_resolution_waiting_for_display)
        } else {
            val negotiated = CarPlayDisplayScale.applyPercent(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScalePercent,
            )
            "${getString(R.string.resolution_handshake_prefix)}${native.width} x ${native.height} -> " +
                "${negotiated.widthPixels} x ${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            "HEVC (H.265, ${if (hevcSoftwareDecoderEnabled) "software" else "hardware"})"
        }
        val fullscreen = buildString {
            append(if (hideTopBar) getString(R.string.fullscreen_top_hidden) else getString(R.string.fullscreen_top_shown))
            append(", ")
            append(if (hideBottomBar) getString(R.string.fullscreen_bottom_hidden) else getString(R.string.fullscreen_bottom_shown))
        }
        resolutionPreviewView?.text = buildString {
            append(resolution).append('\n')
            append(getString(R.string.preview_identity)).append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel()).append('\n')
            append(getString(R.string.preview_oem_label)).append(oemLabel.ifBlank { getString(R.string.preview_empty) }).append('\n')
            append(getString(R.string.preview_frame_rate)).append(fps).append(" fps\n")
            append(getString(R.string.preview_detected_maximum))
                .append(maximumDetectedWidthPixels).append(" x ")
                .append(maximumDetectedHeightPixels).append(" px\n")
            append(getString(R.string.preview_physical_reference))
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> getString(R.string.basis_widest_width)
                        AirPlayPhysicalSizeBasis.HEIGHT -> getString(R.string.basis_longest_height)
                    },
                )
                .append(" = ").append(widthPhysicalMm).append(" mm\n")
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(getString(R.string.preview_carplay_physical_size))
                    .append(physical.widthMm).append(" x ")
                    .append(physical.heightMm).append(" mm\n")
            }
            append(getString(R.string.preview_driving_side)).append(if (rightHandDrive) getString(R.string.driving_side_right) else getString(R.string.driving_side_left)).append('\n')
            append(getString(R.string.preview_fullscreen)).append(fullscreen).append('\n')
            append(getString(R.string.preview_video_transport)).append(transport).append('\n')
            append(getString(R.string.preview_location_reporting))
                .append(if (locationReportingEnabled) getString(R.string.enabled_value) else getString(R.string.disabled_value))
                .append('\n')
            if (advancedAudioChannelMappingSupported) {
                append(getString(R.string.preview_audio_channel_mapping))
                    .append(if (advancedAudioChannelMapping) getString(R.string.mapping_aaos_buses) else getString(R.string.mapping_mobile_compatible))
                    .append('\n')
            }
            append(safeAreaSummary())
        }
    }

    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport =
        if (maxOf(display.widthPixels, display.heightPixels) > 3840 || minOf(display.widthPixels, display.heightPixels) > 2160) {
            CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
        } else decoderCanvasSupport(display)

    private fun decoderCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport = try {
        val mime = if (hevcEnabled) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Match MediaCodec.createDecoderByType's first suitable decoder; do not silently force
        // an enlarged stream through a software decoder on a slower head unit.
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (Build.VERSION.SDK_INT >= 29) decoder.isHardwareAccelerated
                else !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels, display.heightPixels, display.fps.toDouble()) == true
            val reason = when {
                !hardware -> "software_decoder"
                hevcEnabled && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            CanvasSupport(reason == "supported", reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                    "widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} " +
                    "fpsRange=${video?.supportedFrameRates} result=$reason")
        }
    } catch (error: Exception) {
        CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}")
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        // The home settings page can change the name while this host stays alive.
        if (!menuOpen) oemLabel = AirPlayPersistence.loadOemLabel(this)
        val safeWidth = (size.width / 2 * 2).coerceAtLeast(2)
        val safeHeight = (size.height / 2 * 2).coerceAtLeast(2)
        val alignedSize = DisplaySize(safeWidth, safeHeight)
        val physical = resolvePhysicalSize(alignedSize)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = alignedSize.width,
            heightPixels = alignedSize.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            // Tell CarPlay to use its native knob/focus model on Android TV and other non-touch
            // hosts. Touch-capable head units remain touchscreen-primary.
            primaryInputDevice = if (knobPrimary) 3 else 1,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val requestedResolutionPercent = displayScalePercent
        val requestedResolutionDisplay = CarPlayDisplayScale.applyPercent(baseDisplay, requestedResolutionPercent)
        var resolutionDisplay = requestedResolutionDisplay
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        var support = when {
            uiScalePercent < CarPlayUiScale.DEFAULT && scaledDisplay === resolutionDisplay ->
                CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            uiScalePercent < CarPlayUiScale.DEFAULT || scaledDisplay.widthPixels > baseDisplay.widthPixels ||
                scaledDisplay.heightPixels > baseDisplay.heightPixels -> largerCanvasSupport(scaledDisplay)
            else -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
        }
        var smallerUiFallback = false
        var resolutionFallback = false
        if (!support.supported && uiScalePercent < CarPlayUiScale.DEFAULT) {
            val failedCanvas = support
            smallerUiFallback = true
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            scaledDisplay = resolutionDisplay
            appendLog("Larger CarPlay canvas unavailable reason=${failedCanvas.reason}; using Default icon and text size")
            // Removing the smaller-controls enlargement may leave a separately enlarged resolution.
            if (displayScalePercent > 100) {
                val resolutionSupport = largerCanvasSupport(scaledDisplay)
                support = resolutionSupport.copy(
                    reason = if (resolutionSupport.supported) failedCanvas.reason else resolutionSupport.reason,
                    details = "${failedCanvas.details}\n${resolutionSupport.details}",
                )
            }
        }
        if (!support.supported && displayScalePercent > 100) {
            resolutionFallback = true
            displayScalePercent = 100
            displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
            AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
            AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
            resolutionDisplay = baseDisplay
            scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
            appendLog("Enlarged CarPlay resolution unavailable reason=${support.reason}; using 100%")
        }
        if (smallerUiFallback || resolutionFallback) {
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    getString(if (resolutionFallback) R.string.resolution_canvas_unsupported
                        else R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        // A fixed dock, split-screen support or a turning screen declares several areas the car switches
        // between live. A session that starts in split screen sizes its canvas to that window, so it keeps
        // the plain canvas and one area for it.
        val inSplitScreen = isMultiWindowActive()
        val dock = CarPlayDock.load(this).also { sessionDock = it }
        // The whole screen and CarPlay's full window in each orientation: the system bars take their
        // place in both, so a remembered split window is sized against the window of its orientation.
        val screen = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val screenLong = maxOf(screen.widthPixels, screen.heightPixels)
        val screenShort = minOf(screen.widthPixels, screen.heightPixels)
        val (landscapeWindow, portraitWindow) = CarPlayRotation.turnedWindows(size.width, size.height,
            screen.widthPixels, screen.heightPixels)
        // Before DiPlay has seen a split window, expect one from the screen, its bars and Android's divider.
        val statusBar = systemDimension("status_bar_height")
        val navigationBar = systemDimension("navigation_bar_height")
        val divider = (systemDimension("docked_stack_divider_thickness") -
            2 * systemDimension("docked_stack_divider_insets")).coerceAtLeast(0)
        val splitWindow: (Boolean) -> Pair<Float, Float>? = { portrait ->
            if (SplitScreenSettings.enabled(this) && !inSplitScreen) {
                val window = if (portrait) portraitWindow else landscapeWindow
                val expected = SplitScreenSettings.expectedWindow(portrait, screenLong, screenShort,
                    statusBar, navigationBar, divider)
                SplitScreenSettings.ofWindow(SplitScreenSettings.window(this, portrait, expected),
                    if (portrait) screenShort else screenLong, if (portrait) screenLong else screenShort,
                    window.first, window.second)
            } else null
        }
        val longPixels = maxOf(display.widthPixels, display.heightPixels)
        val square = if (CarPlayRotation.enabled(this) && !inSplitScreen) {
            CarPlayRotation.squareSide(longPixels, CarPlayRotation.picture(this), hevcEnabled, hevcSoftwareDecoderEnabled)
        } else null
        val canvas = if (square == null) display else {
            // The square is drawn across the screen's long side whatever its pixel count, so it reports that
            // side's real length: CarPlay then sizes its UI for the real screen, and a smaller square (the
            // smoother picture) only lowers the resolution, not the scale. It has no room for the custom
            // safe area.
            val squareMm = if (display.widthPixels >= display.heightPixels) display.widthPhysicalMm else display.heightPhysicalMm
            display.copy(widthPixels = square, heightPixels = square, widthPhysicalMm = squareMm,
                heightPhysicalMm = squareMm, viewArea = null, safeArea = null)
        }
        val viewAreas = if (square == null) {
            CarPlayViewAreas.build(display.widthPixels, display.heightPixels, listOf(
                CarPlayViewAreas.Screen(display.widthPixels, display.heightPixels, portrait = display.heightPixels > display.widthPixels),
            ), dock, splitWindow, startPortrait = display.heightPixels > display.widthPixels,
                sidePanel = SidePanelSettings.enabled(this), rightHandDrive = rightHandDrive)
        } else {
            val (landscape, portrait) = CarPlayRotation.turningAreas(square, size.width, size.height,
                screen.widthPixels, screen.heightPixels)
            CarPlayViewAreas.build(square, square, listOf(
                CarPlayViewAreas.Screen(landscape.first, landscape.second, portrait = false),
                CarPlayViewAreas.Screen(portrait.first, portrait.second, portrait = true),
            ), dock, splitWindow, startPortrait = size.height > size.width, sidePanel = SidePanelSettings.enabled(this),
                rightHandDrive = rightHandDrive)
        }
        pendingViewAreas = viewAreas
        val declared = if (viewAreas == null) canvas else canvas.copy(viewAreas = viewAreas.areas, initialViewArea = viewAreas.current)
        if (square != null) appendLog("Turning screen: square canvas ${square}x$square, areas ${viewAreas?.areas}")
        if (SplitScreenSettings.enabled(this) && !inSplitScreen) {
            appendLog("Split screen expected from status bar=$statusBar navigation bar=$navigationBar divider=$divider px")
        }
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${requestedResolutionPercent}% " +
            "base=${requestedResolutionDisplay.widthPixels}x${requestedResolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (hevcEnabled) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent resolution=${displayScalePercent}% " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        val carBluetoothAudio = AirPlayPersistence.loadCarBluetoothAudio(this)
        if (carBluetoothAudio) logCarBluetoothAudio()
        return AirPlayConfig(
            deviceName = "DiPlay",
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = declared,
            cluster = clusterDisplayConfig(),
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            microphoneOpus = com.shilapi.xcertplay.media.OpusEncoderSupport.isAvailable(),
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = oemLabel,
            icons = listOf(loadAirPlayIcon()),
            videoInCar = com.shilapi.xcertplay.hud.BydOutputSettings.videoWhileParkedActive(this),
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(this),
            disableAudioOutput = carBluetoothAudio,
        )
    }

    /** Car Bluetooth audio: shows whether the iPhone keeps A2DP/HFP with the car now and once CarPlay runs. */
    private fun logCarBluetoothAudio() {
        appendLog("Car Bluetooth audio: on; CarPlay audio not offered")
        val adapter = runCatching { getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter }.getOrNull()
            ?: return appendLog("Car Bluetooth audio: no Bluetooth adapter")
        fun probe(moment: String) {
            for ((name, profile) in listOf("A2DP" to android.bluetooth.BluetoothProfile.A2DP,
                    "HFP" to android.bluetooth.BluetoothProfile.HEADSET)) {
                runCatching {
                    adapter.getProfileProxy(applicationContext, object : android.bluetooth.BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(id: Int, proxy: android.bluetooth.BluetoothProfile) {
                            val devices = runCatching { proxy.connectedDevices.map { it.name ?: "?" } }
                                .getOrElse { listOf("unreadable:${it.javaClass.simpleName}") }
                            appendLog("Car Bluetooth audio: $moment $name connected=$devices")
                            adapter.closeProfileProxy(id, proxy)
                        }
                        override fun onServiceDisconnected(id: Int) = Unit
                    }, profile)
                }.onFailure { appendLog("Car Bluetooth audio: $moment $name unavailable ${it.javaClass.simpleName}") }
            }
        }
        probe("start")
        mainHandler.postDelayed({ if (!isDestroyed) probe("after30s") }, 30_000L)
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        // Shown in CarPlay's app list as the "back to the car" button.
        resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) getString(R.string.custom_1_1_icon) else getString(R.string.default_placeholder_icon)
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return getString(R.string.safe_area_waiting_for_activity_size)
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "${getString(R.string.safe_area_full_screen_at)}${size.width} x ${size.height}"
        } else {
            getString(
                R.string.safe_area_mapping_summary,
                mapping.width, mapping.height, mapping.left, mapping.top, size.width, size.height,
            )
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        rememberSafeAreaBeforeEdit(size)
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        rememberSafeAreaBeforeEdit(size)
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun rememberSafeAreaBeforeEdit(size: DisplaySize) {
        val baseline = settingsBaseline ?: return
        if (!baseline.safeAreaRects.containsKey(size)) {
            baseline.safeAreaRects[size] = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        }
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink {
        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            audioFocusAutoYield = AirPlayPersistence.loadAudioFocusAutoYield(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            context = this,
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
            callEchoCancellation = AirPlayPersistence.loadCallEchoCancellation(this),
            callVoiceFilter = AirPlayPersistence.loadCallVoiceFilter(this),
            // Only a SurfaceView honours release timestamps; smooth video always selects one.
            videoPacingDelayMillis = if (smoothVideo) smoothVideoDelayMillis(fps) else 0,
            mainVideoFrameRate = fps,
        )
    }

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog

            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    reconnectAttempts = 0
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    // A monitor result may arrive after the stream geometry was requested.
                    applyClusterTurnOverlay()
                    if (AirPlayPersistence.loadClusterSmallWindowMode(this@CarPlayHostActivity) == CLUSTER_SMALL_WINDOW_AUTO &&
                        requestedSmallWindowForStream != null &&
                        requestedSmallWindowForStream != smallWindowActive()) {
                        if (menuOpen) recoveryPendingAfterMenu = true
                        else reconnectAfterLoss("Cluster small-window navi ${if (smallWindowActive()) "on" else "off"}")
                    }
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    if (!startupRetryBudget.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread
                    mainHandler.postDelayed({
                        if (controllerGeneration == restartGeneration && activeAirPlaySession === session &&
                            !shuttingDown.get() && CarPlayBackgroundSession.isOwner(this@CarPlayHostActivity) &&
                            startupRetryBudget.resetIfStable(session, android.os.SystemClock.elapsedRealtime())) {
                            appendLog("wireless startup retry budget reset after stable video session")
                        }
                    }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    val active = activeAirPlaySession
                    if (active != null && active !== session) {
                        // 探针或非活动握手结束时，保留当前播放会话。
                        appendLog("Non-active AirPlay connection ended; keeping the live session")
                        return@runOnUiThread
                    }
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    ClusterActivityOutput.setStreamActive(false)
                    setConnectionStage(getString(R.string.carplay_session_ended_reconnecting))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration || startupRetryStopped) return@runOnUiThread
                    startupRetryBudget.disconnected()
                    if (menuOpen) {
                        recoveryPendingAfterMenu = true
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    ClusterActivityOutput.setStreamActive(false)
                    setConnectionStage(getString(R.string.transport_error_reconnecting))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                if (DiagnosticRedactor.redact(message) == null) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    // Retain old-controller teardown evidence without accepting its UI/session state.
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(this@CarPlayHostActivity, displayDiagnosticAttempt, message)
                    if (menuOpen) return@runOnUiThread
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = report@{ status ->
        if (controllerGeneration != restartGeneration) return@report
        if (menuOpen) {
            if (status is CarPlayStatus.Failed) failurePendingAfterMenu = status
            return@report
        }
        updateHotspotStatus(status)
        val description = status.describe()
        setConnectionStage(description)
        stageHintView?.text = connectionStageHint(status)
        when (status) {
            is CarPlayStatus.Failed -> if (status.wifiResetRequired) {
                wifiRecoveryButton?.visibility = View.VISIBLE
            } else {
                wifiRecoveryButton?.visibility = View.GONE
                if (status.startupFailure != null) {
                    if (startupFailureGeneration == controllerGeneration) return@report
                    startupFailureGeneration = controllerGeneration
                }
                reconnectAfterLoss(description, status.startupFailure)
            }
            else -> Unit
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        // The sink's pacing is fixed when it is built; a session from before a Smooth video change (for
        // example one whose reconnect stopped at a prerequisite) is stopped instead of adopted. Callers
        // retry, and the next start matches this view.
        if (!backgroundSessionMatchesView(snapshot.sink.videoPacingEnabled, smoothVideo)) {
            appendLog("Background session smooth video=${snapshot.sink.videoPacingEnabled} differs from this view; " +
                "stopping it instead of adopting it")
            CarPlayBackgroundSession.stop { mainHandler.post { if (!isDestroyed) maybeStartCarPlay() } }
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        adbClusterConfigured = AdbClusterRouter.enabled(this) && snapshot.controller.configuredClusterSize() ==
            (DiLink4ClusterDisplay.STREAM_WIDTH to DiLink4ClusterDisplay.STREAM_HEIGHT)
        controller = snapshot.controller
        requestedSmallWindowForStream = snapshot.requestedSmallWindowForStream
        updateClusterMapShown()
        sink = snapshot.sink
        sessionDisplay = snapshot.display
        resetSidePanel()
        if (snapshot.display.viewAreas?.let { it.kindOf(it.current) == CarPlayViewAreas.Kind.SIDE_PANEL } == true) {
            // The live stream can outlast its Activity. Restore the Android view covering its
            // unused strip instead of leaving a blank third when a new host adopts the session.
            sidePanelShown = true
            sidePanel?.visibility = View.VISIBLE
            sidePanelTick.run()
        }
        MapMirrors.reapply()
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height,
            this, snapshot.display, requestedSmallWindowForStream) { completion ->
            runOnUiThread {
                shutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.let { updateVideoLayout(it.width, it.height) }
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
        }

        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.carplay_service_already_running)
            } else {
                getString(R.string.carplay_session_already_running)
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        val config = createRuntimeConfig()
        val effectiveSize = if (isMultiWindowActive() && !AirPlayPersistence.loadAdaptPipResolution(this) &&
            maximumDetectedWidthPixels >= size.width && maximumDetectedHeightPixels >= size.height &&
            (maximumDetectedWidthPixels > size.width || maximumDetectedHeightPixels > size.height)) {
            DisplaySize(maximumDetectedWidthPixels, maximumDetectedHeightPixels)
        } else {
            size
        }
        val airPlayConfig = createAirPlayConfig(effectiveSize)
        val locationProvider: Iap2LocationProvider? =
            when {
                !config.locationReportingEnabled -> null
                config.identification.vehicleSpeedEnabled -> VehicleSpeedLocationProvider(
                    AndroidCarPlayLocationProvider(this),
                    com.shilapi.xcertplay.hud.BydNavigationOutputs.wheelSpeed(applicationContext),
                )
                else -> AndroidCarPlayLocationProvider(this)
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${displayScalePercent}%) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"}" +
                "${if (config.identification.vehicleSpeedEnabled) "+wheel-speed" else ""} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${displayScalePercent}% " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        clusterSurface?.let { renderer.setSurface(SCREEN_TYPE_ALT, it) }
        MapMirrors.reapply()
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
            vehicleStatusProvider = if (com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this)) {
                com.shilapi.xcertplay.hud.BydNavigationOutputs.batteryStatus(applicationContext)
            } else {
                null
            },
        )
        controller = next
        resetSidePanel() // a new session starts without the side panel
        updateClusterMapShown()
        CarPlayMediaKeys.attach(this, next)
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, effectiveSize.width, effectiveSize.height,
            viewAreas = pendingViewAreas,
        )
        sessionDisplay = display
        videoView?.let { updateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display,
            requestedSmallWindowForStream) { completion ->
            runOnUiThread {
                shutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, DiPlaySessionService::class.java))
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            shutdown(false, "foreground service could not start")
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        // resources.configuration is mutated in place, so keep a copy to compare against.
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    private fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    private fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        val session = activeAirPlaySession
        val night = darkMode
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                // A successful write does not prove the iPhone changed its appearance.
                appendLog(
                    "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                )
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                val failureClass = error.javaClass.simpleName.take(64)
                    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
                    .ifEmpty { "unknown" }
                appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=$failureClass")
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        // With a turning screen's square canvas nothing reconnects, so the turn need not wait for the size to settle.
        mainHandler.postDelayed(applyDisplaySize,
            if (sessionDisplay?.viewAreas?.turnsWithScreen == true) 0L else DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    private fun isMultiWindowActive(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode
    }


    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        if (display != null && applyViewArea(size, display)) return
        val layoutChanged = displayLayoutChanged(size)
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight) {
            // Keep camera shrink/restore cycles within the original window connected. If the
            // session started in a camera window, growth beyond it needs a full-size canvas.
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            Log.i(TAG, message)
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    /**
     * Entering or leaving the head unit's split screen, or turning the screen with a square canvas, moves
     * CarPlay to the matching view area instead of reconnecting. The split window is remembered per screen
     * orientation for the next session's area. False when the change is something else (a camera window,
     * a turn without a square canvas), which the usual path handles.
     */
    private fun applyViewArea(size: DisplaySize, display: CarPlaySessionDisplay): Boolean {
        val areas = display.viewAreas ?: return false
        if (controller == null || menuOpen || handshakeResetInProgress || shuttingDown.get()) return false
        val split = isMultiWindowActive()
        // A full window's own shape tells the screen's orientation; during a turn the display metrics and
        // rotation can lag behind the window. A split-screen window can have either shape, so it asks the
        // display, which is settled then.
        val portrait = if (split) screenPortrait() else size.height > size.width
        val previous = activeDisplaySize
        if (areas.turnsWithScreen && display.rotation != displayRotation() && size == previous) {
            // The turn arrived before the window's new size: note it and wait for the size, which picks
            // the area, instead of letting the old rotation look like a changed layout.
            sessionDisplay = display.copy(rotation = displayRotation())
            Log.i(TAG, "Screen turned before the window resized (${size.width}x${size.height}); waiting for its size")
            return true
        }
        val turned = previous != null && !split && (previous.height > previous.width) != portrait
        if (turned && !areas.turnsWithScreen) return false
        if (split) {
            // Against the whole screen, which the system bars (shown in BYD's split screen) do not change.
            val screen = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            val screenLong = maxOf(screen.widthPixels, screen.heightPixels).toFloat()
            val screenShort = minOf(screen.widthPixels, screen.heightPixels).toFloat()
            if (screenLong > 0 && screenShort > 0) {
                if (portrait) SplitScreenSettings.saveWindow(this, true, size.width / screenShort, size.height / screenLong)
                else SplitScreenSettings.saveWindow(this, false, size.width / screenLong, size.height / screenShort)
            }
        }
        val target = areas.indexFor(size.width, size.height, split, portrait) ?: return false
        if (sidePanelShown) resetSidePanel() // a turn or the split screen ends the side panel
        // The same whole-screen area without a turn (a camera window, say) keeps the usual handling.
        if (!turned && target == areas.current && areas.kindOf(target) == CarPlayViewAreas.Kind.FULL_SCREEN) return false
        activeDisplaySize = size
        sessionDisplay = display.copy(rotation = displayRotation())
        val sent = if (target == areas.current) true else controller?.showViewArea(target) == true
        if (sent) areas.use(target)
        videoView?.let { updateVideoLayout(it.width, it.height) }
        val message = "Window ${previous?.width}x${previous?.height} -> ${size.width}x${size.height} " +
            "split=$split portrait=$portrait turned=$turned: view area $target sent=$sent"
        appendLog(message)
        Log.i(TAG, message)
        return true
    }

    /** Whether the screen itself is portrait now (DiPlay's own window may be a split-screen part of it). */
    @Suppress("DEPRECATION")
    private fun screenPortrait(): Boolean {
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels > metrics.widthPixels
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(newSize: DisplaySize? = activeDisplaySize): Boolean {
        val display = sessionDisplay ?: return false
        if (display.rotation != displayRotation()) return true
        if (display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar) return true
        if (newSize != null && newSize.width > 0 && newSize.height > 0 &&
            display.windowWidth > 0 && display.windowHeight > 0) {
            // The negotiated canvas may be square to support screen rotation; compare the
            // actual startup window instead, or every return to a landscape window looks like PiP.
            val baseAspect = display.windowWidth.toDouble() / display.windowHeight
            val currentAspect = newSize.width.toDouble() / newSize.height
            val aspectDiff = kotlin.math.abs(currentAspect / baseAspect - 1.0)
            if (aspectDiff > 0.08 && AirPlayPersistence.loadAdaptPipResolution(this)) {
                return true
            }
        }
        return false
    }



    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = sessionDisplay ?: return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // In the head unit's split screen, or on a turning screen's square canvas, CarPlay draws in part of
        // the canvas; that area fills the window and the rest falls outside it. Video and touches both
        // follow this. An area of the whole canvas keeps the usual fit.
        display.viewAreas?.let { areas ->
            val area = areas.layoutArea(areas.current)
            if (area.width != display.width || area.height != display.height) {
                val scaleX = viewWidth.toFloat() / area.width
                val scaleY = viewHeight.toFloat() / area.height
                return CarPlayVideoLayout(-area.originX * scaleX, -area.originY * scaleY,
                    display.width * scaleX, display.height * scaleY)
            }
        }
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        val fallback = fallbackVideoView
        if (fallback != null) {
            val bounds = CarPlaySurfaceBounds.from(content)
            if (fallbackVideoBounds != bounds) {
                fallbackVideoBounds = bounds
                fallback.layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height,
                    Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = bounds.left
                    topMargin = bounds.top
                }
            }
        } else {
            (view as? TextureView)?.setTransform(Matrix().apply {
                setScale(content.width / viewWidth, content.height / viewHeight)
                postTranslate(content.left, content.top)
            })
        }
        if (sidePanelShown) placeSidePanel(viewWidth, viewHeight)
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (shuttingDown.get()) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adoptBackgroundSession()) mainHandler.postDelayed({ maybeStartCarPlay() }, 500)
            return
        }
        if (controller == null && adoptBackgroundSession()) return
        // The video view is chosen once per activity; a changed Smooth video setting needs a new one.
        if (controller == null && AirPlayPersistence.loadSmoothVideo(this) != smoothVideo) {
            appendLog("Smooth video setting changed; rebuilding the video view")
            // No session runs here, but a restart keeps this host as the session owner; the new instance
            // must be able to start its own.
            if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.clear()
            recreate()
            return
        }
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            handshakeResetInProgress ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    private fun reconnectAfterLoss(reason: String, startupFailure: WirelessStartupFailure? = null) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (menuOpen) recoveryPendingAfterMenu = true
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress || startupRetryStopped) return
        if (reconnectScheduled) return
        val startupDelay = if (startupFailure != null && startupFailure != WirelessStartupFailure.HOTSPOT_CONFIGURATION)
            startupRetryBudget.nextDelayMillis() else null
        if (startupFailure != null && startupDelay == null) {
            startupRetryStopped = true
            startupRetryButton?.visibility = View.VISIBLE
            setConnectionStage(if (startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION) reason
                else "$reason\n${getString(R.string.wireless_startup_retries_exhausted)}")
            appendLog("wireless startup recovery stopped generation=$restartGeneration reason=$startupFailure retries=${startupRetryBudget.retries}")
            return
        }
        reconnectScheduled = true
        val generation = restartGeneration
        val delayMillis = if (startupDelay != null) {
            startupDelay
        } else if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms generation=$generation startupFailure=$startupFailure startupRetries=${startupRetryBudget.retries}")
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (reason.startsWith("Cluster small-window navi") && CarPlayBackgroundSession.active &&
                    requestedSmallWindowForStream == smallWindowActive()) return@postDelayed
                if (menuOpen && generation == restartGeneration) recoveryPendingAfterMenu = true
                if (
                    shuttingDown.get() ||
                    menuOpen ||
                    handshakeResetInProgress || startupRetryStopped ||
                    generation != restartGeneration
                ) {
                    return@postDelayed
                }
                restartCarPlay("Reconnecting after $reason")
            },
            delayMillis,
        )
    }

    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) {
        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || menuOpen || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        startupRetryBudget.disconnected()
        startupRetryButton?.visibility = View.GONE
        appendLog(reason)
        activeScreenStreamTypes.clear()
        ClusterActivityOutput.setStreamActive(false)
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = ++restartGeneration
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        val diagnosticLog = sessionLog
        teardownExecutor.execute {
            val started = System.nanoTime()
            oldController?.close()
            val completed = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            AsyncDiagnosticLog.append(
                diagnosticLog,
                "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} generation=$generation " +
                    "restart teardownWaitCompleted=$completed " +
                    "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}",
            )
            oldSink?.let(::closeRetiringSink)
            runOnUiThread {
                if (!shuttingDown.get() && generation == restartGeneration) {
                    handshakeResetInProgress = false
                    // The display can rotate again while the old stack is closing. Use the
                    // latest accepted size, and wait for any pending resize to settle first.
                    maybeStartCarPlay()
                }
            }
        }
    }

    private fun showDiPlayHome(page: String = "home") {
        controller?.sendTouch(emptyList())
        startActivity(Intent(this, DiPlayActivity::class.java)
            .putExtra("page", page).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** Keep a timed-out sink visible to surface teardown until its codecs have actually been released. */
    private fun closeRetiringSink(owner: AndroidMediaSink) {
        owner.close()
        val owners = retiringSinks
        owner.whenVideoReleased { owners.remove(owner) }
        // Preserve bounded teardown sequencing without blocking the UI or forgetting a live worker.
        owner.awaitVideoReleased(sinkReleaseWaitMillis)
    }

    private fun openSettingsMenu() {
        if (menuOpen) return
        controller?.sendTouch(emptyList())
        loadPersistedSettings()
        settingsBaseline = captureSettingsBaseline()
        // Rebuild controls from saved values so a cancelled edit cannot reappear on reopening.
        settingsMenu?.let { previous ->
            val parent = previous.parent as ViewGroup
            val index = parent.indexOfChild(previous)
            parent.removeView(previous)
            settingsMenu = buildSettingsMenu()
            parent.addView(settingsMenu, index, FrameLayout.LayoutParams(-1, -1))
        }
        menuOpen = true
        gestureOverlay?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        safeAreaEditor?.visibility = View.GONE
        updateSafeAreaSummary()
        updateResolutionMenu()
        updateDebugOverlays()
    }

    private fun openPicturePanel() {
        closePicturePanel()
        val generation = picturePanelGeneration
        val root = videoView?.parent as? FrameLayout ?: return
        controller?.sendTouch(emptyList())
        root.post {
            if (generation != picturePanelGeneration || isFinishing || isDestroyed) return@post
            val panel = CarPlayPicturePanel(
                this,
                adjustmentsAvailable = fallbackVideoView == null,
                initialPalette = overlayPalette,
                close = ::closePicturePanel,
            )
            val availableWidth = (root.width - dp(24)).coerceAtLeast(1)
            val width = minOf(dp(420), if (root.width < dp(800)) availableWidth else (root.width * 0.42f).toInt())
            val height = minOf(dp(540), root.height - dp(24)).coerceAtLeast(1)
            root.addView(panel, FrameLayout.LayoutParams(width, height, Gravity.END or Gravity.TOP).apply {
                topMargin = dp(12); marginEnd = dp(12)
            })
            picturePanel = panel
        }
    }

    private fun closePicturePanel() {
        ++picturePanelGeneration
        picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        picturePanel = null
        CarPlayPicture.showOriginal(false)
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        persistMenuSettings()
        settingsBaseline = null
        finishSettingsMenu("Settings saved", reconnect = true)
    }

    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        finishSettingsMenu("Settings changes discarded", reconnect = false)
    }

    private fun finishSettingsMenu(prefix: String, reconnect: Boolean) {
        if (!menuOpen) return
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        settingsGestureHint?.text = getString(R.string.open_diplay_settings_hint, gestureFingerCount)
        updateDebugOverlays()
        logLines.clear()
        appendLog(
            "$prefix; resolution " +
                "${displayScalePercent}% with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        val failure = failurePendingAfterMenu
        failurePendingAfterMenu = null
        val recoveryPending = recoveryPendingAfterMenu || failure != null
        recoveryPendingAfterMenu = false
        if (reconnect) {
            startupRetryBudget.manualRetry()
            startupRetryStopped = false
        }
        if (failure?.startupFailure != null && !reconnect) {
            createStatusReporter(restartGeneration)(failure)
        } else if (failure?.wifiResetRequired == true && (!reconnect || wirelessEnabled)) {
            createStatusReporter(restartGeneration)(failure)
        } else if (handshakeResetInProgress) {
            startAfterHandshakeReset = true
        } else if ((reconnect || recoveryPending) && controller != null) {
            restartCarPlay(if (reconnect) "Settings saved; reconnecting" else "Connection lost while settings were open; reconnecting")
        } else {
            maybeStartCarPlay()
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        resetSidePanel()
        startupRetryBudget.disconnected()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.let(::closeRetiringSink)
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun observeVideoWindow(texture: TextureView) {
        val probe = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!texture.isAttachedToWindow) return true
                removeVideoSurfaceProbe()
                if (isDestroyed || videoView !== texture) return true
                val mode = carPlayVideoSurfaceMode(texture.isHardwareAccelerated, smoothVideo)
                appendLog("Video output mode=$mode windowHardwareAccelerated=${texture.isHardwareAccelerated} " +
                    "smoothVideo=$smoothVideo")
                if (mode == CarPlayVideoSurfaceMode.TEXTURE) return true
                useFallbackVideoSurface(texture)
                return false // Measure the replacement before drawing the software window.
            }
        }
        videoSurfaceProbe = probe
        texture.viewTreeObserver.addOnPreDrawListener(probe)
    }

    private fun removeVideoSurfaceProbe() {
        val probe = videoSurfaceProbe ?: return
        videoView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe)
        videoSurfaceProbe = null
    }

    private fun useFallbackVideoSurface(texture: TextureView) {
        val root = texture.parent as? FrameLayout ?: return
        val index = root.indexOfChild(texture)
        val viewport = FrameLayout(this).apply { clipChildren = true }
        val surfaceView = SurfaceView(this)
        pictureBinding?.close()
        pictureBinding = null
        // Detach our wrapper before removing its TextureView; a late destruction callback
        // must not clear the framework-owned replacement surface.
        videoSurfaceOwner.clear()
        currentSurfaceTexture = null
        texture.surfaceTextureListener = null
        root.removeView(texture)
        videoView = viewport
        fallbackVideoView = surfaceView
        surfaceView.holder.addCallback(fallbackSurfaceCallback)
        viewport.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        viewport.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            updateVideoLayout(width, height)
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                scheduleDisplaySize(width, height)
            }
        }
        root.addView(viewport, index, texture.layoutParams)
        appendLog(if (smoothVideo) {
            "Using SurfaceView video output: smooth video, frames shown at the iPhone's frame time + a delay " +
                "starting at ${smoothVideoDelayMillis(fps)} ms; picture adjustments unavailable"
        } else {
            "Using SurfaceView video output: window has no hardware acceleration; picture adjustments unavailable"
        })
    }

    /** A dimension of the platform's own resources in pixels, or 0 when this build has none by [name]. */
    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        if (AirPlayPersistence.loadClusterMapEnabled(this)) {
            clusterSurface?.let { sink?.setSurface(SCREEN_TYPE_ALT, it) }
        } else {
            sink?.setSurface(SCREEN_TYPE_ALT, surface)
        }
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == gestureFingerCount && !gestureSequenceActive) {
                    gestureSequenceActive = true
                    gestureTracking = true
                    gestureStartX = pointerCentroid(event, horizontal = true)
                    gestureStartY = pointerCentroid(event, horizontal = false)
                    controller?.sendTouch(emptyList())
                    appendLog("Settings swipe tracking started; fingers=$gestureFingerCount")
                    return true
                }
            }
        }

        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != gestureFingerCount) {
                gestureTracking = false
            }
            if (!gestureTracking || event.pointerCount != gestureFingerCount) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                if (
                    deltaY >= dp(SETTINGS_SWIPE_DISTANCE_DP) &&
                    deltaY >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                    if (AirPlayPersistence.loadSwipeOpensFullSettings(this)) {
                        showDiPlayHome("settings")
                    } else {
                        openSettingsMenu()
                    }
                    return true
                }
            }
            return true
        }

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOutsideContent = !content.contains(event.x, event.y)
        }
        if (touchOutsideContent) {
            // Ignore the entire touch sequence when it starts in a letterbox bar.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchOutsideContent = false
            }
            return true
        }
        val contacts = CarPlayTouchMapper.contacts(event, content)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            if (type == SCREEN_TYPE_ALT) {
                Log.i(ClusterMapPresentation.TAG, "cluster stream active=$active")
                appendLog("Cluster map: stream active=$active")
                clusterPresentation?.setStreamActive(active)
                ClusterActivityOutput.setStreamActive(active)
                MapMirrors.setStreamActive(active)
                if (active) {
                    mainHandler.removeCallbacks(hideIdleCenterMap)
                    if (!CenterMapOverlay.shown) CenterMapOverlay.scheduleShow()
                } else {
                    mainHandler.postDelayed(hideIdleCenterMap, CENTER_MAP_IDLE_MILLIS)
                }
            }
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = friendlyStage(message)
        stageHintView?.text = getString(R.string.connection_stage_diagnostic_hint)
        updateDebugOverlays()
    }

    private fun connectionStageHint(status: CarPlayStatus): String = getString(when (status) {
        CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.ConnectingBluetooth ->
            R.string.connection_stage_bluetooth_hint
        CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady,
        CarPlayStatus.AttachingNetwork -> R.string.connection_stage_wifi_hint
        CarPlayStatus.RunningWireless, CarPlayStatus.WirelessActive,
        CarPlayStatus.WirelessActiveFallback, CarPlayStatus.ConnectingControl,
        CarPlayStatus.RunningControl -> R.string.connection_stage_session_hint
        CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForIphone,
        CarPlayStatus.RequestingIphonePermission, CarPlayStatus.WaitingForReenumeration ->
            R.string.connection_stage_usb_hint
        is CarPlayStatus.Failed, CarPlayStatus.ControlEnded -> R.string.connection_stage_failed_hint
        else -> R.string.connection_stage_diagnostic_hint
    })

    private fun updateDebugOverlays() {
        statusScrollView?.visibility = View.GONE
        connectionPanel?.visibility = if (activeScreenStreamTypes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun friendlyStage(message: String): String = when {
        message == getString(R.string.waiting_for_mfi_coprocessor) ||
            message == getString(R.string.requesting_mfi_usb_permission) -> message
        message.contains("Turn on Wi-Fi", true) -> getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        message.contains("Allow precise Location", true) -> getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        message.contains("Allow Nearby devices", true) -> getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        message.contains("createGroup failed", true) -> getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        message.contains("needs a reset", true) -> getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        message.contains("socket", true) || message.contains("RFCOMM", true) -> getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        message.contains("unsupported", true) || message.contains("not supported", true) -> getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        message.contains("denied", true) || message.contains("permission", true) -> getString(R.string.allow_the_connection_permission_to_continue)
        message.contains("Failed", true) || message.contains("error", true) -> getString(R.string.connection_interrupted_retrying)
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) -> getString(R.string.connect_your_iphone_with_a_usb_cable)
        message.contains("paired", true) -> getString(R.string.looking_for_your_paired_iphone)
        message.contains("Bluetooth", true) -> getString(R.string.connecting_to_your_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) -> getString(R.string.reconnecting_to_your_iphone)
        message.contains("active", true) || message.contains("running", true) -> getString(R.string.opening_carplay)
        else -> getString(R.string.getting_carplay_ready)
    }

    private fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    private fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
    }

    private fun refreshLogView(nowMillis: Long) {
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        while (logLines.firstOrNull()?.timestampMillis?.let { it <= cutoff } == true) {
            logLines.removeFirst()
        }
        statusView?.text = logLines.joinToString("\n") { it.text }
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstOrNull()?.let { oldest ->
            val delay = (oldest.timestampMillis + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun applyFullscreenMode() {
        val multiWindow = isMultiWindowActive()
        val hideTop = hideTopBar && !multiWindow
        val hideBottom = hideBottomBar && !multiWindow
        WindowCompat.setDecorFitsSystemWindows(window, !(hideTop && hideBottom))
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideTop) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> getString(if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI)
            R.string.existing_wifi_attaching else R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                getString(R.string.existing_wifi_ready, ssid, band, channel, address)
            } else getString(R.string.status_hotspot_ready, backend, ssid, band, if (channel == 0) getString(R.string.auto_value) else channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) getString(R.string.starting_airplay_service) else getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> when (startupFailure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> getString(R.string.hotspot_network_not_ready)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> getString(R.string.first_tcp_timeout)
            else -> getString(R.string.status_failed, message)
        }
    }

    private companion object {
        const val CLUSTER_SMALL_WINDOW_ON = 1
        const val CLUSTER_SMALL_WINDOW_AUTO = 2
        const val SIDE_PANEL_REFRESH_MILLIS = 5_000L
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        // On the teardown thread: bounded sequencing wait. Release notification retains ownership beyond
        // this budget when a codec is still busy.
        const val SINK_RELEASE_WAIT_MILLIS = 2_000L
        private const val CENTER_MAP_IDLE_MILLIS = 3_000L // a reconnect is quicker; a session end is not
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val SETTINGS_SWIPE_DISTANCE_DP = 72
        const val SETTINGS_SWIPE_DIRECTION_RATIO = 1.15f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class LogEntry(val timestampMillis: Long, val text: String)
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )
}

internal data class CarPlaySessionDisplay(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val hideTopBar: Boolean,
    val hideBottomBar: Boolean,
    // Compare unscaled startup window dimensions, not the scaled video canvas.
    val windowWidth: Int,
    val windowHeight: Int,
    /** The view areas this session declared, or null for the whole screen as one area. */
    val viewAreas: CarPlayViewAreas? = null,
)

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
internal object CarPlayBackgroundSession {
    @Volatile var active = false
    private var stopAction: (((() -> Unit)) -> Unit)? = null
    private var stopping = false
    private var owner: Any? = null
    @Synchronized fun isOwner(candidate: Any): Boolean = owner === candidate
    @Synchronized fun hasSession(): Boolean = stopAction != null || stopping
    private val stopWaiters = mutableListOf<() -> Unit>()

    fun stop(completion: () -> Unit = {}) {
        val action: (((() -> Unit)) -> Unit)?
        synchronized(this) {
            if (stopping) { stopWaiters.add(completion); return }
            action = stopAction
            if (action != null) { stopping = true; stopWaiters.add(completion) }
        }
        if (action == null) { completion(); return }
        action.invoke {
            val callbacks = synchronized(this) {
                stopping = false
                stopWaiters.toList().also { stopWaiters.clear() }
            }
            callbacks.forEach { it() }
        }
    }

    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
        val display: CarPlaySessionDisplay,
        val requestedSmallWindowForStream: Boolean?,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0
    private var display: CarPlaySessionDisplay? = null
    private var requestedSmallWindowForStream: Boolean? = null

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        val currentDisplay = display ?: return null
        return Snapshot(currentController, currentSink, width, height, currentDisplay, requestedSmallWindowForStream)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, stop: (() -> Unit) -> Unit) {
        store(controller, sink, width, height, owner, display, null, stop)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int,
        owner: Any, display: CarPlaySessionDisplay, requestedSmallWindowForStream: Boolean?,
        stop: (() -> Unit) -> Unit) {
        this.stopAction = stop
        this.owner = owner
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
        this.display = display
        this.requestedSmallWindowForStream = requestedSmallWindowForStream
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null, keepOwner: Boolean = false) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        if (!keepOwner) { stopAction = null; owner = null }
        active = false
        width = 0
        height = 0
        display = null
        requestedSmallWindowForStream = null
    }
}
