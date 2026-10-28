package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.SafeAreaRect
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager

/**
 * The Geely FX11 cluster route: a plain 1920x720 presentation display selected only on the
 * measured head unit. Values mirror the measured panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GeelyClusterDisplayTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private companion object {
        const val MEASURED_DEVICE = "msmnile_gvmq"
        const val MEASURED_PRODUCT = "geely_fx11"
        const val MEASURED_MODEL = "CS11_A4"

        /** Measured flags of displayId 2: FLAG_PRESENTATION | FLAG_SECURE | protected buffers | own content. */
        const val MEASURED_FLAGS = 0x8b

        /** Robolectric's mdpi display qualifier yields exactly 1920x720 pixels. */
        const val PANEL_QUALIFIER = "w1920dp-h720dp-mdpi"
        const val PANEL_DISPLAY_ID = 5
    }

    @Before fun reset() {
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        offTheMeasuredHeadUnit()
    }

    @After fun tearDown() {
        MapMirrors.streamAspect = MapMirrors.PHYSICAL_STREAM_ASPECT
    }

    /** Everything the route looks at, so the gate is satisfied. */
    private fun onTheMeasuredHeadUnit() {
        ShadowBuild.setDevice(MEASURED_DEVICE)
        ShadowBuild.setProduct(MEASURED_PRODUCT)
        ShadowBuild.setModel(MEASURED_MODEL)
    }

    private fun offTheMeasuredHeadUnit() {
        ShadowBuild.setDevice("robolectric")
        ShadowBuild.setProduct("robolectric")
        ShadowBuild.setModel("robolectric")
    }

    @Test fun theDeviceGateNeedsTheDeviceTheProductAndTheModelTogether() {
        assertTrue(GeelyClusterDisplay.supported(MEASURED_DEVICE, MEASURED_PRODUCT, MEASURED_MODEL))
        assertTrue("the build fields are compared without case",
            GeelyClusterDisplay.supported("MSMNILE_GVMQ", "GEELY_FX11", "cs11_a4"))

        assertFalse("msmnile_gvmq is Qualcomm's generic SA8155 board name, not this car",
            GeelyClusterDisplay.supported(MEASURED_DEVICE, "other_product", MEASURED_MODEL))
        assertFalse("the product alone is not this car",
            GeelyClusterDisplay.supported(MEASURED_DEVICE, MEASURED_PRODUCT, "other_model"))
        assertFalse("the model alone is not this car",
            GeelyClusterDisplay.supported("other_device", MEASURED_PRODUCT, MEASURED_MODEL))
        assertFalse(GeelyClusterDisplay.supported("robolectric", "robolectric", "robolectric"))
        assertFalse(GeelyClusterDisplay.supported("", "", ""))
    }

    @Test fun theBuildFieldsAdmitTheMeasuredHeadUnit() {
        onTheMeasuredHeadUnit()
        assertTrue(GeelyClusterDisplay.supported())
        offTheMeasuredHeadUnit()
        assertFalse(GeelyClusterDisplay.supported())
    }

    @Test fun panelMatchNeedsThePresentationFlagTheSizeAndANonDefaultDisplay() {
        assertTrue(GeelyClusterDisplay.matches(PANEL_DISPLAY_ID, 1920, 720, MEASURED_FLAGS))
        assertFalse("the centre screen is never the cluster",
            GeelyClusterDisplay.matches(Display.DEFAULT_DISPLAY, 1920, 720, MEASURED_FLAGS))
        assertFalse("a non-presentation panel is not the cluster",
            GeelyClusterDisplay.matches(PANEL_DISPLAY_ID, 1920, 720, 0x03))
        assertFalse(GeelyClusterDisplay.matches(PANEL_DISPLAY_ID, 1280, 480, MEASURED_FLAGS))
        assertFalse(GeelyClusterDisplay.matches(PANEL_DISPLAY_ID, 1920, 1080, MEASURED_FLAGS))
    }

    @Test fun streamConfigIsTheWholePanelAndKeepsTheSafeArea() {
        val plain = GeelyClusterDisplay.streamConfig(CarPlayClusterDisplay.Content.MAP)
        assertEquals(1920, plain.widthPixels)
        assertEquals(720, plain.heightPixels)
        assertEquals(CarPlayClusterDisplay.Content.MAP.url, plain.initialUrl)

        val inset = GeelyClusterDisplay.streamConfig(
            CarPlayClusterDisplay.Content.MAP,
            safeAreaRect = SafeAreaRect(200, 0, 1720, 720),
        )
        assertEquals(200, inset.safeArea!!.left)
        assertEquals(200, inset.safeArea!!.right)
        assertEquals(0, inset.safeArea!!.bottom)
        assertEquals(1920, inset.widthPixels)
    }

    @Test fun findDisplaySelectsThePanelOnTheMeasuredDeviceOnly() {
        val id = ShadowDisplayManager.addDisplay(PANEL_QUALIFIER, PANEL_DISPLAY_ID)
        shadowOf(app.getSystemService(DisplayManager::class.java).getDisplay(id)).apply {
            setFlags(Display.FLAG_PRESENTATION)
        }
        try {
            onTheMeasuredHeadUnit()
            assertEquals(id, ClusterMapPresentation.findDisplay(app)?.displayId)

            offTheMeasuredHeadUnit()
            assertNull(ClusterMapPresentation.findDisplay(app))
        } finally {
            offTheMeasuredHeadUnit()
            ShadowDisplayManager.removeDisplay(id)
        }
    }

    @Test fun clusterStreamIsThePanelSizeWhenTheClusterMapIsOn() {
        AirPlayPersistence.saveClusterMapEnabled(app, true)
        val id = ShadowDisplayManager.addDisplay(PANEL_QUALIFIER, PANEL_DISPLAY_ID)
        shadowOf(app.getSystemService(DisplayManager::class.java).getDisplay(id)).apply {
            setFlags(Display.FLAG_PRESENTATION)
        }
        onTheMeasuredHeadUnit()
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            val config = CarPlayHostActivity::class.java.getDeclaredMethod("clusterDisplayConfig")
                .apply { isAccessible = true }.invoke(host) as com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
            assertEquals(1920, config.widthPixels)
            assertEquals(720, config.heightPixels)
            assertEquals(1920.0 / 720.0, MapMirrors.streamAspect, 0.00001)
        } finally {
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                val executor = host.javaClass.getDeclaredField(name).apply { isAccessible = true }
                    .get(host) as ExecutorService
                executor.shutdownNow()
            }
            offTheMeasuredHeadUnit()
            ShadowDisplayManager.removeDisplay(id)
        }
    }
}
