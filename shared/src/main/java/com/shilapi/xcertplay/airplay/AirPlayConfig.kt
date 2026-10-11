package com.shilapi.xcertplay.airplay

/** Display insets in pixels, used for CarPlay viewArea and safeArea declarations. */
data class AirPlayInsets(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0,
)

/** One display advertised to the phone in /info. */
data class AirPlayDisplayConfig(
    val widthPixels: Int,
    val heightPixels: Int,
    val widthPhysicalMm: Int? = null,
    val heightPhysicalMm: Int? = null,
    val fps: Int = 60,
    val primaryInputDevice: Int = 1,
    val viewArea: AirPlayInsets? = null,
    val safeArea: AirPlayInsets? = null,
    val safeAreaDrawOutside: Boolean? = null,
    val initialUrl: String? = null,
    /** Display feature bits; null keeps the main-screen default (high-fidelity touch and knobs). */
    val features: Int? = null,
    /**
     * The view areas CarPlay may use on this display, or null for the whole display as one area. With
     * several, the car moves CarPlay between them with updateViewArea, without reconnecting.
     */
    val viewAreas: List<AirPlayViewArea>? = null,
    /** The area CarPlay starts in, an index into [viewAreas]. */
    val initialViewArea: Int = 0,
    /**
     * When true, CarPlay video stream fills the entire display with right-angled corners.
     * With corner masks advertised, iOS sends an unclipped rectangular video stream alongside
     * corner mask PNG assets for blending. On rectangular vehicle screens, advertising true allows
     * the video stream to fill the entire display as right angles without requiring additional clipping.
     */
    val mainDisplayCornerMasks: Boolean? = null,
    /** When true, do not declare safeArea in viewAreas dictionary to avoid conflict with cornerMasks. */
    val mainDisplayDisableSafeArea: Boolean = (mainDisplayCornerMasks == true),
)

/**
 * A rectangle of the display's stream that CarPlay can draw in, and optionally the edge for its dock
 * ([AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE] or [AirPlayInfoPlist.DOCK_EDGE_BOTTOM]; null leaves it to
 * the iPhone).
 */
data class AirPlayViewArea(
    val width: Int,
    val height: Int,
    val originX: Int = 0,
    val originY: Int = 0,
    val dockEdge: Int? = null,
)

/** One OEM homescreen icon. */
data class AirPlayIcon(
    val widthPixels: Int,
    val heightPixels: Int,
    val data: ByteArray,
)

/** Immutable accessory configuration consumed by the AirPlay session server. */
data class AirPlayConfig(
    val deviceName: String,
    val deviceId: String,
    val btMac: String,
    val sourceVersion: String,
    val main: AirPlayDisplayConfig,
    val cluster: AirPlayDisplayConfig? = null,
    val rightHandDrive: Boolean = false,
    val port: Int = 7000,
    val entertainmentSampleRate: Int = 48000,
    val hevc: Boolean = false,
    val disableAudioOutput: Boolean = false,
    val microphone: Boolean = false,
    /**
     * Whether to offer Opus alongside PCM for the microphone. Both platform and bundled software
     * encoding count toward [com.shilapi.xcertplay.media.OpusEncoderSupport]; an older Android
     * version or absent MediaCodec encoder alone must not remove the wireless Opus formats.
     */
    val microphoneOpus: Boolean = true,
    val manufacturer: String = "xcertplay",
    val model: String = "xcertplay",
    val oemLabel: String = "xcertplay",
    val icons: List<AirPlayIcon> = emptyList(),
    /** iOS 27 video in car (see [VideoInCar]); video plays only while [VideoInCar.allowed]. */
    val videoInCar: Boolean = false,
    /**
     * CarPlay's main buffered audio (see [BufferedAudioStream]): apps that support it send music
     * ahead of time over TCP, so short Wi-Fi gaps do not interrupt it.
     */
    val mainBufferedAudio: Boolean = false,
    /**
     * Over USB the iPhone sent navigation prompts as 8 kHz PCM, the lowest rate offered for the
     * default stream (#148, #341). When set, 8 and 16 kHz are not offered for default and alert audio.
     */
    val speechWithout8And16Khz: Boolean = false,
)

/** The offer, SETUP and controls must all honor the user's audio-output setting. */
internal val AirPlayConfig.bufferedAudioOutputEnabled: Boolean
    get() = mainBufferedAudio && !disableAudioOutput
