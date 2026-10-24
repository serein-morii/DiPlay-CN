package com.shilapi.xcertplay.turncamera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Pure calibration-capture logic: tag parsing, guided phases, channel sweep bookkeeping. */
class TurnCameraCalibrationCaptureTest {

    @Test fun cameraTagsParseAcrossFirmwareShapes() {
        assertEquals(listOf("pano_h", "front"), TurnCameraAvmEnumeration.parseCameraTags("pano_h, front"))
        assertEquals(listOf("pano_h", "pano_l"), TurnCameraAvmEnumeration.parseCameraTags("[pano_h | pano_l]"))
        assertEquals(listOf("a", "b"), TurnCameraAvmEnumeration.parseCameraTags(arrayOf("a", "b")))
        assertEquals(listOf("x"), TurnCameraAvmEnumeration.parseCameraTags(listOf("x")))
        assertEquals(listOf("solo"), TurnCameraAvmEnumeration.parseCameraTags("solo"))
        assertEquals(emptyList<String>(), TurnCameraAvmEnumeration.parseCameraTags(null))
        // Distinct, order preserved, decoration stripped.
        assertEquals(listOf("a", "b"), TurnCameraAvmEnumeration.parseCameraTags("a, a, \"b\""))
    }

    @Test fun enumerationRecordsIdsSizesAndJournalWithoutOpening() {
        val info = FakeInfo()
        val enumeration = TurnCameraAvmEnumeration(
            classLookup = { name, _ ->
                when (name) {
                    TurnCameraAvmEnumeration.BMM_CAMERA_INFO -> info.javaClass
                    else -> throw ClassNotFoundException(name)
                }
            },
            jarExists = { false },
        )
        assertTrue(enumeration.run())
        assertEquals(5, enumeration.cameraCount)
        assertEquals(
            listOf("pano_h" to 0, "pano_l" to 1, "special_front" to 7),
            enumeration.cameras.map { it.tag to it.id },
        )
        assertEquals(700, enumeration.cameras.last().width)
        assertEquals(350, enumeration.cameras.last().height)
        assertTrue(enumeration.cameras.last().fromFirmwareTags)
        assertTrue(enumeration.journal.any { it.startsWith("getValidCameraTag=pano_l") })
        assertTrue(enumeration.journal.none { it.contains("open") || it.contains("startPreview") })
    }

    @Test fun jarFallbackIsTriedOnceAndOnlyWhenTheClassIsMissing() {
        var jarLoads = 0
        val enumeration = TurnCameraAvmEnumeration(
            classLookup = { name, loader ->
                when {
                    // The app class path lacks the class; only the jar loader finds it.
                    name == TurnCameraAvmEnumeration.BMM_CAMERA_INFO && loader != null -> FakeInfo(count = 2).javaClass
                    else -> throw ClassNotFoundException(name)
                }
            },
            jarExists = { it == TurnCameraAvmEnumeration.CAMERA_JAR },
            jarLoader = { jarLoads++; ClassLoader.getSystemClassLoader() },
        )
        assertTrue(enumeration.run())
        assertEquals(1, jarLoads)
        assertTrue(enumeration.journal.contains("bmmcamera.jar: loaded"))
    }

    @Test fun signalCaptureLabelsPhasesAndCollectsDistinctValues() {
        val now = AtomicLong(0)
        var turn = 1
        val capture = TurnCameraSignalCapture(clock = { now.get() }, read = { intArrayOf(turn, 1, 0, 0) })
        capture.phase("idle", 100)
        capture.phase("left", 100)
        capture.phase("neutral", 100)
        assertEquals("idle", capture.beginNext()?.key)
        // idle: mostly 1 with one transient 9 — both recorded, min/max sensible.
        repeat(5) {
            assertEquals("idle", capture.tick()); now.addAndGet(10)
        }
        turn = 9
        capture.tick(); now.addAndGet(10)
        turn = 1
        assertNull(capture.beginNext()) // phase not ended yet
        now.set(200) // end idle
        assertEquals("left", capture.beginNext()?.key)
        turn = 2
        repeat(4) { capture.tick(); now.addAndGet(10) }
        now.set(400)
        assertEquals("neutral", capture.beginNext()?.key)
        turn = 1
        capture.tick()
        val values = capture.values()
        assertEquals(listOf(1, 9), values["idle"]!![0])
        assertEquals(listOf(2), values["left"]!![0])
        assertEquals(listOf(1), values["neutral"]!![0])
        val report = capture.reportText()
        assertTrue(report.contains("turn=1, 9 (min=1, max=9)"))
        assertTrue(report.contains("phase left:"))
        assertTrue(report.contains(TurnCameraSignalCapture.HINT_TURN_MASK).not())
    }

    @Test fun signalCaptureSurvivesFailingReads() {
        val now = AtomicLong(0)
        var fail = false
        val capture = TurnCameraSignalCapture(clock = { now.get() }, read = { if (fail) null else intArrayOf(1, 1, 0, 0) })
        capture.phase("idle", 50)
        assertEquals("idle", capture.beginNext()?.key)
        assertEquals("idle", capture.tick())
        fail = true
        assertEquals("idle", capture.tick()) // read failed: no data, phase still open
        fail = false
        now.set(100)
        assertNull(capture.tick()) // phase over
        assertEquals(0, capture.remainingPhases())
    }

    @Test fun previewSweeperStepsWithinRangeAndMarksSides() {
        val sweeper = AvmPreviewSweeper(cameraId = 0, channels = 0..4)
        assertEquals(0, sweeper.channel)
        assertEquals(1, sweeper.next())
        assertEquals(2, sweeper.next())
        sweeper.markLeft()
        assertEquals(2, sweeper.left)
        assertEquals(3, sweeper.next())
        sweeper.markRight()
        assertEquals(3, sweeper.right)
        assertEquals(4, sweeper.next()) // clamped at the top
        assertTrue(sweeper.complete())
        // Same channel for both sides is not a complete calibration.
        val broken = AvmPreviewSweeper(0, 0..4)
        broken.markLeft(); broken.markRight()
        assertFalse(broken.complete())
        assertTrue(sweeper.hint().contains("2=left"))
    }

    /** Static fake of the vendor info class: reflection invokes these with a null instance. */
    private class FakeInfo(private val count: Int = 5) {
        companion object {
            @JvmStatic fun getCameraNumbers(): Int = 5
            @JvmStatic fun getValidCameraTag(): String = "pano_l; special_front"
            @JvmStatic fun getCameraId(tag: String): Int = when (tag) {
                "pano_h" -> 0
                "pano_l" -> 1
                "special_front" -> 7
                else -> -1
            }
            @JvmStatic fun getDefaultPreviewWidth(id: Int): Int = id * 100
            @JvmStatic fun getDefaultPreviewHeight(id: Int): Int = id * 50
        }
    }
}
