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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GeelyClusterProjectionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aLeftoverIsOnlyHandedBackWhenDiPlayStillIsTheOneThatAsked() {
        assertEquals(StaleProjectionAction.HAND_BACK,
            staleProjectionAction(heldInProcess = false, claimed = true, mapEnabled = false,
                supported = true, projecting = { true }))
    }

    @Test fun nothingIsDoneWithoutAClaim() {
        var reads = 0
        assertEquals(StaleProjectionAction.NONE,
            staleProjectionAction(heldInProcess = false, claimed = false, mapEnabled = false,
                supported = true, projecting = { reads++; true }))
        assertEquals("the switch is not even read", 0, reads)
    }

    @Test fun theProcessHoldingTheProjectionNeverReleasesItsOwnClaim() {
        var reads = 0
        assertEquals(StaleProjectionAction.NONE,
            staleProjectionAction(heldInProcess = true, claimed = true, mapEnabled = false,
                supported = true, projecting = { reads++; false }))
        assertEquals(0, reads)
    }

    @Test fun aRunThatIsStillSetToProjectKeepsTheClaimForTheNextSession() {
        // DiPlay asserts the projection again as soon as its session is up and that frame replaces the
        // leftover. Handing the switch back first would take the wheel-key map off the cluster.
        var reads = 0
        assertEquals(StaleProjectionAction.NONE,
            staleProjectionAction(heldInProcess = false, claimed = true, mapEnabled = true,
                supported = true, projecting = { reads++; true }))
        assertEquals(0, reads)
    }

    @Test fun anotherHeadUnitForgetsAClaimItsSettingsRestored() {
        var reads = 0
        assertEquals(StaleProjectionAction.FORGET,
            staleProjectionAction(heldInProcess = false, claimed = true, mapEnabled = false,
                supported = false, projecting = { reads++; true }))
        assertEquals(0, reads)
    }

    @Test fun anOffSwitchForgetsTheClaimWithoutTouchingTheHeadUnit() {
        assertEquals(StaleProjectionAction.FORGET,
            staleProjectionAction(heldInProcess = false, claimed = true, mapEnabled = false,
                supported = true, projecting = { false }))
    }

    @Test fun anUnreadableSwitchKeepsTheClaimForTheNextRun() {
        assertEquals(StaleProjectionAction.NONE,
            staleProjectionAction(heldInProcess = false, claimed = true, mapEnabled = false,
                supported = true, projecting = { null }))
    }

    @Test fun theClaimIsNotPersistedUntilDiPlayAsksForTheProjection() {
        assertFalse(AirPlayPersistence.loadClusterProjectionHeld(context))
        AirPlayPersistence.saveClusterProjectionHeld(context, true)
        assertTrue(AirPlayPersistence.loadClusterProjectionHeld(context))
        AirPlayPersistence.saveClusterProjectionHeld(context, false)
        assertFalse(AirPlayPersistence.loadClusterProjectionHeld(context))
    }
}
