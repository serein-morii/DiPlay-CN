package com.shilapi.xcertplay.transport

import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2WiredAvailabilityDecodeTest {
    @Test fun aMalformedAvailabilityBodyIsReportedInsteadOfEndingTheControlLoop() {
        // A one-byte 0x4300 body is a truncated CSM parameter header. Iap2ParameterList.parse
        // throws Iap2ProtocolException, which is an IOException, not a RuntimeException.
        val summary = Iap2WiredControlClient.carPlayAvailabilitySummary(byteArrayOf(0))
        assertTrue(summary, summary.startsWith("iap2 4300 decode failed:"))
    }

    @Test fun aParameterLengthPastTheBodyIsReportedInsteadOfEndingTheControlLoop() {
        // length = 8 but only the 4 header bytes are present.
        val summary = Iap2WiredControlClient.carPlayAvailabilitySummary(byteArrayOf(0, 8, 0, 0))
        assertTrue(summary, summary.startsWith("iap2 4300 decode failed:"))
    }
}
