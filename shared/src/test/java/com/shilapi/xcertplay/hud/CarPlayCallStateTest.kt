package com.shilapi.xcertplay.hud

import android.view.KeyEvent
import com.shilapi.xcertplay.hud.CarPlayCallKeyPolicy.Action
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayCallStateTest {
    private var now = 1_000L
    private val state = CarPlayCallState { now }

    private fun update(uuid: String, status: Int, name: String? = null, remote: String? = null) =
        Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            remote?.let { string(0, it) }
            name?.let { string(1, it) }
            u8(2, status)
            u8(3, 1)
            string(4, uuid)
        }

    @Test
    fun followsAnIncomingCallFromRingingToEnded() {
        assertTrue(state.accept(update("a", status = 2, name = "Mum")))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Mum"), state.current())

        now = 5_000L
        assertTrue(state.accept(update("a", status = 4, name = "Mum")))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 5_000L), state.current())

        // A repeated update keeps the time the call connected and changes nothing on the card.
        now = 9_000L
        assertFalse(state.accept(update("a", status = 4, name = "Mum")))
        assertTrue(state.accept(update("a", status = 0)))
        assertNull(state.current())
    }

    @Test
    fun anOutgoingCallDialsThenConnects() {
        state.accept(update("b", status = 1, remote = "+971500000000"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "+971500000000"), state.current())
        state.accept(update("b", status = 3, name = "Office"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "Office"), state.current())
        state.accept(update("b", status = 4))
        // The name sticks when a later update leaves it out.
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Office", 1_000L), state.current())
    }

    @Test
    fun aWaitingCallRingsOverTheActiveOneAndTheActiveOneReturns() {
        state.accept(update("a", status = 4, name = "Mum"))
        state.accept(update("b", status = 2, name = "Boss"))
        assertEquals(CarPlayCallCard.Phase.RINGING, state.current()?.phase)
        assertEquals("Boss", state.current()?.name)

        state.accept(update("b", status = 6))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 1_000L), state.current())
    }

    @Test
    fun otherMessagesAndClearLeaveNoCall() {
        assertFalse(state.accept(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) { u8(2, 2) }))
        assertNull(state.current())
        state.accept(update("a", status = 2, name = "Mum"))
        state.clear()
        assertNull(state.current())
    }

    @Test
    fun theNameFitsBydsCallCard() {
        assertArrayEquals("Mum".toByteArray(Charsets.UTF_16LE), CarPlayCallState.nameBytes("Mum"))
        val long = CarPlayCallState.nameBytes("A".repeat(40))
        assertEquals(CarPlayCallState.MAX_NAME_BYTES, long.size)
        // An emoji is never cut in half.
        val emoji = CarPlayCallState.nameBytes("A".repeat(29) + "😀")
        assertEquals("A".repeat(29), String(emoji, Charsets.UTF_16LE))
    }
}

class CarPlayCallKeyPolicyTest {
    private val ringing = CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Mum")
    private val active = CarPlayCallCard(CarPlayCallCard.Phase.ACTIVE, "Mum", 1L)

    private fun press(key: Int, call: CarPlayCallCard?, session: Boolean = true) =
        CarPlayCallKeyPolicy.onKey(key, true, call, session) to CarPlayCallKeyPolicy.onKey(key, false, call, session)

    @Test
    fun theCallKeyAnswersARingingCarPlayCall() {
        assertEquals(Action.CONSUME to Action.ANSWER, press(313, ringing))
        assertEquals(Action.CONSUME to Action.ANSWER, press(KeyEvent.KEYCODE_CALL, ringing))
        // During the call it must not open BYD's phone app over CarPlay, and does not hang up.
        assertEquals(Action.CONSUME to Action.CONSUME, press(313, active))
    }

    @Test
    fun theHangUpKeysEndOrDecline() {
        assertEquals(Action.CONSUME to Action.END, press(KeyEvent.KEYCODE_ENDCALL, ringing))
        assertEquals(Action.CONSUME to Action.END, press(314, active))
        assertEquals(Action.END, CarPlayCallKeyPolicy.onHangUpBroadcast(314, active, session = true))
        assertEquals(Action.END, CarPlayCallKeyPolicy.onHangUpBroadcast(309, ringing, session = true))
    }

    @Test
    fun trackKeysAnswerAndDeclineOnlyWhileACallRings() {
        // #272: the Song Pro DM-i's wheel has only previous/next for calls.
        assertEquals(Action.CONSUME to Action.ANSWER, press(KeyEvent.KEYCODE_MEDIA_NEXT, ringing))
        assertEquals(Action.CONSUME to Action.END, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ringing))
        assertEquals(Action.PASS to Action.PASS, press(KeyEvent.KEYCODE_MEDIA_NEXT, active))
        assertEquals(Action.PASS to Action.PASS, press(KeyEvent.KEYCODE_MEDIA_PREVIOUS, null))
    }

    @Test
    fun withoutACarPlayCallTheCarKeepsItsKeys() {
        assertEquals(Action.PASS to Action.PASS, press(313, null))
        assertEquals(Action.PASS to Action.PASS, press(313, ringing, session = false))
        assertEquals(Action.PASS to Action.PASS, press(KeyEvent.KEYCODE_VOLUME_UP, ringing))
        assertEquals(Action.PASS, CarPlayCallKeyPolicy.onHangUpBroadcast(314, null, session = true))
        assertEquals(Action.PASS, CarPlayCallKeyPolicy.onHangUpBroadcast(305, active, session = true))
    }
}
