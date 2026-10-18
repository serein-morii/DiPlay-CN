package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** The bubble's setting, its remembered size, and the overlay permission it needs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LauncherBubblePersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() = prefs.edit().clear().apply()

    @Test fun aFreshInstallHasNoBubbleAndTheDefaultSize() {
        assertFalse(AirPlayPersistence.loadLauncherBubble(context))
        assertEquals(LauncherBubbleLayout.DEFAULT_SIZE_PERCENT, AirPlayPersistence.loadLauncherBubbleSize(context))
    }

    @Test fun theSettingAndTheSizeRoundTrip() {
        AirPlayPersistence.saveLauncherBubble(context, true)
        AirPlayPersistence.saveLauncherBubbleSize(context, 12)
        assertTrue(AirPlayPersistence.loadLauncherBubble(context))
        assertEquals(12, AirPlayPersistence.loadLauncherBubbleSize(context))
        AirPlayPersistence.saveLauncherBubble(context, false)
        assertFalse(AirPlayPersistence.loadLauncherBubble(context))
    }

    @Test fun aHandEditedSizeComesBackOnTheListTheSettingsOffer() {
        prefs.edit().putInt("launcher_bubble_size", 15).apply()
        assertEquals(16, AirPlayPersistence.loadLauncherBubbleSize(context))
        prefs.edit().putInt("launcher_bubble_size", 0).apply()
        assertEquals(8, AirPlayPersistence.loadLauncherBubbleSize(context))
        prefs.edit().putInt("launcher_bubble_size", 99).apply()
        assertEquals(16, AirPlayPersistence.loadLauncherBubbleSize(context))
    }

    @Test fun theBubbleIsNotShownWithoutTheOverlayPermission() {
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(LauncherBubble.permitted(context))
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(LauncherBubble.permitted(context))
        ShadowSettings.setCanDrawOverlays(false)
    }
}
