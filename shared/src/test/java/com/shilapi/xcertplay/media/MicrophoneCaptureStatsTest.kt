package com.shilapi.xcertplay.media

import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MicrophoneCaptureStatsTest {
    private val config = MicrophoneConfig("telephony", 48_000, 1, 100, 20,
        InetAddress.getByName("198.51.100.20"), 54321, ByteArray(32) { 0x7f }, AudioCodecKind.OPUS)

    @Test fun aggregatesFiveSecondWindowsAndKeepsFinalPartialWindow() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.started(15)
        stats.reading()
        now = 25_000_000
        stats.read(1920)
        stats.encoded(1, 0)
        stats.sent()
        stats.reading()
        now += 30_000_000
        stats.read(0)
        stats.encoded(0, 1)
        stats.sent()
        stats.sendFailed()
        stats.flush()
        assertEquals(1, reports.size)
        now = 5_000_000_000
        stats.flush(routeType = { 18 })
        val first = reports.last()
        assertTrue(first.contains("captureBytes=1920 reads=2 zeroReads=1 readErrors=0 readMaxMs=30"))
        assertTrue(first.contains("encodedFrames=1 emptyEncodedFrames=1 udpSent=2 sendErrors=1 sendGapMaxMs=30"))
        assertTrue(first.contains("routedDeviceType=18"))
        assertTrue(first.endsWith("ended=false"))
        stats.reading()
        now += 8_000_000
        stats.read(-3)
        stats.failure(MicrophoneFailureStage.READ, code = -3)
        stats.flush(ended = true)
        val final = reports.last()
        assertTrue(final.contains("captureBytes=0 reads=1 zeroReads=0 readErrors=1 readMaxMs=8"))
        assertTrue(final.contains("encodedFrames=0 emptyEncodedFrames=0 udpSent=0 sendErrors=0"))
        assertTrue(final.endsWith("ended=true"))
    }

    @Test fun reportsTheLoudestSampleSoSilentCaptureStandsOut() {
        // #647: a silent microphone and a discarded uplink looked the same in a report.
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.level(ByteArray(960))
        now = 5_000_000_000
        stats.flush()
        assertTrue(reports.last().contains("peakDbfs=silent"))
        // -16384 little-endian is half of full scale: -6 dBFS.
        stats.level(byteArrayOf(0, 0, 0x00, 0xc0.toByte(), 0x10, 0x00))
        stats.flush(ended = true)
        assertTrue(reports.last().contains("peakDbfs=-6 ended=true"))
        assertEquals("0", MicrophoneCaptureStats.peakDbfs(32768))
    }

    @Test fun reportingIsBoundedAndRouteQueriesRunOnlyWhenDue() {
        var now = 0L
        var reports = 0
        var routeQueries = 0
        var longest = 0
        val stats = MicrophoneCaptureStats(config, { reports++; longest = maxOf(longest, it.length) }) { now }
        repeat(100_000) {
            stats.reading()
            now += 1000
            stats.read(0)
            stats.encoded(0, 1)
            stats.flush(routeType = { routeQueries++; 15 })
        }
        assertEquals(0, reports)
        assertEquals(0, routeQueries)
        now = 5_000_000_000
        stats.flush(routeType = { routeQueries++; 15 })
        assertEquals(1, reports)
        assertEquals(1, routeQueries)
        assertTrue(longest < 512)
    }

    @Test fun callbackAndRouteFailuresDoNotEscapeOrPreventCounterReset() {
        var now = 0L
        var fail = true
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, {
            if (fail) throw IllegalStateException("diagnostic callback failed")
            reports.add(it)
        }) { now }
        stats.started(15)
        stats.failure(MicrophoneFailureStage.CAPTURE, IllegalStateException("not reported"))
        stats.reading()
        stats.read(1920)
        stats.sent()
        now = 5_000_000_000
        stats.flush(routeType = { throw IllegalStateException("route metadata unavailable") })
        fail = false
        stats.flush(ended = true)
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("captureBytes=0 reads=0"))
        assertTrue(reports.single().contains("udpSent=0"))
        assertTrue(reports.single().contains("routedDeviceType=unknown"))
    }

    @Test fun onlyASilentWindowAsksWhatElseIsRecording() {
        var now = 0L
        var asked = 0
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.level(byteArrayOf(0x00, 0xc0.toByte()))
        now = 5_000_000_000
        stats.flush(silence = { asked++; "audioMode=3 otherRecordings=none" })
        assertEquals(0, asked)
        assertTrue(reports.last().endsWith("peakDbfs=-6 ended=false"))
        stats.level(ByteArray(960))
        now += 5_000_000_000
        stats.flush(silence = { asked++; "audioMode=3 otherRecordings=none" })
        assertEquals(1, asked)
        assertTrue(reports.last().endsWith("peakDbfs=silent audioMode=3 otherRecordings=none ended=false"))
        stats.flush(ended = true, silence = { throw SecurityException("audio service refused") })
        assertTrue(reports.last().endsWith("peakDbfs=silent ended=true"))
    }

    @Test fun fallbackSourceIsReportedAfterTheRefusedSource() {
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config.copy(audioType = "speechrecognition"), reports::add) { 0 }
        stats.useSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        stats.failure(MicrophoneFailureStage.RECORDER_CREATION, UnsupportedOperationException())
        stats.useSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        stats.started(15)
        stats.flush(ended = true)
        assertTrue(reports[0].contains("type=speechrecognition source=VOICE_RECOGNITION"))
        assertTrue(reports.drop(1).all { it.contains("type=speechrecognition source=VOICE_COMMUNICATION") })
    }

    @Test fun outputContainsOnlyAllowlistedMetadataAndNeverExceptionMessagesOrEndpoints() {
        val reports = mutableListOf<String>()
        val privateConfig = config.copy(audioType = "PRIVATE_PHONE_NAME\nsecret=value")
        val stats = MicrophoneCaptureStats(privateConfig, reports::add) { 0 }
        stats.started(15)
        stats.failure(MicrophoneFailureStage.CAPTURE, IllegalStateException("SECRET_PAYLOAD 198.51.100.20 54321"))
        stats.flush(ended = true)
        MicrophoneCaptureStats.reportStartFailure(privateConfig, SecurityException("PRIVATE_DEVICE_ADDRESS"), reports::add)
        val text = reports.joinToString("\n")
        assertTrue(text.contains("type=other source=MIC codec=OPUS"))
        assertTrue(text.contains("stage=CAPTURE error=IllegalStateException"))
        for (privateValue in listOf("PRIVATE", "secret", "SECRET", "198.51.100.20", "54321", "head=", "payload=", "key=")) {
            assertFalse(privateValue, text.contains(privateValue))
        }
        assertEquals(4, reports.size)
        assertTrue(reports.all { it.length < 512 && '\n' !in it })
    }
}
