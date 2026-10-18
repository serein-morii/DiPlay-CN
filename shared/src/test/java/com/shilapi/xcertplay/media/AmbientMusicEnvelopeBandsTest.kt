package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientMusicEnvelopeBandsTest {
    private fun bands(vararg values: Int) = IntArray(16) { values.getOrElse(it) { 1 } }

    @Test fun playedBandsKeepTheLoudestOfEachBandSinceTheLastCall() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 480, 0.1, 0.0, bands(3, 1, 9))
        envelope.append(480, 480, 0.1, 0.0, bands(1, 12, 2))
        envelope.append(960, 480, 0.1, 0.0, bands(5, 5, 5))
        // The head is inside the third segment: the first two have played, the third is current.
        assertArrayEquals(bands(5, 12, 9), envelope.playedBands(1000, true))
        // Nothing new has played since: only the current segment remains.
        assertArrayEquals(bands(5, 5, 5), envelope.playedBands(1001, true))
    }

    @Test fun nothingIsReturnedWhilePausedOrWithoutBands() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 480, 0.1, 0.0, bands(7))
        assertNull(envelope.playedBands(10, false))
        val plain = AmbientMusicEnvelope()
        plain.append(0, 480, 0.1, 0.0)
        assertNull(plain.playedBands(10, true))
    }
}
