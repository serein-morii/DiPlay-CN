package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class MediaClockTrimTest {
    @Before fun forgetLearnedTrim() { MediaClockTrim.learnedTrim = 0.0 }
    @After fun cleanUp() { MediaClockTrim.learnedTrim = 0.0 }

    /**
     * A head unit whose audio clock runs [drift] fast: the iPhone sends 352-frame packets at 48 kHz, with
     * gaps of 50–150 ms followed by a catch-up burst; the head unit plays 1024-frame periods.
     */
    private class Drive(val drift: Double, val trimmed: Boolean, seed: Int = 7) {
        val target = 14_400L // 300 ms at 48 kHz, as #610's music track started
        val trim = MediaClockTrim(48_000, target)
        val random = Random(seed)
        var queued = target.toDouble()
        var starved = 0
        var lowest = queued
        val rates = mutableListOf<Int>()

        fun run(seconds: Int) {
            var rate = 48_000
            var nextPacketMs = 0.0
            var pending = 0
            var gapUntilMs = -1.0
            var played = 0.0
            for (ms in 0 until seconds * 1000) {
                val nowNs = ms * 1_000_000L
                if (random.nextDouble() < 0.4 / 1000) gapUntilMs = ms + 50 + random.nextDouble() * 100
                while (nextPacketMs <= ms) { pending += 352; nextPacketMs += 352 / 48.0 }
                if (ms >= gapUntilMs && pending > 0) {
                    queued += pending
                    pending = 0
                    if (trimmed) {
                        trim.sample(queued.toLong(), nowNs)
                        trim.rateDue(nowNs)?.let { rate = it; rates += it }
                    }
                }
                played += rate * (1 + drift) / 1000.0
                while (played >= 1024) {
                    played -= 1024
                    if (queued >= 1024) queued -= 1024 else { starved++; queued = 0.0 }
                }
                lowest = minOf(lowest, queued)
            }
        }
    }

    /**
     * Wireless CarPlay: the iPhone sends about 0.4 s of music at once, every 0.4 s by its clock give or take
     * 30 ms (#807, #976). The head unit's clock is accurate; playback starts once the target, and at least one
     * burst, is queued.
     */
    private class BurstDrive(targetMs: Int, seed: Int = 7) {
        val target = 48L * targetMs
        val trim = MediaClockTrim(48_000, target)
        val random = Random(seed)
        var queued = maxOf(target, 19_200L).toDouble()
        val starvedSeconds = mutableListOf<Int>()
        val ratesAfterAMinute = mutableListOf<Int>()

        fun run(seconds: Int) {
            var rate = 48_000
            var burst = 1
            var nextBurstMs = 400.0
            var played = 0.0
            for (ms in 0 until seconds * 1000) {
                val nowNs = ms * 1_000_000L
                if (ms >= nextBurstMs) {
                    queued += 19_200
                    burst++
                    nextBurstMs = burst * 400 + (random.nextDouble() - 0.5) * 60
                    trim.sample(queued.toLong(), nowNs)
                    trim.rateDue(nowNs)?.let { rate = it }
                    if (ms >= 60_000) ratesAfterAMinute += rate
                }
                played += rate / 1000.0
                while (played >= 1024) {
                    played -= 1024
                    if (queued >= 1024) queued -= 1024 else { starvedSeconds += ms / 1000; queued = 0.0 }
                }
            }
        }
    }

    @Test fun overWiFiTheDefaultBufferIsNotPlayedFast() {
        for (seed in 1..3) {
            MediaClockTrim.learnedTrim = 0.0
            val drive = BurstDrive(300, seed)
            drive.run(300)
            assertEquals("seed $seed starved at ${drive.starvedSeconds}", 0, drive.starvedSeconds.count { it >= 60 })
            assertEquals("seed $seed", 48_000.0, drive.ratesAfterAMinute.average(), 20.0)
            assertTrue("seed $seed max ${drive.ratesAfterAMinute.max()}", drive.ratesAfterAMinute.max() < 48_200)
        }
    }

    @Test fun overWiFiALongerBufferAlsoSettlesAtTheIphonesClock() {
        val drive = BurstDrive(1000)
        drive.run(300)
        assertEquals(emptyList<Int>(), drive.starvedSeconds)
        assertEquals(48_000.0, drive.ratesAfterAMinute.average(), 20.0)
    }

    @Test fun withoutTheTrimA076PercentFastHeadUnitRunsDryEveryMinuteOrSo() {
        val drive = Drive(0.0076, trimmed = false)
        drive.run(300)
        assertTrue("starved ${drive.starved}", drive.starved >= 3)
    }

    @Test fun theTrimSettlesAtTheHeadUnitsDriftAndMusicNeverRunsDry() {
        val drive = Drive(0.0076, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertTrue("lowest ${drive.lowest}", drive.lowest > 0.2 * drive.target)
        // 48000 / 1.0076: the track plays that much slower, so the fast clock consumes 48 kHz.
        assertEquals(47_638.0, drive.trim.rate.toDouble(), 15.0)
        assertEquals(-0.0075, MediaClockTrim.learnedTrim, 0.0003)
    }

    @Test fun aSlowHeadUnitIsTrimmedTheOtherWay() {
        val drive = Drive(-0.0076, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertEquals(48_368.0, drive.trim.rate.toDouble(), 15.0)
    }

    @Test fun anAccurateClockIsLeftAlone() {
        val drive = Drive(0.0, trimmed = true)
        drive.run(300)
        assertEquals(0, drive.starved)
        assertEquals(48_000.0, drive.trim.rate.toDouble(), 5.0)
    }

    @Test fun theTrimStaysWithinOnePointFivePercent() {
        val trim = MediaClockTrim(48_000, 14_400)
        // Nothing queued for minutes: the trim stops at its bound.
        for (second in 0..600) {
            val nowNs = second * 1_000_000_000L
            trim.sample(0, nowNs)
            trim.rateDue(nowNs)
        }
        assertEquals(47_280, trim.rate)
    }

    @Test fun withoutNewAudioTheTrimHolds() {
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(7_200, 0)
        assertEquals(47_280, trim.rateDue(0))
        // The iPhone paused: no write for 2 s, so no update even though the last level was low.
        assertNull(trim.rateDue(2_000_000_000L))
        assertEquals(47_280, trim.rate)
        // Music resumes at the target level: the average restarts there.
        trim.sample(14_400, 2_100_000_000L)
        assertEquals(48_000, trim.rateDue(2_100_000_000L))
    }

    @Test fun aLongPauseDoesNotCountAsDrift() {
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(14_400, 0)
        trim.rateDue(0)
        // Five minutes later music resumes with a short first burst: one step, not 300 s, is integrated.
        val resumeNs = 300_000_000_000L
        trim.sample(10_080, resumeNs)
        assertEquals(47_539, trim.rateDue(resumeNs))
        assertEquals(-0.0006, MediaClockTrim.learnedTrim, 1e-9)
    }

    @Test fun aNewStreamStartsFromTheLearnedTrim() {
        MediaClockTrim.learnedTrim = -0.0075
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(14_400, 0)
        assertEquals(47_640, trim.rateDue(0))
    }

    @Test fun updatesComeAtMostOnceASecond() {
        val trim = MediaClockTrim(48_000, 14_400)
        trim.sample(7_200, 0)
        assertEquals(47_280, trim.rateDue(0))
        trim.sample(14_400, 500_000_000L)
        assertNull(trim.rateDue(500_000_000L))
    }

    @Test fun aLargeCompressedQueueMustNotTrickClockControlIntoSlowingDownFurther() {
        val trim = MediaClockTrim(48_000, 14_400)
        // The #806 recording had 150-192 pending AAC packets while Android's track writes
        // reported a small hardware PCM queue. Those packets are not 'missing' music.
        for (second in 1..12) {
            trim.sample(4_800, second * 1_000_000_000L, compressedFrames = 96 * 1024L)
            trim.rateDue(second * 1_000_000_000L)
        }
        assertTrue("slow playback grows a 2-second packet backlog; rate=${trim.rate}", trim.rate >= 48_000)
        // Decoder congestion is not a characteristic of the vehicle's playback clock. A future
        // song should not inherit a +1.5% learned pitch error caused by a temporary stall.
        assertEquals(0.0, MediaClockTrim.learnedTrim, 1e-8)
    }
}
