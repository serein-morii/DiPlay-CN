package com.shilapi.xcertplay

import android.media.AudioManager
import android.os.Looper
import android.view.Surface
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayMediaFocusForwardingTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()
    private val sinks = mutableListOf<AndroidMediaSink>()

    @Before fun enableFocusAndGrantRequests() {
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        shadowOf(app.getSystemService(AudioManager::class.java))
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        sinks.forEach(AndroidMediaSink::close)
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveDisableMediaKeyAudioFocus(app, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun currentMediaKeyFocusReachesMatchingSinkOutsideOwnerLocks() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        doAnswer {
            assertFalse(Thread.holdsLock(CarPlayMediaKeys))
            assertFalse(Thread.holdsLock(CarPlayBackgroundSession))
            it.callRealMethod()
        }.`when`(sink).onMediaAudioFocusChanged(anyInt())
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun oldControllerAndOldRequestCallbacksCannotAffectReplacementSink() {
        val firstController = mock(CarPlayController::class.java).also(controllers::add)
        val firstSink = spy(AndroidMediaSink()).also(sinks::add)
        val oldListener = start(firstController, firstSink)
        val secondController = mock(CarPlayController::class.java).also(controllers::add)
        val secondSink = spy(AndroidMediaSink()).also(sinks::add)
        val currentListener = start(secondController, secondSink)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(firstSink, never()).onMediaAudioFocusChanged(anyInt())
        verify(secondSink, never()).onMediaAudioFocusChanged(anyInt())
        currentListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(secondSink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        CarPlayMediaKeys.detach(secondController)
        val replacement = start(secondController, secondSink)
        clearInvocations(secondSink)
        currentListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(secondSink, never()).onMediaAudioFocusChanged(anyInt())
        replacement.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(secondSink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun backgroundSessionMismatchIsNotForwarded() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        val listener = start(controller, sink)
        CarPlayBackgroundSession.clear()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(sink, never()).onMediaAudioFocusChanged(anyInt())
    }

    @Test fun immediateStartGrantIsForwardedWithoutWaitingForPlatformGainCallback() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        start(controller, sink, clearInitialGrant = false)
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun immediateRegrantRestoresFocusButRejectedRequestDoesNot() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        clearInvocations(sink)
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink, never()).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun explicitIphonePlaybackResumeRetriesFocusAfterUnreturnedTransientCallLoss() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        clearInvocations(sink)
        // Some Android car firmwares never send AUDIOFOCUS_GAIN after a Bluetooth call.
        // The iPhone's *new* playing event is an appropriate point to retry focus.
        CarPlayMediaKeys::class.java.getDeclaredMethod("onIphonePlaying",
            CarPlayController::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(CarPlayMediaKeys, controller, true)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun refusedResumedPlaybackFocusDoesNotUnmuteUntilAndroidGrantsIt() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        val audio = shadowOf(app.getSystemService(AudioManager::class.java))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        clearInvocations(sink)
        CarPlayMediaKeys::class.java.getDeclaredMethod("onIphonePlaying",
            CarPlayController::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(CarPlayMediaKeys, controller, true)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink, never()).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
        // A later platform gain still restores audio; a rejected retry must not fake one.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun transientFocusLossDoesNotRetryUntilIphoneActuallyResumesPlaying() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        clearInvocations(sink)
        CarPlayMediaKeys.onMediaAudioChanged(true) // AudioTrack is still present during the call.
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink, never()).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
        // Android's own return notification remains sufficient without a new play event.
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
        clearInvocations(sink)
        CarPlayMediaKeys::class.java.getDeclaredMethod("onIphonePlaying",
            CarPlayController::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(CarPlayMediaKeys, controller, true)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink, never()).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun queuedRealLossInvalidatesEarlierImmediateGrantBeforeItCanRestoreAudio() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        clearInvocations(sink)
        // Deliver a real loss ahead of the synthetic grant by processing it while the successful
        // request dispatch is still pending on the main handler.
        CarPlayMediaKeys::class.java.getDeclaredMethod("regainFocusLocked").apply { isAccessible = true }
            .let { method -> synchronized(CarPlayMediaKeys) { method.invoke(CarPlayMediaKeys) } }
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        verify(sink, never()).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    @Test fun queuedImmediateGrantCannotRestoreAReleasedRequest() {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        val sink = spy(AndroidMediaSink()).also(sinks::add)
        val listener = start(controller, sink)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        clearInvocations(sink)
        CarPlayMediaKeys::class.java.getDeclaredMethod("regainFocusLocked").apply { isAccessible = true }
            .let { method -> synchronized(CarPlayMediaKeys) { method.invoke(CarPlayMediaKeys) } }
        CarPlayMediaKeys.detach(controller)
        shadowOf(Looper.getMainLooper()).idle()
        verify(sink, never()).onMediaAudioFocusChanged(AudioManager.AUDIOFOCUS_GAIN)
    }

    private fun start(controller: CarPlayController, sink: AndroidMediaSink,
        clearInitialGrant: Boolean = true): AudioManager.OnAudioFocusChangeListener {
        `when`(controller.nowPlayingSnapshot()).thenReturn(CarPlayNowPlaying())
        CarPlayBackgroundSession.store(controller, sink, 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) {}
        CarPlayMediaKeys.attach(app, controller)
        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        if (clearInitialGrant) clearInvocations(sink)
        val request = ReflectionHelpers.getField<AudioFocusRequestCompat>(CarPlayMediaKeys, "focusRequest")
        return ReflectionHelpers.getField(request, "listener")
    }
}
