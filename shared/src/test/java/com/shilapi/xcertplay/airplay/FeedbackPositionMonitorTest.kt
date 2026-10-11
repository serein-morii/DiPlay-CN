package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackPositionMonitorTest {
    private val monitor = FeedbackPositionMonitor(sampleRate = 48_000, repeatNs = 10_000L)

    @Test fun logsTheFirstAnswerThenOnlyOverruns() {
        assertNull(monitor.onFeedback(102, 7L, 1_000L, 0L)) // Nothing received yet.
        monitor.observeRtp(48_000)
        assertEquals(
            "Audio: feedback type=102 id=7 reported=24000 received=48000 aheadMs=-500",
            monitor.onFeedback(102, 7L, 24_000L, 0L),
        )
        // Behind the received audio, as while it plays, or inside the newest packet.
        assertNull(monitor.onFeedback(102, 7L, 48_960L, 1L))
        // The stream starved and the extrapolated position ran 0.5 s past the newest packet.
        assertEquals(
            "Audio: feedback type=102 id=7 reported=72000 received=48000 aheadMs=500 overrun=true",
            monitor.onFeedback(102, 7L, 72_000L, 2L),
        )
        assertNull(monitor.onFeedback(102, 7L, 96_000L, 9_000L))
        assertEquals(
            "Audio: feedback type=102 id=7 reported=120000 received=48000 aheadMs=1500 overrun=true",
            monitor.onFeedback(102, 7L, 120_000L, 10_002L),
        )
    }

    @Test fun keepsTheNewestSampleAcrossReorderingAndTheTimestampWrap() {
        monitor.observeRtp(-960) // 0xfffffc40
        monitor.observeRtp(-1_920) // Late packet: not newer.
        monitor.observeRtp(960) // After the wrap.
        assertEquals(
            "Audio: feedback type=100 id=0 reported=0 received=960 aheadMs=-20",
            monitor.onFeedback(100, null, 0L, 0L),
        )
        assertEquals(48_000L, FeedbackPositionMonitor.samplesAhead(24_000L, 0xffff_ffffL - 23_999L))
    }
}
