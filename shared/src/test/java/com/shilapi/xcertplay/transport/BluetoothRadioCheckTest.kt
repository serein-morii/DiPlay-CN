package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRadioCheckTest {
    @Test fun aRadioThatIsOnLetsTheAttemptGoOn() {
        assertNull(BluetoothRadioCheck.blocker(adapterPresent = true, enabled = true, pausedByDiPlay = false))
    }

    // An Allwinner T8 head unit came up with Bluetooth off. A fork build that still offered the
    // local-only hotspot made 94 attempts in under six hours; each started the hotspot and only
    // then failed on the radio.
    @Test fun aRadioThatIsOffStopsTheAttemptBeforeTheHotspot() {
        assertEquals(
            BluetoothRadioCheck.RADIO_OFF,
            BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false, pausedByDiPlay = false),
        )
    }

    // The pause is undone before the handshake; stopping here would never let that happen.
    @Test fun aRadioThatDiPlayPausedDoesNotStopTheAttempt() {
        assertNull(BluetoothRadioCheck.blocker(adapterPresent = true, enabled = false, pausedByDiPlay = true))
    }

    @Test fun aHeadUnitWithoutAnAdapterStopsTheAttemptWhateverElseIsKnown() {
        for (paused in listOf(false, true)) {
            assertEquals(
                BluetoothRadioCheck.NO_ADAPTER,
                BluetoothRadioCheck.blocker(adapterPresent = false, enabled = false, pausedByDiPlay = paused),
            )
        }
    }

    // CarPlayHostActivity shows the request in the app's language by finding these words.
    @Test fun theRequestCarriesTheWordsTheHostScreenLooksFor() {
        assertTrue(BluetoothRadioCheck.RADIO_OFF.contains(BluetoothRadioCheck.RADIO_OFF_MARK))
    }
}
