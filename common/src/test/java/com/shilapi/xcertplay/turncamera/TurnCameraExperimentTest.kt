package com.shilapi.xcertplay.turncamera

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.backup.SettingsBackupCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TurnCameraExperimentTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences(TurnCameraExperimentSettings.PREFS, 0).edit().clear().commit()
        context.getSharedPreferences("diplay_turn_camera_calibration", 0).edit().clear().commit()
        ReflectionHelpers.setStaticField(Build::class.java, "FINGERPRINT", "test/experiment/firmware")
        controller("test-controller-1")
        Light.turn = 21; Light.hazard = 10; Light.reverse = 30; Light.fail = false; Light.onRead = {}
        Oem.state = 40
    }
    private fun controller(value: String) {
        Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
            .invoke(null, "apps.setting.product.outswver", value)
    }
    private fun configured(): JSONObject {
        TurnCameraCalibrationStore.save(context, mapOf(TurnCameraSide.LEFT to TurnCameraCalibration(cameraId = "7"),
            TurnCameraSide.RIGHT to TurnCameraCalibration(cameraId = "8")))
        return JSONObject(TurnCameraExperimentSettings.TEMPLATE)
            .put("turnOff", 20).put("turnLeft", 21).put("turnRight", 22)
            .put("hazardOff", 10).put("hazardOn", 11).put("reverseOff", 30).put("reverseOn", 31)
            .put("oemClass", "android.hardware.bydauto.test.OemDevice").put("oemGetter", "getCameraState")
            .put("oemIdle", 40).put("oemActive", 41)
            .put("leftChannel", 0).put("rightChannel", 1)
            .put("leftWidth", 640).put("leftHeight", 480).put("rightWidth", 640).put("rightHeight", 480)
            .put("timestampTimebase", "ELAPSED_REALTIME_NANOS")
            .put("safeAreaConfirmed", true).put("latchedSignalConfirmed", true)
    }
    private fun save(j: JSONObject) = TurnCameraExperimentSettings.saveText(context, j.toString())

    @Test fun defaultAndTemplateCannotOpenAnythingOrEnableWithoutExplicitConfiguration() {
        assertFalse(TurnCameraExperimentSettings.enabled(context))
        assertNull(TurnCameraExperimentSettings.profile(context, false))
        assertThrows(IllegalArgumentException::class.java) { TurnCameraExperimentSettings.setEnabled(context, true) }
        assertFalse(TurnCameraExperimentSettings.enabled(context))
    }
    @Test fun consentIsFirmwareBoundAndEditingEitherProfileOrCalibrationRevokesIt() {
        val j = configured(); save(j)
        assertNotNull(TurnCameraExperimentSettings.profile(context, false))
        assertNull(TurnCameraExperimentSettings.profile(context))
        TurnCameraExperimentSettings.setEnabled(context, true)
        assertNotNull(TurnCameraExperimentSettings.profile(context))
        controller("test-controller-2"); assertNull(TurnCameraExperimentSettings.profile(context))
        TurnCameraExperimentSettings.setEnabled(context, true)
        save(j); assertFalse(TurnCameraExperimentSettings.enabled(context))
        TurnCameraExperimentSettings.setEnabled(context, true)
        TurnCameraCalibrationStore.save(context, mapOf(TurnCameraSide.LEFT to TurnCameraCalibration(cameraId = "9")))
        assertFalse(TurnCameraExperimentSettings.enabled(context))
    }
    @Test fun guessedOverlappingNegativeOrUnsupportedValuesNeverFormAnEnabledProfile() {
        for ((key, value) in listOf("turnRight" to 21, "hazardOn" to 10, "reverseOff" to -1,
            "timestampTimebase" to "UNCONFIRMED", "oemClass" to "java.lang.Runtime", "oemGetter" to "setCameraState",
            "safeAreaConfirmed" to false, "leftWidth" to 0, "rightChannel" to 1.5)) {
            val j = configured().put(key, value); save(j)
            assertNull("$key must fail closed", TurnCameraExperimentSettings.profile(context, false))
        }
    }
    @Test fun experimentConfigurationAndConsentNeverTravelInBackup() {
        save(configured()); TurnCameraExperimentSettings.setEnabled(context, true)
        val exported = SettingsBackupCodec.export(context, "test").toString(Charsets.UTF_8)
        assertFalse(exported.contains("turn_camera_experiment")); assertFalse(exported.contains("oemGetter"))
    }
    @Test fun mappedLatchedTurnsHazardsReverseAndOemOccupancyAreIndependent() {
        save(configured())
        val p = TurnCameraExperimentSettings.profile(context, false)!!
        var now = 1000L
        val source = BydExperimentalTurnSignalSource(context, p, { now }) {
            if (it.endsWith("BYDAutoLightDevice")) Light::class.java else Oem::class.java
        }
        assertEquals(TurnSignalState.LEFT, source.read().signal.state)
        Light.turn = 22; assertEquals(TurnSignalState.RIGHT, source.read().signal.state)
        Light.hazard = 11; assertEquals(TurnSignalState.HAZARD, source.read().signal.state)
        Light.hazard = 999; assertEquals(TurnSignalState.UNKNOWN, source.read().signal.state)
        Light.hazard = 10; Light.reverse = 31; assertEquals(NativeCameraState.ACTIVE, source.read().native)
        Light.reverse = 30; Oem.state = 41; assertEquals(NativeCameraState.ACTIVE, source.read().native)
        Oem.state = 999; assertEquals(NativeCameraState.UNKNOWN, source.read().native)
        Light.onRead = { now += 2000 }
        assertEquals(1000L, source.read().signal.atMillis)
        source.close(); assertEquals(TurnSignalState.UNKNOWN, source.read().signal.state)
    }
    @Test fun sourceFailureCannotFabricateOffOrIdle() {
        save(configured())
        val source = BydExperimentalTurnSignalSource(context, TurnCameraExperimentSettings.profile(context, false)!!, { 1000 }) {
            if (it.endsWith("BYDAutoLightDevice")) Light::class.java else Oem::class.java
        }
        Light.fail = true
        val reading = source.read()
        assertEquals(TurnSignalState.UNKNOWN, reading.signal.state)
        assertEquals(NativeCameraState.UNKNOWN, reading.native)
        source.close()
    }
    class Light {
        fun getTurnLightState(): Int { if (fail) error("getter failed"); onRead(); return turn }
        fun getDoubleFlashLightState() = hazard
        fun getReversingLightState() = reverse
        companion object {
            var turn = 0; var hazard = 0; var reverse = 0; var fail = false; var onRead: () -> Unit = {}
            @JvmStatic fun getInstance(context: Context) = Light()
        }
    }
    class Oem {
        fun getCameraState() = state
        companion object { var state = 0; @JvmStatic fun getInstance(context: Context) = Oem() }
    }
}
