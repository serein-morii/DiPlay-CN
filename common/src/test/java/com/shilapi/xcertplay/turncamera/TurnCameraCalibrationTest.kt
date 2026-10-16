package com.shilapi.xcertplay.turncamera

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class TurnCameraCalibrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() { context.getSharedPreferences("diplay_turn_camera_calibration", 0).edit().clear().commit() }

    @Test fun corruptNonFiniteOrOversizedValuesCannotEscapeTheViewport() {
        val p = TurnCameraCalibration(windowX = Float.NaN, windowY = Float.POSITIVE_INFINITY,
            windowWidth = 50f, windowHeight = -.4f, imageX = Float.NEGATIVE_INFINITY,
            imageY = 200f, zoom = Float.NaN, rotation = 45, cameraId = "-1").normalized()
        assertEquals(.05f, p.windowX); assertEquals(.1f, p.windowY)
        assertEquals(1f, p.windowWidth); assertEquals(.1f, p.windowHeight)
        assertEquals(0f, p.imageX); assertEquals(1f, p.imageY)
        assertEquals(1f, p.zoom); assertEquals(0, p.rotation); assertEquals("", p.cameraId)
        assertEquals(p, p.normalized())
    }

    @Test fun rotationAndMirrorsHaveExplicitSourceAxesAndScreenOffsets() {
        fun mapped(p: TurnCameraCalibration) = p.mapPoint(0f, 0f, 320f, 180f, 320f, 180f)
        assertEquals(0f to 0f, mapped(TurnCameraCalibration()))
        assertEquals(320f to 0f, mapped(TurnCameraCalibration(mirrorX = true)))
        assertEquals(0f to 180f, mapped(TurnCameraCalibration(mirrorY = true)))
        assertEquals(320f to 180f, mapped(TurnCameraCalibration(rotation = 180)))
        val rotated = mapped(TurnCameraCalibration(rotation = 90))
        assertEquals(210.625f, rotated.first, .001f); assertEquals(0f, rotated.second, .001f)
        val shifted = mapped(TurnCameraCalibration(rotation = 90, imageX = .1f, imageY = -.2f))
        assertEquals(rotated.first + 32, shifted.first, .001f)
        assertEquals(rotated.second - 36, shifted.second, .001f)
    }

    @Test fun fitPreservesWholeFrameWhileFillCropsAndZoomIsIndependentOfDensity() {
        val p = TurnCameraCalibration()
        assertEquals(0f to 70f, p.mapPoint(0f, 0f, 320f, 180f, 320f, 320f))
        val filled = p.copy(fill = true).mapPoint(0f, 0f, 320f, 180f, 320f, 320f)
        assertTrue(filled.first < 0f); assertEquals(0f, filled.second, .001f)
        val zoomed = p.copy(zoom = 2f).mapPoint(0f, 0f, 320f, 180f, 320f, 180f)
        assertEquals(-160f to -90f, zoomed)
        assertThrows(IllegalArgumentException::class.java) { p.mapPoint(0f, 0f, 0f, 180f, 320f, 180f) }
    }

    @Test fun everyOrthogonalTransformKeepsCenterAndIsFinite() {
        for (r in listOf(0, 90, 180, 270)) for (mx in listOf(false, true)) for (my in listOf(false, true)) {
            val p = TurnCameraCalibration(rotation = r, mirrorX = mx, mirrorY = my)
            assertEquals(160f to 90f, p.mapPoint(160f, 90f, 320f, 180f, 320f, 180f))
        }
    }

    @Test fun idsAreExplicitUnassignedByDefaultAndNeverAutoMappedFromFacing() {
        assertEquals("", TurnCameraCalibrationStore.load(context, TurnCameraSide.LEFT).cameraId)
        for (bad in listOf("-1", "2147483648", "1;input tap", "", "99999999999")) {
            assertEquals(bad.isEmpty(), TurnCameraCalibration.validId(TurnCameraCalibration.Source.VENDOR_AVM, bad))
        }
        assertTrue(TurnCameraCalibration.validId(TurnCameraCalibration.Source.CAMERA2, "external/0"))
        assertFalse(TurnCameraCalibration.validId(TurnCameraCalibration.Source.CAMERA2, "0\n1"))
    }

    @Test fun profilesPersistSeparatelyButCannotBeExportedOrClaimVerification() {
        val left = TurnCameraCalibration(cameraId = "4", rotation = 270, mirrorY = true, zoom = 1.4f)
        val right = TurnCameraCalibration(cameraId = "6", windowX = 1f)
        TurnCameraCalibrationStore.save(context, mapOf(TurnCameraSide.LEFT to left, TurnCameraSide.RIGHT to right))
        assertEquals(left, TurnCameraCalibrationStore.load(context, TurnCameraSide.LEFT))
        assertEquals(right, TurnCameraCalibrationStore.load(context, TurnCameraSide.RIGHT))
        val exported = com.shilapi.xcertplay.backup.SettingsBackupCodec.export(context, "test").toString(Charsets.UTF_8)
        assertFalse(exported.contains("diplay_turn_camera_calibration"))
        assertFalse(context.getSharedPreferences("diplay_turn_camera_calibration", 0).all.keys.any { "enable" in it || "verified" in it })
    }

    @Test fun invalidIdCannotPartiallyOverwriteTheOtherSideAndUnknownSchemaFallsBack() {
        val first = TurnCameraCalibration(cameraId = "1")
        TurnCameraCalibrationStore.save(context, mapOf(TurnCameraSide.LEFT to first))
        assertThrows(IllegalArgumentException::class.java) {
            TurnCameraCalibrationStore.save(context, mapOf(TurnCameraSide.LEFT to first.copy(cameraId = "2"),
                TurnCameraSide.RIGHT to first.copy(cameraId = "invalid")))
        }
        assertEquals(first, TurnCameraCalibrationStore.load(context, TurnCameraSide.LEFT))
        context.getSharedPreferences("diplay_turn_camera_calibration", 0).edit().putString("LEFT", "{\"version\":99}").commit()
        assertEquals(TurnCameraCalibration(), TurnCameraCalibrationStore.load(context, TurnCameraSide.LEFT))
    }

    @Test fun malformedPreferenceTypesDoNotCrashTheEditor() {
        context.getSharedPreferences("diplay_turn_camera_calibration", 0).edit().putInt("LEFT", 1).commit()
        assertEquals(TurnCameraCalibration(), TurnCameraCalibrationStore.load(context, TurnCameraSide.LEFT))
    }
}
