package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.transport.Iap2WiredControlClient.StartAgain

/**
 * Whether DiPlay sends `carplay-start-session` again on USB.
 *
 * The iPhone tries DiPlay's AirPlay listener right after the request, for about 7 s, and does not try again on its
 * own. While it still holds the CarPlay session of a DiPlay that ended without closing it (DiPlay stopped or
 * replaced while connected), it keeps the NCM link NAKed 15–26 s after the request: every SYN-ACK is dropped and
 * the attempt fails at [WiredStartWatchdog.TIMEOUT_MILLIS] (#889, #610). Once the link opens, a second request
 * makes it connect at once. A link that was open before the request let the iPhone's own attempt through, as on head
 * units where it opens first (#888, #890), so the request is not repeated there.
 */
internal object WiredStartAgain {
    /** How long the iPhone may stay silent on the opened link before the request is repeated. */
    const val QUIET_MILLIS = 2_000L

    fun decide(
        nowNanos: Long,
        startSentAtNanos: Long,
        linkOpenedAtNanos: Long?,
        sessionActive: Boolean,
        connectionAttemptSinceLinkOpened: Boolean,
    ): StartAgain = when {
        sessionActive || connectionAttemptSinceLinkOpened -> StartAgain.DONE
        linkOpenedAtNanos == null -> StartAgain.WAIT
        linkOpenedAtNanos - startSentAtNanos < 0 -> StartAgain.DONE
        nowNanos - linkOpenedAtNanos < QUIET_MILLIS * NANOS_PER_MILLI -> StartAgain.WAIT
        else -> StartAgain.SEND
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
