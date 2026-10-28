package com.shilapi.xcertplay.airplay

/**
 * Builds the /info response the phone reads before it requests media streams.
 *
 * The declaration is complete on purpose: it must carry the display, audio formats/latencies,
 * CarPlay resource modes, and the HID input devices, otherwise the phone aborts the session.
 */
object AirPlayInfoPlist {
    const val MAIN_UUID = "b7e6c5a0-1111-4000-8000-000000000001"
    const val ALT_UUID = "b7e6c5a0-2222-4000-8000-000000000002"

    private const val STREAM_TYPE_MAIN_SCREEN = 110
    private const val STREAM_TYPE_ALT_SCREEN = 111
    private const val DISPLAY_FEATURE_KNOBS = 0x02
    private const val DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH = 0x08
    private const val CARPLAY_FEATURES = 0x615653aee2L
    private const val CARPLAY_AUDIO_FEATURES = 0x10004540a00L
    private val CARPLAY_FEATURES_NO_AUDIO = CARPLAY_FEATURES and CARPLAY_AUDIO_FEATURES.inv()

    private const val RESOURCE_SCREEN = 1
    private const val RESOURCE_AUDIO = 2
    private const val TRANSFER_TAKE = 1
    private const val PRIORITY_NICE_TO_HAVE = 100
    private const val CONSTRAINT_ANYTIME = 100

    /** Discovery and /info must describe the same receiver capabilities. */
    fun features(config: AirPlayConfig): Long =
        if (config.disableAudioOutput) CARPLAY_FEATURES_NO_AUDIO else CARPLAY_FEATURES

    fun build(config: AirPlayConfig): Map<String, Any?> {
        val displays = arrayListOf<Any?>(
            displayEntry(config.main, STREAM_TYPE_MAIN_SCREEN, MAIN_UUID),
        )
        config.cluster?.let { displays.add(displayEntry(it, STREAM_TYPE_ALT_SCREEN, ALT_UUID)) }

        val info = linkedMapOf<String, Any?>(
            "sourceVersion" to config.sourceVersion,
            "features" to features(config),
            "statusFlags" to 4L,
            "model" to config.model,
            "manufacturer" to config.manufacturer,
            "deviceID" to config.deviceId,
            "bluetoothIDs" to listOf(config.btMac),
            "name" to config.deviceName,
            "rightHandDrive" to config.rightHandDrive,
            "keepAliveLowPower" to false,
            "keepAliveSendStatsAsBody" to false,
            "modes" to modes(),
        )
        if (!config.disableAudioOutput) {
            info["audioLatencies"] = audioLatencies()
            info["audioFormats"] = audioFormats(config.entertainmentSampleRate, config.microphone, config.mainBufferedAudio, config.microphoneOpus,
                config.speechWithout8And16Khz, config.callAtCallRate)
        }
        info["extendedFeatures"] = listOf("vocoderInfo", "enhancedRequestCarUI")
        info["displays"] = displays
        info["hidDevices"] = listOf(
            AirPlayHid.touchHidDevice(config.main.widthPixels, config.main.heightPixels, MAIN_UUID),
            AirPlayHid.knobHidDevice(MAIN_UUID),
            AirPlayHid.mediaHidDevice(MAIN_UUID),
            AirPlayHid.telephonyHidDevice(MAIN_UUID),
        )
        if (config.icons.isNotEmpty()) {
            info["oemIconVisible"] = true
            info["oemIconLabel"] = config.oemLabel
            info["oemIcons"] = config.icons.map { icon ->
                linkedMapOf(
                    "imageData" to icon.data,
                    "widthPixels" to icon.widthPixels,
                    "heightPixels" to icon.heightPixels,
                    "prerendered" to true,
                )
            }
        }
        if (config.hevc) info["hevcInfo"] = emptyMap<String, Any?>()
        // With the mainBuffered session feature the iPhone requires this key; an empty dictionary is accepted.
        if (config.bufferedAudioOutputEnabled) info["mainBufferedInfo"] = emptyMap<String, Any?>()
        if (config.videoInCar) {
            // The iPhone tears down a session that enables videoPlayback without this key.
            val legacy = features(config)
            info["videoPlaybackInfo"] = VideoInCar.info(legacy, VideoInCar.allowed)
        }
        return info
    }

    private fun resource(resourceId: Int): Map<String, Any?> = linkedMapOf(
        "resourceID" to resourceId,
        "transferType" to TRANSFER_TAKE,
        "transferPriority" to PRIORITY_NICE_TO_HAVE,
        "takeConstraint" to CONSTRAINT_ANYTIME,
        "borrowConstraint" to CONSTRAINT_ANYTIME,
        "unborrowConstraint" to CONSTRAINT_ANYTIME,
    )

    private fun modes(): Map<String, Any?> = linkedMapOf(
        "resources" to listOf(resource(RESOURCE_SCREEN), resource(RESOURCE_AUDIO)),
        "appStates" to listOf(
            linkedMapOf("appStateID" to 2, "state" to false),
            linkedMapOf("appStateID" to 1, "speechMode" to -1),
            linkedMapOf("appStateID" to 3, "state" to false),
        ),
    )

    private fun audioLatencies(): List<Map<String, Any?>> {
        fun base(type: Int, audioType: String? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "inputLatencyMicros" to 0L,
                "outputLatencyMicros" to 0L,
            )
            if (audioType != null) entry["audioType"] = audioType
            return entry
        }
        return listOf(
            base(100), base(100, "default"), base(100, "media"), base(100, "telephony"),
            base(100, "speechRecognition"), base(100, "alert"), base(101), base(101, "default"),
            base(102, "default"),
        )
    }

    private fun audioFormats(
        entertainmentRate: Int,
        microphone: Boolean,
        mainBuffered: Boolean = false,
        microphoneOpus: Boolean = true,
        speechWithout8And16Khz: Boolean = false,
        callAtCallRate: Boolean = false,
    ): List<Map<String, Any?>> {
        fun format(type: Int, audioType: String, outputFormats: Int, inputFormats: Int? = null): Map<String, Any?> {
            val entry = linkedMapOf<String, Any?>(
                "type" to type,
                "audioType" to audioType,
                "audioOutputFormats" to outputFormats,
            )
            if (inputFormats != null) entry["audioInputFormats"] = inputFormats
            return entry
        }

        val is48 = entertainmentRate == 48000
        val pcmVoice = 0x3fc
        val pcm = pcmVoice or (if (is48) 0xc000 else 0xc00)
        // PCM 8 kHz and 16 kHz, mono and stereo.
        val speech = if (speechWithout8And16Khz) pcm and 0x3c.inv() else pcm
        val pcmMono = 0x154 or (if (is48) 0x4000 else 0x400)
        val opus = 0x70000000
        val aacLc = if (is48) 0x800000 else 0x400000
        // Calls at their own rate: PCM mono at the vocoder rates (8, 16, 24, 32 kHz), no Opus and no 44.1/48 kHz.
        val telephonyOutput = if (callAtCallRate) 0x154 else pcmMono or opus
        val pcmInput = if (microphone) pcmMono else null
        // Retain Opus whenever platform or bundled software encoding is usable. PCM-only wireless
        // negotiation is not a universal fallback; a missing MediaCodec encoder is insufficient.
        val wirelessInput = if (microphone) (if (microphoneOpus) pcmMono or opus else pcmMono) else null
        // The iPhone picks one audioFormat for both directions of the call stream, so the call microphone is offered
        // at the same rates; otherwise a 48 kHz microphone offer let it keep choosing 48 kHz.
        val telephonyInput = if (callAtCallRate && microphone) 0x154 else wirelessInput

        return listOf(
            format(100, "compatibility", pcm, pcmInput),
            format(101, "compatibility", pcm),
            format(100, "default", speech or opus, wirelessInput),
            format(100, "alert", speech or opus),
            format(100, "media", pcm),
            format(100, "telephony", telephonyOutput, telephonyInput),
            format(100, "speechRecognition", pcmMono or opus, wirelessInput),
            format(101, "default", speech or opus),
            format(102, "media", aacLc),
        ) + if (mainBuffered) {
            // The buffered music stream; the iPhone (iOS 27) opened it only with AAC-LC, not PCM or ALAC.
            listOf(format(BufferedAudioStream.STREAM_TYPE, "media", aacLc))
        } else emptyList()
    }

    /**
     * viewAreaStatusBarEdge values, as CarPlay Simulator's StatusBarEdge (automatic, bottom, driver) and as
     * seen on a Tang with iOS 27: 1 puts the dock at the bottom, 2 on the driver's side.
     */
    const val DOCK_EDGE_BOTTOM = 1
    const val DOCK_EDGE_DRIVER_SIDE = 2
    private const val VIEW_AREA_ANIMATION_MILLIS = 300

    /**
     * updateViewArea for the main screen. Seen on a Tang with iOS 27: the iPhone acts on it only with an
     * animation duration and the adjacent areas, the arguments CarPlaySDK's ViewAreaUpdate takes.
     */
    fun viewAreaCommand(index: Int, areaCount: Int): Map<String, Any?> = linkedMapOf(
        "type" to "updateViewArea",
        "params" to linkedMapOf(
            "uuid" to MAIN_UUID,
            "viewAreaIndex" to index,
            "animationDurationMillis" to VIEW_AREA_ANIMATION_MILLIS,
            "adjacentViewAreas" to (0 until areaCount).filter { it != index },
        ),
    )

    private fun displayEntry(display: AirPlayDisplayConfig, type: Int, uuid: String): Map<String, Any?> {
        val widthPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.widthPhysicalMm ?: AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM,
        )
        val heightPhysical = AirPlayDisplaySettings.sanitizeReportedPhysicalMm(
            display.heightPhysicalMm
                ?: Math.round(
                    widthPhysical * display.heightPixels.toDouble() / display.widthPixels,
                ).toInt(),
        )
        val fps = AirPlayDisplaySettings.sanitizeFps(display.fps)

        val entry = linkedMapOf<String, Any?>(
            "uuid" to uuid,
            "type" to type,
            "maxFPS" to fps,
            "widthPixels" to display.widthPixels,
            "heightPixels" to display.heightPixels,
            "widthPhysical" to widthPhysical,
            "heightPhysical" to heightPhysical,
            "features" to (display.features ?: (DISPLAY_FEATURE_HIGH_FIDELITY_TOUCH or DISPLAY_FEATURE_KNOBS)),
            "primaryInputDevice" to display.primaryInputDevice,
            // Declare automatic UI/map appearance before runtime setNightMode commands.
            "uiAppearanceMode" to 0,
            "uiAppearanceSetting" to 0,
            "mapAppearanceMode" to 0,
            "mapAppearanceSetting" to 0,
        )

        // Several areas let the car move CarPlay between them (another dock edge, the head unit's split
        // screen) with updateViewArea, without reconnecting.
        val areas = display.viewAreas?.takeIf { it.isNotEmpty() }
        val disableSafeArea = uuid == MAIN_UUID && display.mainDisplayDisableSafeArea
        entry["viewAreas"] = areas?.map { areaDict(display, it, disableSafeArea) } ?: listOf(areaDict(display, null, disableSafeArea))
        entry["initialViewArea"] = if (areas == null) 0 else display.initialViewArea.coerceIn(0, areas.lastIndex)
        if (uuid == MAIN_UUID && display.mainDisplayCornerMasks != null) entry["cornerMasks"] = display.mainDisplayCornerMasks
        if (areas != null && areas.size > 1) entry["viewAreaTransitionControl"] = true
        if (display.initialUrl != null) entry["initialURL"] = display.initialUrl
        return entry
    }

    private fun areaDict(
        display: AirPlayDisplayConfig,
        area: AirPlayViewArea? = null,
        disableSafeArea: Boolean = false,
    ): Map<String, Any?> {
        // The session SETUP response enables "viewAreas", so /info must always describe one.
        // A display without custom insets uses the full panel for both the view and safe areas; an
        // explicit area replaces the display's view insets and clips its safe area.
        val width = display.widthPixels
        val height = display.heightPixels
        val view = area?.let {
            AirPlayInsets(top = it.originY, bottom = height - it.originY - it.height,
                left = it.originX, right = width - it.originX - it.width)
        } ?: display.viewArea ?: AirPlayInsets()
        val result = linkedMapOf<String, Any?>(
            "widthPixels" to (width - view.left - view.right),
            "heightPixels" to (height - view.top - view.bottom),
            "originXPixels" to view.left,
            "originYPixels" to view.top,
        )
        area?.dockEdge?.let { result["viewAreaStatusBarEdge"] = it }
        if (!disableSafeArea) {
            val displaySafe = display.safeArea ?: AirPlayInsets()
            val clipped = if (area == null) displaySafe else AirPlayInsets(
                top = maxOf(displaySafe.top, view.top),
                bottom = maxOf(displaySafe.bottom, view.bottom),
                left = maxOf(displaySafe.left, view.left),
                right = maxOf(displaySafe.right, view.right),
            )
            // A valid saved mapping can lie wholly outside a smaller view area. In that case the
            // intersection is empty: use this area's bounds instead of advertising negative/zero sizes.
            val safe = if (area != null &&
                (clipped.left + clipped.right >= width || clipped.top + clipped.bottom >= height)
            ) view else clipped
            val safeArea = linkedMapOf<String, Any?>(
                "widthPixels" to (width - safe.left - safe.right),
                "heightPixels" to (height - safe.top - safe.bottom),
                "originXPixels" to safe.left,
                "originYPixels" to safe.top,
                "drawUIOutsideSafeArea" to (display.safeAreaDrawOutside ?: true),
            )
            result["safeArea"] = safeArea
        }
        return result
    }
}
