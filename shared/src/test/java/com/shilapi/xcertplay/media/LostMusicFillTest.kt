package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class LostMusicFillTest {
    private val frames = 1024

    private fun ptsUs(packet: Int) = packet * frames * 1_000_000L / 48_000

    /**
     * Sends packets 0 until [count] except [lost] through a decoder and returns the frames played. A packet in
     * [marked] arrived after a sequence gap. A decoder that does not pass timestamps through numbers its
     * output buffers itself.
     */
    private fun play(
        count: Int,
        lost: Set<Int>,
        marked: Set<Int> = lost.map { it + 1 }.filterNot(lost::contains).toSet(),
        passesTimestamps: Boolean = true,
    ): Long {
        val fill = LostMusicFill(48_000)
        var played = 0L
        var output = 0
        for (packet in 0 until count) {
            if (packet in lost) continue
            if (packet in marked) fill.lossBefore(ptsUs(packet))
            val pts = if (passesTimestamps) ptsUs(packet) else ptsUs(output)
            played += fill.silenceBefore(pts, frames) + frames
            output++
        }
        return played
    }

    @Test fun lostPacketsKeepTheMusicOnTheIphonesClock() {
        assertEquals(500L * frames, play(500, setOf(10, 50, 51, 52, 300)))
    }

    @Test fun packetsDroppedOnPurposeKeepTheirHole() {
        assertEquals(495L * frames, play(500, setOf(10, 50, 51, 52, 300), marked = emptySet()))
    }

    @Test fun aDecoderThatNumbersItsOwnOutputPlaysAsBefore() {
        assertEquals(495L * frames, play(500, setOf(10, 50, 51, 52, 300), passesTimestamps = false))
    }

    @Test fun aHoleLongerThanHalfASecondStaysAHole() {
        val stall = (100 until 130).toSet()
        assertEquals((500L - stall.size) * frames, play(500, stall))
    }

    @Test fun aMarkFarAheadOfTheMusicDoesNotBlockLaterHoles() {
        val fill = LostMusicFill(48_000)
        fill.lossBefore(ptsUs(1_000_000))
        fill.silenceBefore(ptsUs(0), frames)
        fill.lossBefore(ptsUs(2))
        assertEquals(frames, fill.silenceBefore(ptsUs(2), frames))
    }
}
