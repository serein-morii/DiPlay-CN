package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.setup.DiLinkGeneration
import com.shilapi.xcertplay.setup.SetupGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AudioChannelPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)

    @Before fun clearPreferences() {
        prefs.edit().clear().apply()
    }

    @Test fun freshInstallUsesAutomaticRouting() {
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        org.junit.Assert.assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context))
    }

    @Test fun audioFocusAutoYieldCanBeSavedAndRestored() {
        AirPlayPersistence.saveAudioFocusAutoYield(context, false)
        org.junit.Assert.assertFalse(AirPlayPersistence.loadAudioFocusAutoYield(context))
        AirPlayPersistence.saveAudioFocusAutoYield(context, true)
        org.junit.Assert.assertTrue(AirPlayPersistence.loadAudioFocusAutoYield(context))
    }

    @Test fun legacyNavigationChannelIsInheritedWithoutChangingMedia() {
        AirPlayPersistence.saveNavigationStreamType(context, 15)
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
        assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
    }

    @Test fun explicitNavigationChannelTakesPrecedenceOverLegacySetting() {
        AirPlayPersistence.saveNavigationStreamType(context, 14)
        AirPlayPersistence.saveNavigationAudioChannel(context, 15)
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun explicitAutomaticRoutingTakesPrecedenceOverLegacySetting() {
        AirPlayPersistence.saveNavigationStreamType(context, 15)
        AirPlayPersistence.saveNavigationAudioChannel(context, 0)
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun extendedChannelsCanBeSavedForMediaAndNavigation() {
        for (channel in listOf(11, 14, 15, 20)) {
            AirPlayPersistence.saveMediaAudioChannel(context, channel)
            AirPlayPersistence.saveNavigationAudioChannel(context, channel)
            assertEquals(channel, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(channel, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun existingExtendedChannelsRemainAvailable() {
        prefs.edit().putInt("media_audio_channel", 15).putInt("navigation_audio_channel", 15).apply()
        assertEquals(15, AirPlayPersistence.loadMediaAudioChannel(context))
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun invalidSavedChannelsUseAutomaticRouting() {
        for (channel in listOf(-1, 21)) {
            AirPlayPersistence.saveMediaAudioChannel(context, channel)
            AirPlayPersistence.saveNavigationAudioChannel(context, channel)
            assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun invalidStoredChannelsUseAutomaticRouting() {
        AirPlayPersistence.saveNavigationStreamType(context, 15)
        for (channel in listOf(-1, 21)) {
            prefs.edit().putInt("media_audio_channel", channel).putInt("navigation_audio_channel", channel).apply()
            assertEquals(0, AirPlayPersistence.loadMediaAudioChannel(context))
            assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
        }
    }

    @Test fun invalidLegacyChannelUsesAutomaticRouting() {
        AirPlayPersistence.saveNavigationStreamType(context, 21)
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun setupGuideDefaultIsOnlyAppliedOnce() {
        assertTrue(SetupGuide.applyGenerationDefaults(context, DiLinkGeneration.DILINK_3))
        assertEquals(14, AirPlayPersistence.loadNavigationAudioChannel(context))

        assertFalse(SetupGuide.applyGenerationDefaults(context, DiLinkGeneration.DILINK_5))
        assertEquals(14, AirPlayPersistence.loadNavigationAudioChannel(context))

        AirPlayPersistence.saveNavigationAudioChannel(context, 20)
        assertFalse(SetupGuide.applyGenerationDefaults(context, DiLinkGeneration.DILINK_4))
        assertEquals(20, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun unknownGenerationLeavesTheAutomaticDefaultUnstored() {
        assertFalse(SetupGuide.applyGenerationDefaults(context, DiLinkGeneration.UNKNOWN))
        assertFalse(prefs.contains("navigation_audio_channel"))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))

        assertTrue(SetupGuide.applyGenerationDefaults(context, DiLinkGeneration.DILINK_5))
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
    }

    @Test fun onlyAFirstSetupAppliesTheGenerationDefault() {
        val guide = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        guide.edit().clear().commit()
        // Set up before the guide existed: a saved iPhone and automatic routing stay as they are.
        assertFalse(SetupGuide.applyFirstSetupDefaults(context, DiLinkGeneration.DILINK_4, phoneChosen = true))
        assertFalse(prefs.contains("navigation_audio_channel"))
        // Re-running the guide after it was finished once.
        SetupGuide.markSeen(context)
        assertFalse(SetupGuide.applyFirstSetupDefaults(context, DiLinkGeneration.DILINK_4, phoneChosen = false))
        assertFalse(prefs.contains("navigation_audio_channel"))
        // A new install confirming its car.
        guide.edit().clear().commit()
        assertTrue(SetupGuide.applyFirstSetupDefaults(context, DiLinkGeneration.DILINK_4, phoneChosen = false))
        assertEquals(14, AirPlayPersistence.loadNavigationAudioChannel(context))
        guide.edit().clear().commit()
    }

    @Test fun setupGuideDefaultPreservesExistingNewAndLegacySettings() {
        AirPlayPersistence.saveNavigationAudioChannel(context, 0)
        assertFalse(SetupGuide.applyGenerationDefaults(context, DiLinkGeneration.DILINK_5))
        assertEquals(0, AirPlayPersistence.loadNavigationAudioChannel(context))

        prefs.edit().clear().apply()
        AirPlayPersistence.saveNavigationStreamType(context, 15)
        assertFalse(SetupGuide.applyGenerationDefaults(context, DiLinkGeneration.DILINK_3))
        assertEquals(15, AirPlayPersistence.loadNavigationAudioChannel(context))
    }
}
