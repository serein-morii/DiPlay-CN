package com.shilapi.xcertplay

import android.os.Build
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.SafeAreaRect

/**
 * Geely FX11 / CS11_A4 head unit (`msmnile_gvmq`): the instrument cluster is an ordinary
 * 1920x720 presentation display, owned by no projection app. Any app may show a Presentation
 * on it; there is no BYD-style projection display, no projection command to issue and no
 * DiLink side-layer layout.
 *
 * Measured on the target head unit: the cluster is
 * displayId 2, 1920x720, flags 0x8b (`FLAG_PRESENTATION` set), and an ordinary third-party
 * `Presentation` becomes that display's focused window and renders.
 *
 * Selection is gated to this one head unit. Other ECARX firmware may expose a differently sized
 * or non-public cluster, and picking the wrong panel would put the map on a passenger screen.
 * The identifiers only admit the route; the display still has to look like this cluster.
 */
internal object GeelyClusterDisplay {

    /** Qualcomm's generic SA8155 board name: several brands' head units report it. */
    private const val DEVICE = "msmnile_gvmq"

    /** Product code of the head unit this route was measured on. */
    private const val PRODUCT = "geely_fx11"

    /** Model name that head unit reports. */
    private const val MODEL = "CS11_A4"

    /** The cluster stream is the panel's own size: the cluster scales nothing. */
    const val STREAM_WIDTH = 1920
    const val STREAM_HEIGHT = 720

    /**
     * The one car where this cluster route has been measured. All three identifiers are required:
     * `msmnile_gvmq` alone is Qualcomm's generic SA8155 board name, which other brands' head units
     * report too, and on those the DIM services either do not exist or mean something else. The
     * product and model identify the car, the same identifiers the wheel-key support uses
     * (`GeelyHeadUnit`). Values are compared without case, because the build fields are not
     * guaranteed to be lower case.
     */
    fun supported(
        device: String = Build.DEVICE,
        product: String = Build.PRODUCT,
        model: String = Build.MODEL,
    ): Boolean = device.equals(DEVICE, ignoreCase = true) &&
        product.equals(PRODUCT, ignoreCase = true) &&
        model.equals(MODEL, ignoreCase = true)

    /**
     * The cluster is the non-default presentation panel at the stream size. Its name is a
     * localized system string ("HDMI 屏幕"), so the name is deliberately not part of the match.
     */
    fun matches(displayId: Int, width: Int, height: Int, flags: Int): Boolean =
        displayId != Display.DEFAULT_DISPLAY &&
            (flags and Display.FLAG_PRESENTATION) != 0 &&
            width == STREAM_WIDTH && height == STREAM_HEIGHT

    fun matches(display: Display, width: Int, height: Int): Boolean =
        matches(display.displayId, width, height, display.flags)

    /**
     * A full-panel 1920x720 stream. The cluster keeps its own readouts outside
     * [safeAreaRect], which the cluster-map settings editor produces.
     */
    fun streamConfig(
        content: CarPlayClusterDisplay.Content,
        safeAreaRect: SafeAreaRect? = null,
        markerXPercent: Int? = null,
        markerYPercent: Int? = null,
    ): AirPlayDisplayConfig {
        val config = CarPlayClusterDisplay.config(
            STREAM_WIDTH,
            STREAM_HEIGHT,
            scalePercent = 100,
            horizontalStep = 0,
            verticalStep = 0,
            content = content,
            markerXPercent = markerXPercent,
            markerYPercent = markerYPercent,
        )
        return if (safeAreaRect == null) config else config.copy(safeArea = AirPlaySafeArea.toInsets(
            safeAreaRect, STREAM_WIDTH, STREAM_HEIGHT, STREAM_WIDTH, STREAM_HEIGHT))
    }
}
