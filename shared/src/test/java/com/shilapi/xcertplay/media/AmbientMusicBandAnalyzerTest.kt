package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientMusicBandAnalyzerTest {
    private val rate = 48_000

    private fun sine(frequency: Double, frames: Int, amplitude: Double = 0.5, channels: Int = 2): ByteArray {
        val out = ByteArray(frames * channels * 2)
        for (n in 0 until frames) {
            val sample = (sin(2.0 * PI * frequency * n / rate) * amplitude * 32767).toInt().toShort().toInt()
            for (channel in 0 until channels) {
                val p = (n * channels + channel) * 2
                out[p] = sample.toByte()
                out[p + 1] = (sample shr 8).toByte()
            }
        }
        return out
    }

    private fun analyze(data: ByteArray): IntArray {
        val analyzer = AmbientMusicBandAnalyzer(rate, 2)
        var result = IntArray(16) { 1 }
        var offset = 0
        // 10 ms writes, as the sink delivers them.
        while (offset + 1920 <= data.size) {
            result = analyzer.analyze(data, offset, 1920)
            offset += 1920
        }
        return result
    }

    @Test fun silenceIsTheLowestLevelInEveryBand() {
        assertEquals(List(16) { 1 }, analyze(ByteArray(48_000 * 4)).toList())
    }

    @Test fun aToneLightsItsOwnBandMostAndStaysWithinOneToFifteen() {
        for ((band, frequency) in AmbientMusicBandAnalyzer.FREQUENCIES.withIndex()) {
            val levels = analyze(sine(frequency.toDouble(), 48_000))
            assertTrue("band $band levels ${levels.toList()}", levels.all { it in 1..15 })
            // The low bands see few cycles in the window, so a neighbour can edge out the tone's own band.
            assertTrue("band $band", levels[band] >= levels.max() - 1)
            assertTrue("band $band", levels[band] >= 13)
        }
    }

    @Test fun aBassToneKeepsTheHighBandsLow() {
        val levels = analyze(sine(80.0, 48_000))
        assertTrue(levels[1] > levels[15] + 5)
        assertEquals(1, levels[15])
    }

    @Test fun aQuietToneStillReachesTheTopOfTheScaleBecauseTheLevelsAreRelative() {
        val levels = analyze(sine(400.0, 48_000, amplitude = 0.02))
        assertEquals(15, levels[6])
    }

    @Test fun nothingIsReturnedAsFreshUntilEnoughFramesArrive() {
        val analyzer = AmbientMusicBandAnalyzer(rate, 2)
        val first = analyzer.analyze(sine(400.0, 480), 0, 480 * 4)
        assertEquals(List(16) { 1 }, first.toList())
    }

    @Test fun badRangesAreIgnored() {
        val analyzer = AmbientMusicBandAnalyzer(rate, 2)
        assertEquals(List(16) { 1 }, analyzer.analyze(ByteArray(4), 8, 4).toList())
        assertEquals(List(16) { 1 }, analyzer.analyze(ByteArray(4), -1, 4).toList())
    }
}
