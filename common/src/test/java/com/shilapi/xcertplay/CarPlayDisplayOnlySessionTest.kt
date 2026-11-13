package com.shilapi.xcertplay

import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/**
 * With "CarPlay audio over the car's Bluetooth" the iPhone opens no media audio stream, yet what is
 * playing is still published, in a session that takes no audio focus and lists no actions (#1257).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayDisplayOnlySessionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<CarPlayController>()
    private val song = CarPlayNowPlaying(
        title = "My Favorite Things",
        album = "My Favorite Things",
        artist = "John Coltrane",
        sourceApp = "Music",
        elapsedMillis = 59_000,
        playing = true,
    )

    @Before fun carBluetoothAudio() {
        AirPlayPersistence.saveCarBluetoothAudio(app, true)
    }

    @After fun cleanup() {
        controllers.forEach(CarPlayMediaKeys::detach)
        CarPlayBackgroundSession.clear()
        AirPlayPersistence.saveCarBluetoothAudio(app, false)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun nowPlayingWithoutMediaAudioPublishesADisplayOnlySession() {
        attach(song)

        val session = currentSession()
        assertNotNull(session)
        assertTrue(session!!.isActive)
        assertTrue(displayOnly())
        assertNoFocus()
    }

    @Test fun nothingPlayingYetPublishesNoSession() {
        attach(CarPlayNowPlaying())

        assertNull(currentSession())
    }

    @Test fun withoutTheOptionNowPlayingAloneStillPublishesNoSession() {
        AirPlayPersistence.saveCarBluetoothAudio(app, false)

        attach(song)

        assertNull(currentSession())
    }

    @Test fun mediaAudioReplacesTheDisplayOnlySessionWithTheFullOne() {
        attach(song)
        val displaySession = currentSession()!!

        CarPlayMediaKeys.onMediaAudioChanged(true)
        shadowOf(Looper.getMainLooper()).idle()

        val fullSession = currentSession()!!
        assertNotSame(displaySession, fullSession)
        assertFalse(displaySession.isActive)
        assertTrue(fullSession.isActive)
        assertFalse(displayOnly())
    }

    @Test fun detachReleasesTheDisplayOnlySession() {
        val controller = attach(song)
        val session = currentSession()!!

        CarPlayMediaKeys.detach(controller)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(session.isActive)
        assertNull(currentSession())
        assertFalse(displayOnly())
    }

    @Test fun displayOnlyStateListsNoActionsAndKeepsPlayAndPosition() {
        val state = CarPlayMediaKeys.playbackState(song, mediaAudioActive = false, displayOnly = true, elapsedUpdatedAt = 42)

        assertEquals(0L, state.actions)
        assertEquals(PlaybackState.STATE_PLAYING, state.state)
        assertEquals(59_000L, state.position)
        assertEquals(42L, state.lastPositionUpdateTime)
    }

    @Test fun fullSessionStateStillListsTheTransportActions() {
        val state = CarPlayMediaKeys.playbackState(song, mediaAudioActive = true, displayOnly = false, elapsedUpdatedAt = 42)

        assertNotEquals(0L, state.actions and PlaybackState.ACTION_SKIP_TO_NEXT)
        assertNotEquals(0L, state.actions and PlaybackState.ACTION_PLAY_PAUSE)
    }

    @Test fun keysThatReachTheDisplayOnlySessionAreLoggedAndNeverSentToTheIphone() {
        WheelKeyLog.reset()
        val controller = attach(song)
        val callback: MediaSession.Callback = ReflectionHelpers.getField(CarPlayMediaKeys, "displayOnlyCallback")

        // A wheel key, and a launcher's buttons.
        val next = Intent(Intent.ACTION_MEDIA_BUTTON)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue(callback.onMediaButtonEvent(next))
        callback.onPlay()
        callback.onPause()
        callback.onSkipToNext()
        callback.onSkipToPrevious()

        verify(controller, never()).sendMediaButton(anyInt())
        val report = WheelKeyLog.report(bound = true).joinToString("\n")
        // The wheel key, named as Android names it, and the four calls a launcher can make.
        assertEquals(report, 5, Regex("reached the display-only session; left to the car").findAll(report).count())
        assertTrue(report, report.contains("media key pause reached the display-only session; left to the car"))
    }

    private fun attach(nowPlaying: CarPlayNowPlaying): CarPlayController {
        val controller = mock(CarPlayController::class.java).also(controllers::add)
        `when`(controller.nowPlayingSnapshot()).thenReturn(nowPlaying)
        CarPlayMediaKeys.attach(app, controller)
        shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    private fun currentSession(): MediaSession? =
        ReflectionHelpers.getField(CarPlayMediaKeys, "session")

    private fun displayOnly(): Boolean = ReflectionHelpers.getField(CarPlayMediaKeys, "displayOnly")

    private fun assertNoFocus() {
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusRequest"))
        assertFalse(ReflectionHelpers.getField(CarPlayMediaKeys, "focusHeld"))
        assertNull(ReflectionHelpers.getField<Any?>(CarPlayMediaKeys, "focusOwner"))
    }
}
