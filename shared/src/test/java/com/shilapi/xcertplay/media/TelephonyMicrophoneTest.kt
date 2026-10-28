package com.shilapi.xcertplay.media

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE,
    shadows = [TelephonyMicrophoneTest.ConfigurableAudioEffect::class, ShadowAudioModeRequests::class])
class TelephonyMicrophoneTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val telephony = AudioStreamId(100, "telephony")
    private val speechRecognition = AudioStreamId(100, "speechrecognition")
    private val readStarted = CountDownLatch(1)
    private val recorder = AtomicReference<AudioRecord?>()
    private lateinit var manager: AudioManager
    private lateinit var sink: AndroidMediaSink

    @Before fun setUp() {
        ConfigurableAudioEffect.resetStatus()
        ShadowAudioModeRequests.clearRequests()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        context.getSharedPreferences("diplay_bt_suspend", 0).edit().clear().commit()
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_ON)
        manager = context.getSystemService(AudioManager::class.java)
        sink = AndroidMediaSink(context = context)
        for (type in listOf(AudioEffect.EFFECT_TYPE_AEC, AudioEffect.EFFECT_TYPE_NS)) {
            ShadowAudioEffect.addEffect(AudioEffect.Descriptor(type.toString(), type.toString(),
                "Pre Processing", "Test effect", "DiPlay"))
        }
        ShadowAudioRecord.setSourceProvider { record ->
            recorder.compareAndSet(null, record)
            readStarted.countDown()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    // Keep capture alive without sending packets or busy-spinning.
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        sink.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun telephonyEnablesEffectsOnItsRecorderAndRestoresThePreviousMode() {
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_RINGTONE)
        sink.onMicrophoneStarted(telephony, config("telephony"))
        val record = awaitCapture()

        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, record.audioSource)
        val effects = ShadowAudioEffect.getAudioEffects()
        assertEquals(microphoneLog(), setOf("AcousticEchoCanceler", "NoiseSuppressor"),
            effects.map { it.javaClass.simpleName }.toSet())
        effects.forEach {
            assertTrue(it.enabled)
            assertEquals(record.audioSessionId, Shadow.extract<ShadowAudioEffect>(it).audioSession)
        }

        sink.onMicrophoneStopped(telephony)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertEquals(AudioRecord.STATE_UNINITIALIZED, record.state)
    }

    @Test fun aCallLeavesCommunicationModeOnceItsOtherOwnerLetsGo() {
        // #987: the Telecom call for the car's phone audio, or the car's own phone app, enters communication mode
        // before DiPlay's call does. DiPlay asked for that mode again after the call and kept the head unit in it,
        // with no music or navigation, after the other owner had let go.
        val diagnostics = CopyOnWriteArrayList<String>()
        sink.close()
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add)
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_IN_COMMUNICATION)
        sink.onMicrophoneStarted(telephony, config("telephony"))
        awaitCapture()
        sink.onMicrophoneStopped(telephony)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)

        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_NORMAL)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertTrue(diagnostics.joinToString("\n"), diagnostics.any {
            it.startsWith("Audio: mode restored requested=${AudioManager.MODE_NORMAL} now=${AudioManager.MODE_IN_COMMUNICATION}")
        })
    }

    @Test fun closingTheSinkDuringACallAlsoLetsTheModeGo() {
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_IN_COMMUNICATION)
        sink.onMicrophoneStarted(telephony, config("telephony"))
        awaitCapture()
        sink.close()
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_NORMAL)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test fun aCallWhileCarBluetoothIsOffUsesTheCabinMicrophone() {
        val diagnostics = recordDiagnostics()
        context.getSharedPreferences("diplay_bt_suspend", 0).edit().putBoolean("restore_initially_enabled", true).commit()
        shadowOf(context.getSystemService(BluetoothManager::class.java).adapter).setState(BluetoothAdapter.STATE_OFF)
        manager.mode = AudioManager.MODE_NORMAL
        sink.onMicrophoneStarted(telephony, config("telephony"))
        val record = awaitCapture()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertEquals(MediaRecorder.AudioSource.MIC, record.audioSource)
        assertTrue(diagnostics.joinToString("\n"),
            diagnostics.any { it.startsWith("Microphone: start type=telephony source=MIC ") })
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        sink.onMicrophoneStopped(telephony)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test fun aCallInTheCarsPhoneStateRecordsFromVoiceRecognitionWithoutCallEffects() {
        val diagnostics = recordDiagnostics()
        BydOutputSettings.setPhoneAudioRoute(context, true)
        PhoneAudioRoute.attach(context)
        try {
            sink.onMicrophoneStarted(telephony, config("telephony"))
            val record = awaitCapture()
            assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, record.audioSource)
            // #987: the report said VOICE_COMMUNICATION for these calls, so it could not show the car's phone state.
            assertTrue(diagnostics.joinToString("\n"),
                diagnostics.any { it.startsWith("Microphone: start type=telephony source=VOICE_RECOGNITION ") })
            assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        } finally {
            BydOutputSettings.setPhoneAudioRoute(context, false)
        }
    }

    @Test @Config(sdk = [28])
    fun aSilentWindowNamesTheModeAndWhatElseIsRecording() {
        // #987: calls and Siri recorded only zeros on a head unit that stayed in communication mode, and the
        // report could not show whether another app held the microphone.
        val diagnostics = recordDiagnostics()
        sink.onMicrophoneStarted(speechRecognition, config("speechrecognition"))
        val record = awaitCapture()
        val audio = shadowOf(manager)
        audio.setActiveRecordingConfigurations(listOf(
            audio.createActiveRecordingConfiguration(record.audioSessionId, MediaRecorder.AudioSource.VOICE_RECOGNITION, context.packageName),
            audio.createActiveRecordingConfiguration(record.audioSessionId + 1, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "com.example.carphone"),
        ), false)
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        sink.onMicrophoneStopped(speechRecognition)
        val stats = diagnostics.last { it.startsWith("Microphone: stats") }
        assertTrue(stats, stats.endsWith("peakDbfs=silent audioMode=3 otherRecordings=[VOICE_COMMUNICATION] ended=true"))
    }

    @Test @Config(sdk = [28])
    fun aSilentWindowWithNoOtherRecordingSaysSo() {
        val diagnostics = recordDiagnostics()
        sink.onMicrophoneStarted(speechRecognition, config("speechrecognition"))
        awaitCapture()
        sink.onMicrophoneStopped(speechRecognition)
        val stats = diagnostics.last { it.startsWith("Microphone: stats") }
        assertTrue(stats, stats.endsWith("peakDbfs=silent audioMode=0 otherRecordings=none ended=true"))
    }

    @Test fun anUnverifiedPauseFlagDoesNotSwitchCallAudioRouting() {
        context.getSharedPreferences("diplay_bt_suspend", 0).edit()
            .putBoolean("suspended_by_us", true).putBoolean("restore_initially_enabled", true).commit()
        sink.onMicrophoneStarted(telephony, config("telephony"))
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, awaitCapture().audioSource)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
    }

    @Test fun speechRecognitionDoesNotChangeModeOrEnableTelephonyEffects() {
        sink.onMicrophoneStarted(speechRecognition, config("speechrecognition"))
        val record = awaitCapture()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, record.audioSource)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    @Test fun microphoneMetadataAndFinalCountersReachTheAudioDiagnosticCallback() {
        sink.close()
        val diagnostics = CopyOnWriteArrayList<String>()
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add)
        sink.onMicrophoneStarted(speechRecognition, config("speechrecognition"))
        awaitCapture()
        sink.onMicrophoneStopped(speechRecognition)
        val microphone = diagnostics.filter { it.startsWith("Microphone:") }
        assertTrue(microphone.any { it.startsWith("Microphone: start type=speechrecognition source=VOICE_RECOGNITION codec=LPCM") })
        assertTrue(microphone.any { it.contains("Microphone: stats") && it.endsWith("ended=true") })
        assertFalse(microphone.joinToString("\n").contains("port="))
        assertFalse(microphone.joinToString("\n").contains("head="))
    }

    @Test fun diagnosticCallbackFailureDoesNotStopSpeechRecognitionCapture() {
        sink.close()
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = { throw IllegalStateException("diagnostic callback failed") })
        sink.onMicrophoneStarted(speechRecognition, config("speechrecognition"))
        val record = awaitCapture()
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, record.audioSource)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        sink.onMicrophoneStopped(speechRecognition)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, record.state)
    }

    @Test fun diagnosticCallbackFailureDoesNotStopCallCaptureOrChangeModeRestoration() {
        sink.close()
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = { throw IllegalStateException("diagnostic callback failed") })
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_RINGTONE)
        sink.onMicrophoneStarted(telephony, config("telephony"))
        val record = awaitCapture()
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, record.audioSource)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(2, ShadowAudioEffect.getAudioEffects().size)
        sink.onMicrophoneStopped(telephony)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    @Test fun stoppingAnotherStreamDoesNotRestoreTheCallMode() {
        sink.onMicrophoneStarted(telephony, config("telephony"))
        awaitCapture()
        sink.onMicrophoneStopped(speechRecognition)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
        assertEquals(microphoneLog(), 2, ShadowAudioEffect.getAudioEffects().size)
        sink.onMicrophoneStopped(telephony)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
    }

    @Test fun closingTheSinkReleasesEffectsAndRestoresMode() {
        sink.onMicrophoneStarted(telephony, config("telephony"))
        awaitCapture()
        sink.close()
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    @Test fun duplicateStartDoesNotOverwriteTheOriginalModeOrCreateMoreEffects() {
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_RINGTONE)
        sink.onMicrophoneStarted(telephony, config("telephony"))
        awaitCapture()
        sink.onMicrophoneStarted(telephony, config("telephony"))
        assertEquals(microphoneLog(), 2, ShadowAudioEffect.getAudioEffects().size)
        sink.onMicrophoneStopped(telephony)
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
    }

    @Test fun failedRecorderInitializationRestoresThePreviousMode() {
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_RINGTONE)
        sink.onMicrophoneStarted(telephony, config("telephony").copy(sampleRate = 1))
        assertEquals(AudioManager.MODE_RINGTONE, manager.mode)
        assertNull(recorder.get())
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    @Test fun missingOpusEncodersFailBeforeCaptureAndAllowAnotherStartAttempt() {
        val diagnostics = CopyOnWriteArrayList<String>()
        var encoderAttempts = 0
        val uplink = MicrophoneUplink(config("telephony").copy(codec = AudioCodecKind.OPUS),
            onDiagnostic = diagnostics::add,
            opusEncoderFactory = { encoderAttempts++; null })
        try {
            assertFalse(uplink.start())
            assertFalse(uplink.start())
            assertEquals(2, encoderAttempts)
            assertNull(recorder.get())
            assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
            assertEquals(2, diagnostics.count { it.contains("stage=ENCODER") })
        } finally { uplink.close() }
    }

    @Test fun softwareEncoderDiagnosticsCannotPreventMicrophoneCapture() {
        val uplink = MicrophoneUplink(config("speechrecognition").copy(codec = AudioCodecKind.OPUS),
            onDiagnostic = { throw IllegalStateException("report unavailable") },
            opusEncoderFactory = { SoftwareOpusEncoder(it) })
        try {
            assertTrue(uplink.start())
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, awaitCapture().recordingState)
        } finally { uplink.close() }
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.get()!!.state)
    }

    @Test
    @Config(shadows = [FailingRestoreAudioManager::class])
    fun failedModeRestoreStillReportsTheReadableCurrentMode() {
        verifyRestoreFailure(failGetter = false)
    }

    @Test
    @Config(shadows = [FailingRestoreAudioManager::class])
    fun failedModeRestoreAndModeGetterCannotEscapeMicrophoneTeardown() {
        verifyRestoreFailure(failGetter = true)
    }

    private fun verifyRestoreFailure(failGetter: Boolean) {
        sink.close()
        val diagnostics = CopyOnWriteArrayList<String>()
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add)
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_RINGTONE)
        sink.onMicrophoneStarted(telephony, config("telephony"))
        val record = awaitCapture()
        FailingRestoreAudioManager.failSetter = true
        FailingRestoreAudioManager.failGetter = failGetter
        try {
            sink.onMicrophoneStopped(telephony)
            assertEquals(AudioRecord.STATE_UNINITIALIZED, record.state)
            assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
            val failure = diagnostics.single { it.startsWith("Audio: mode restore failed") }
            val expectedMode = if (failGetter) "unknown" else AudioManager.MODE_IN_COMMUNICATION.toString()
            assertTrue(failure, failure.contains("requested=${AudioManager.MODE_NORMAL} now=$expectedMode"))
            assertTrue(failure, failure.contains("error=SecurityException"))
            sink.onMicrophoneStopped(telephony)
            assertEquals(1, diagnostics.count { it.startsWith("Audio: mode restore failed") })
        } finally { FailingRestoreAudioManager.resetFailures() }
    }

    @Test fun unavailableEffectsDoNotPreventRecording() {
        ShadowAudioEffect.reset()
        sink.onMicrophoneStarted(telephony, config("telephony"))
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, awaitCapture().recordingState)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    @Test fun enableFailureReleasesRejectedEffectsAndContinuesRecording() {
        ConfigurableAudioEffect.enableStatus = AudioEffect.ERROR_INVALID_OPERATION
        sink.onMicrophoneStarted(telephony, config("telephony"))
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, awaitCapture().recordingState)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertTrue(ShadowLog.getLogsForTag("xcertplay-usb").any {
            it.msg.contains("could not be enabled status=${AudioEffect.ERROR_INVALID_OPERATION}")
        })
    }

    @Test
    @Config(shadows = [ThrowingEchoCanceler::class])
    fun echoCancelerCreationFailureStillAllowsNoiseSuppressionAndRecording() {
        sink.onMicrophoneStarted(telephony, config("telephony"))
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, awaitCapture().recordingState)
        assertEquals(microphoneLog(), 1, ShadowAudioEffect.getAudioEffects().size)
        val effect = ShadowAudioEffect.getAudioEffects().single()
        assertEquals("NoiseSuppressor", effect.javaClass.simpleName)
        assertTrue(effect.enabled)
    }

    @Test
    @Config(shadows = [RejectingAudioManager::class])
    fun modeFailureDoesNotEscapeTheDownlinkCallback() {
        sink.onMicrophoneStarted(telephony, config("telephony"))
        assertNull(recorder.get())
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertTrue(ShadowLog.getLogsForTag("xcertplay-usb").any { it.msg.contains("microphone start failed") })
    }

    @Test fun telephonyKeepsRecordingWhenTheEchoCancellerLibraryIsMissing() {
        sink.close()
        sink = AndroidMediaSink(context = context, callEchoCancellation = true)
        registerCallDownlink()
        sink.onMicrophoneStarted(telephony, config("telephony"))
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, awaitCapture().recordingState)
        assertTrue(microphoneLog(), microphoneLog().contains("microphone echo canceller enabled=false"))
        sink.onMicrophoneStopped(telephony)
    }

    @Test fun defaultCallCaptureKeepsPlatformEffectsWithoutStartingExperimentalProcessing() {
        sink.onMicrophoneStarted(telephony, config("telephony"))
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, awaitCapture().recordingState)
        assertEquals(2, ShadowAudioEffect.getAudioEffects().size)
        assertFalse(microphoneLog().contains("echo canceller"))
    }

    @Test fun softwareCallCaptureDisablesPlatformAecBeforeProcessingAndReleasesBothOnClose() {
        val fake = FakeCallEchoCanceller()
        provideCallFrame()
        val uplink = MicrophoneUplink(config("telephony"), echoReference = EchoReference(16_000),
            echoCancellerFactory = { _, _, _ -> fake })
        try {
            assertTrue(uplink.start())
            awaitCapture()
            assertTrue(fake.processed.await(5, TimeUnit.SECONDS))
            assertEquals(false, fake.platformAecEnabledAtProcessing)
            val effects = ShadowAudioEffect.getAudioEffects()
            assertFalse(effects.single { it is AcousticEchoCanceler }.enabled)
            // Speex denoises after cancellation, so platform NS must not alter the signal first.
            assertFalse(effects.single { it.javaClass.simpleName == "NoiseSuppressor" }.enabled)
            effects.forEach { assertEquals(recorder.get()!!.audioSessionId, Shadow.extract<ShadowAudioEffect>(it).audioSession) }
        } finally { uplink.close() }
        assertTrue(fake.closed)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.get()!!.state)
    }

    @Test fun softwareProcessingFailureRestoresPlatformAecAndKeepsTheSameRecorderRunning() {
        val fake = FakeCallEchoCanceller(succeeds = false)
        val nextRead = CountDownLatch(1)
        provideCallFrame(nextRead)
        val uplink = MicrophoneUplink(config("telephony"), echoReference = EchoReference(16_000),
            echoCancellerFactory = { _, _, _ -> fake })
        try {
            assertTrue(uplink.start())
            val record = awaitCapture()
            // The next read is reached only after processing/fallback and packet sending finish.
            assertTrue(nextRead.await(5, TimeUnit.SECONDS))
            assertEquals(false, fake.platformAecEnabledAtProcessing)
            assertTrue(fake.closed)
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, record.recordingState)
            assertTrue(ShadowAudioEffect.getAudioEffects().all { it.enabled })
            assertTrue(microphoneLog().contains("platform AEC restored"))
        } finally { uplink.close() }
    }

    @Test fun failedPlatformAecDisableKeepsTheOriginalCallEffectsAndClosesSoftware() {
        ConfigurableAudioEffect.disableStatus = AudioEffect.ERROR_INVALID_OPERATION
        val fake = FakeCallEchoCanceller()
        val uplink = MicrophoneUplink(config("telephony"), echoReference = EchoReference(16_000),
            echoCancellerFactory = { _, _, _ -> fake })
        try {
            assertTrue(uplink.start())
            awaitCapture()
            assertTrue(fake.closed)
            assertEquals(2, ShadowAudioEffect.getAudioEffects().size)
            assertTrue(ShadowAudioEffect.getAudioEffects().all { it.enabled })
            assertTrue(microphoneLog().contains("platform AEC could not be disabled"))
            assertTrue(microphoneLog().contains("microphone echo canceller enabled=false"))
        } finally { uplink.close() }
    }

    @Test fun unmatchedCallRatesAndStereoCaptureKeepPlatformAecWithoutCreatingSoftware() {
        var created = 0
        for (captureConfig in listOf(config("telephony").copy(sampleRate = 48_000),
                config("telephony").copy(channels = 2))) {
            val uplink = MicrophoneUplink(captureConfig, echoReference = EchoReference(16_000),
                echoCancellerFactory = { _, _, _ -> created++; FakeCallEchoCanceller() })
            try {
                assertTrue(uplink.start())
                assertEquals(2, ShadowAudioEffect.getAudioEffects().size)
                assertTrue(ShadowAudioEffect.getAudioEffects().all { it.enabled })
            } finally { uplink.close() }
        }
        assertEquals(0, created)
    }

    @Test fun eachCallDownlinkOwnsItsReferenceAndReplacementsDoNotReuseOldPcm() {
        sink.close()
        sink = AndroidMediaSink(context = context, callEchoCancellation = true)
        registerCallDownlink()
        val second = AudioStreamId(101, "telephony")
        registerCallDownlink(second)
        val references = org.robolectric.util.ReflectionHelpers.getField<Map<AudioStreamId, EchoReference>>(sink, "callEchoReferences")
        val firstReference = references.getValue(telephony)
        assertNotSame(firstReference, references.getValue(second))
        sink.onMicrophoneStarted(telephony, config("telephony"))
        awaitCapture()
        val uplinks = org.robolectric.util.ReflectionHelpers.getField<Map<AudioStreamId, MicrophoneUplink>>(sink, "microphoneUplinks")
        assertSame(firstReference, org.robolectric.util.ReflectionHelpers.getField<EchoReference>(uplinks.getValue(telephony), "echoReference"))

        sink.onAudioStopped(second)
        assertEquals(setOf(telephony), references.keys)
        sink.onMicrophoneStopped(telephony)
        sink.onAudioStopped(telephony)
        assertTrue(references.isEmpty())
        registerCallDownlink()
        assertNotSame(firstReference, references.getValue(telephony))
        sink.close()
        assertTrue(references.isEmpty())
    }

    @Test fun actualDownlinkRateMismatchKeepsThePlatformCallPath() {
        sink.close()
        sink = AndroidMediaSink(context = context, callEchoCancellation = true)
        registerCallDownlink()
        sink.onMicrophoneStarted(telephony, config("telephony").copy(sampleRate = 48_000))
        awaitCapture()
        assertTrue(microphoneLog(), microphoneLog().contains("echo canceller skipped rate=48000"))
        assertTrue(ShadowAudioEffect.getAudioEffects().all { it.enabled })
    }

    private fun registerCallDownlink(id: AudioStreamId = telephony) {
        // Register the real renderer through its production callback without starting a playback
        // worker: these tests check capture ownership, not whether Robolectric renders audio.
        sink.onAudioRtp(id, AudioFormat(AudioCodecKind.LPCM, 16_000, 1, id.type, "telephony"), byteArrayOf(), 0)
    }

    private fun provideCallFrame(nextRead: CountDownLatch? = null) {
        val produced = AtomicBoolean()
        ShadowAudioRecord.setSourceProvider { record ->
            recorder.compareAndSet(null, record)
            readStarted.countDown()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    if (produced.compareAndSet(false, true)) return config("telephony").frameBytes
                    nextRead?.countDown()
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    private class FakeCallEchoCanceller(private val succeeds: Boolean = true) : CallEchoCanceller {
        override val frameSamples = 320
        val processed = CountDownLatch(1)
        @Volatile var closed = false
        @Volatile var platformAecEnabledAtProcessing: Boolean? = null
        override fun process(frame: ByteArray, reference: ShortArray): Boolean {
            platformAecEnabledAtProcessing = ShadowAudioEffect.getAudioEffects().filterIsInstance<AcousticEchoCanceler>().singleOrNull()?.enabled
            processed.countDown()
            return succeeds
        }
        override fun close() { closed = true }
    }

    @Test fun speechRecognitionNeverStartsTheEchoCanceller() {
        sink.onMicrophoneStarted(speechRecognition, config("speechrecognition"))
        awaitCapture()
        assertFalse(microphoneLog().contains("echo canceller"))
    }

    private fun recordDiagnostics(): MutableList<String> {
        sink.close()
        val diagnostics = CopyOnWriteArrayList<String>()
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add)
        return diagnostics
    }

    private fun awaitCapture(): AudioRecord {
        assertTrue("Microphone capture did not start", readStarted.await(5, TimeUnit.SECONDS))
        return requireNotNull(recorder.get())
    }

    private fun microphoneLog(): String = ShadowLog.getLogsForTag("xcertplay-usb").joinToString("\n") {
        "${it.msg} ${it.throwable ?: ""}"
    }

    private fun config(audioType: String) = MicrophoneConfig(
        audioType = audioType,
        sampleRate = 16_000,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = InetAddress.getLoopbackAddress(),
        port = 9,
        key = ByteArray(32),
    )

    @Implements(AudioEffect::class)
    class ConfigurableAudioEffect : ShadowAudioEffect() {
        @Implementation
        override fun native_setEnabled(enabled: Boolean): Int =
            if (!enabled && disableStatus != AudioEffect.SUCCESS) disableStatus
            else if (enableStatus == AudioEffect.SUCCESS) super.native_setEnabled(enabled) else enableStatus

        companion object {
            var enableStatus = AudioEffect.SUCCESS
            var disableStatus = AudioEffect.SUCCESS

            @Resetter @JvmStatic
            fun resetStatus() {
                enableStatus = AudioEffect.SUCCESS
                disableStatus = AudioEffect.SUCCESS
            }
        }
    }

    @Implements(AcousticEchoCanceler::class)
    class ThrowingEchoCanceler {
        companion object {
            @Implementation @JvmStatic
            fun isAvailable(): Boolean = true

            @Implementation @JvmStatic
            fun create(sessionId: Int): AcousticEchoCanceler? = throw IllegalStateException("Effect initialization failed")
        }
    }

    @Implements(AudioManager::class)
    class RejectingAudioManager : ShadowAudioManager() {
        @Implementation
        override fun setMode(mode: Int) {
            throw SecurityException("Mode change denied")
        }
    }

    @Implements(AudioManager::class)
    class FailingRestoreAudioManager : ShadowAudioModeRequests() {
        @Implementation override fun setMode(mode: Int) {
            if (failSetter) throw SecurityException("Mode restore denied")
            super.setMode(mode)
        }
        @Implementation override fun getMode(): Int {
            if (failGetter) throw IllegalStateException("Mode unavailable")
            return super.getMode()
        }
        companion object {
            var failSetter = false
            var failGetter = false
            @Resetter @JvmStatic fun resetFailures() {
                failSetter = false
                failGetter = false
            }
        }
    }
}
