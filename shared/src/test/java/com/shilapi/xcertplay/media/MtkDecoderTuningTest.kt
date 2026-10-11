package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MtkDecoderTuningTest {
    @Test fun recognisesOmxAndCodec2MediaTekDecodersOnly() {
        assertTrue(MtkDecoderTuning.isDecoderName("OMX.MTK.VIDEO.DECODER.HEVC"))
        assertTrue(MtkDecoderTuning.isDecoderName("c2.mtk.avc.decoder"))
        assertTrue(MtkDecoderTuning.isDecoderName("omx.mtk.video.decoder.avc"))
        assertFalse(MtkDecoderTuning.isDecoderName("c2.qti.hevc.decoder"))
        assertFalse(MtkDecoderTuning.isDecoderName("c2.android.hevc.decoder"))
        assertTrue(MtkDecoderTuning.supportsLegacyAcodecKeys("OMX.MTK.VIDEO.DECODER.HEVC"))
        assertFalse(MtkDecoderTuning.supportsLegacyAcodecKeys("c2.mtk.hevc.decoder"))
    }

    @Test fun modeFallsBackFromNoReorderToLowLatencyToOff() {
        assertEquals(MtkDecoderMode.LOW_LATENCY,
            MtkDecoderMode.LOW_LATENCY_NO_REORDER.fallback())
        assertEquals(MtkDecoderMode.NONE, MtkDecoderMode.LOW_LATENCY.fallback())
        assertEquals(MtkDecoderMode.NONE, MtkDecoderMode.NONE.fallback())
    }

    @Test fun requestedModeOnlyAppliesToMediaTekDecoders() {
        assertEquals(
            MtkDecoderMode.LOW_LATENCY_NO_REORDER,
            appliedMtkDecoderMode(
                MtkDecoderMode.LOW_LATENCY_NO_REORDER,
                "OMX.MTK.VIDEO.DECODER.HEVC",
            ),
        )
        assertEquals(
            MtkDecoderMode.NONE,
            appliedMtkDecoderMode(MtkDecoderMode.LOW_LATENCY_NO_REORDER, "c2.qti.hevc.decoder"),
        )
        assertEquals(
            MtkDecoderMode.NONE,
            appliedMtkDecoderMode(MtkDecoderMode.LOW_LATENCY_NO_REORDER, "c2.mtk.hevc.decoder"),
        )
    }

    @Test fun dedicatedCodec2LowLatencyDecoderIsPreferredForH264() {
        assertEquals(
            MtkDecoderCandidate("c2.mtk.avc.decoder.lowlatency", lowLatency = true),
            MtkDecoderTuning.selectedDecoder(listOf(
                MtkDecoderCandidate("c2.mtk.avc.decoder"),
                MtkDecoderCandidate("c2.mtk.avc.decoder.lowlatency", lowLatency = true),
                MtkDecoderCandidate("OMX.MTK.VIDEO.DECODER.AVC"),
            )),
        )
    }

    @Test fun codec2ComponentNameCanIdentifyLowLatencyWhenTheFeatureFlagIsMissing() {
        assertTrue(MtkDecoderTuning.isCodec2LowLatencyDecoder(
            "c2.mtk.hevc.decoder.lowlatency",
            advertisesLowLatency = false,
        ))
        assertFalse(MtkDecoderTuning.isCodec2LowLatencyDecoder(
            "c2.mtk.hevc.decoder",
            advertisesLowLatency = false,
        ))
    }

    @Test fun legacyKeysAreOnlyOfferedWhenOmxIsTheDefaultMtkDecoder() {
        assertEquals(
            MtkDecoderCandidate("OMX.MTK.VIDEO.DECODER.AVC"),
            MtkDecoderTuning.selectedDecoder(listOf(
                MtkDecoderCandidate("OMX.MTK.VIDEO.DECODER.AVC"),
            )),
        )
        assertNull(
            MtkDecoderTuning.selectedDecoder(listOf(
                MtkDecoderCandidate("c2.mtk.avc.decoder"),
                MtkDecoderCandidate("OMX.MTK.VIDEO.DECODER.AVC"),
            )),
        )
    }

    @Test fun modesMapToTheLegacyMtkAcodecKeys() {
        assertEquals(emptyList<String>(), MtkDecoderMode.NONE.formatKeys())
        assertEquals(
            listOf("vdec-lowlatency"),
            MtkDecoderMode.LOW_LATENCY.formatKeys(),
        )
        assertEquals(
            listOf("vdec-lowlatency", "vdec-no-record"),
            MtkDecoderMode.LOW_LATENCY_NO_REORDER.formatKeys(),
        )
    }

    @Test fun enabledAttemptsDropNoReorderThenLowLatencyBeforeStandardFormats() {
        assertEquals(
            listOf(
                DecoderAttempt(null, tuned = true, operatingRate = 30,
                    mtkMode = MtkDecoderMode.LOW_LATENCY_NO_REORDER),
                DecoderAttempt(null, tuned = true, operatingRate = 30,
                    mtkMode = MtkDecoderMode.LOW_LATENCY),
                DecoderAttempt(null, tuned = true, operatingRate = 30),
                DecoderAttempt(null, tuned = true,
                    mtkMode = MtkDecoderMode.LOW_LATENCY_NO_REORDER),
                DecoderAttempt(null, tuned = true, mtkMode = MtkDecoderMode.LOW_LATENCY),
                DecoderAttempt(null, tuned = true),
                DecoderAttempt(null, tuned = false),
            ),
            videoDecoderAttempts(
                operatingRate = 30,
                softwareDecoder = null,
                maximumMtkMode = MtkDecoderMode.LOW_LATENCY_NO_REORDER,
            ),
        )
    }
}
