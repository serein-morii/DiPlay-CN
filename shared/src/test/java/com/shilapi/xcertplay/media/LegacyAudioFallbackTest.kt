package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class LegacyAudioFallbackTest {
    @Test fun constructorRejectionStillCreatesUsageTrack() {
        val fallback = Any()
        val result = LegacyAudioFallback.build(
            createLegacy = { throw IllegalArgumentException("Unsupported stream") },
            isInitialized = { _: Any -> fail("No candidate should exist"); false },
            release = { fail("No candidate should exist") },
            createFallback = { fallback },
        )
        assertSame(fallback, result)
    }

    @Test fun uninitializedCandidateIsReleasedBeforeFallback() {
        val events = mutableListOf<String>()
        val result = LegacyAudioFallback.build(
            createLegacy = { "legacy" }, isInitialized = { false },
            release = { events += "release:$it" },
            createFallback = { events += "fallback"; "usage" },
        )
        assertEquals("usage", result)
        assertEquals(listOf("release:legacy", "fallback"), events)
    }

    @Test fun workingLegacyTrackDoesNotFallBackOrRelease() {
        assertEquals("legacy", LegacyAudioFallback.build(
            createLegacy = { "legacy" }, isInitialized = { true },
            release = { fail("Working track must remain open") },
            createFallback = { fail("No fallback needed"); "usage" },
        ))
    }

    @Test fun onlyGuidanceUsesLegacyStreams() {
        for (type in listOf("telephony", "speechRecognition", "media")) {
            assertFalse(type, AudioChannelMapper.usesNavigationStream(type, 100, false))
        }
        for (type in listOf("default", "alert", "compatibility")) {
            assertTrue(type, AudioChannelMapper.usesNavigationStream(type, 100, false))
            assertFalse(type, AudioChannelMapper.usesNavigationStream(type, 100, true))
        }
        assertFalse(AudioChannelMapper.usesNavigationStream("unknown", 102, false))
        assertTrue(AudioChannelMapper.usesNavigationStream("unknown", 100, false))
    }

@Test fun aCheckedExceptionFromTheRomStillCreatesUsageTrack() {
        val fallback = Any()
        val result = LegacyAudioFallback.build(
            createLegacy = { throw java.io.IOException("ROM rejected the legacy stream") },
            isInitialized = { _: Any -> fail("No candidate should exist"); false },
            release = { fail("No candidate should exist") },
            createFallback = { fallback },
        )
        assertSame(fallback, result)
    }
}
