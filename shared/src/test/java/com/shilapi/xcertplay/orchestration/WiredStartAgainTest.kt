package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain
import org.junit.Assert.assertEquals
import org.junit.Test

// #889, #610: an iPhone that still held an ended DiPlay's session opened the USB network 15–26 s after the
// start request, after it had stopped trying DiPlay's AirPlay listener.
class WiredStartAgainTest {
    private val second = 1_000_000_000L

    private fun decide(now: Long, opened: Long?, active: Boolean = false, tried: Boolean = false, sent: Long = 0) =
        WiredStartAgain.decide(now, sent, opened, active, tried)

    @Test fun nothingHappensWhileTheIphoneKeepsTheLinkClosed() {
        assertEquals(StartAgain.WAIT, decide(now = 40 * second, opened = null))
    }

    @Test fun anIphoneThatStaysSilentOnTheOpenedLinkIsAskedAgain() {
        assertEquals(StartAgain.WAIT, decide(now = 21 * second, opened = 20 * second))
        assertEquals(StartAgain.SEND, decide(now = 22 * second, opened = 20 * second))
    }

    @Test fun anIphoneThatConnectsAfterTheLinkOpensIsLeftAlone() {
        assertEquals(StartAgain.DONE, decide(now = 22 * second, opened = 20 * second, tried = true))
    }

    // #888, #890: the link was open before the request, so the iPhone's own attempt got through.
    @Test fun aLinkOpenBeforeTheRequestIsNotAskedAgain() {
        assertEquals(StartAgain.DONE, decide(now = 10 * second, opened = 3 * second, sent = 5 * second))
    }

    @Test fun anActiveSessionEndsTheQuestion() {
        assertEquals(StartAgain.DONE, decide(now = 22 * second, opened = 20 * second, active = true))
        assertEquals(StartAgain.DONE, decide(now = 5 * second, opened = null, active = true))
    }
}
