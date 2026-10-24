package com.shilapi.xcertplay.turncamera.sideview

/**
 * Pure decision layer of the turn side-view feature — no Android types, trivially testable.
 *
 * Priority order, each level overruling the ones below it:
 *   1. reverse gear          — the factory rear view owns both screens, everything closes;
 *   2. factory 360 foreground— same ownership, and a warm camera under it makes the 360 stutter;
 *   3. stale telemetry       — a reading older than WATCHDOG_MS hides the windows;
 *   4. speed below threshold — no side view while parking-speed manoeuvring;
 *   5. the blinker itself    — 2 shows left, 4 shows right, everything else (off, hazard, the
 *                              transient 9) shows nothing.
 *
 * The camera itself is wanted only while a single-side blinker is on at speed; keeping it open
 * at all times above the threshold loads the head unit. When the want ends, the engine holds the
 * camera warm for COOL_DOWN_MS so a blinker re-pulled inside that window reuses it.
 */

/** Which side's window is on screen. */
internal enum class SideViewSide { NONE, LEFT, RIGHT }

/** What one evaluated tick wants. */
internal data class SideViewVerdict(
    val show: SideViewSide,
    val warmCamera: Boolean,
)

/** Inputs the policy needs; nulls mean "unknown this tick", never "false". */
internal data class SideViewFacts(
    val blink: Int?,
    val speedKmh: Float?,
    val gear: Int?,
    val reverseGearCode: Int,
    val thresholdKmh: Int,
    val telemetryAgeMs: Long,
    val native360Foreground: Boolean,
)

internal fun sideViewVerdict(facts: SideViewFacts): SideViewVerdict {
    val reverse = facts.gear == facts.reverseGearCode
    val atSpeed = facts.speedKmh != null && facts.speedKmh >= facts.thresholdKmh
    val asked = sideForBlink(facts.blink)
    val blocked = reverse || facts.native360Foreground ||
        facts.telemetryAgeMs > WATCHDOG_MS || !atSpeed
    val show = if (blocked) SideViewSide.NONE else asked
    return SideViewVerdict(
        show = show,
        warmCamera = !reverse && !facts.native360Foreground && atSpeed && asked != SideViewSide.NONE,
    )
}

/** Blink mask verified on the Han DM-i: 1 off, 2 left, 4 right, 6 hazard, 9 transient. */
internal fun sideForBlink(blink: Int?): SideViewSide = when (blink) {
    2 -> SideViewSide.LEFT
    4 -> SideViewSide.RIGHT
    else -> SideViewSide.NONE
}

/**
 * Freshness bookkeeping for the loop. A snapshot counts as fresh only when all three primary
 * signals arrived; two failed reads in a row, or three seconds without any fresh snapshot, mean
 * the channel is gone rather than busy, and the caller tears the whole pipeline down.
 */
internal class SideViewFreshness {
    private var freshAt = 0L
    private var startedAt = 0L
    private var lastFreshGear: Int? = null
    private var failures = 0

    /** Age of the freshest complete snapshot; [Long.MAX_VALUE] before the first one. */
    var ageMs: Long = Long.MAX_VALUE
        private set
    var channelGone: Boolean = false
        private set

    /** A missing gear is not "not reverse" — callers fall back to this. */
    fun lastGear(): Int? = lastFreshGear

    fun start(now: Long) {
        freshAt = 0L
        startedAt = now
        failures = 0
        lastFreshGear = null
        ageMs = Long.MAX_VALUE
        channelGone = false
    }

    fun accept(snapshot: SideViewTelemetry.Snapshot?, now: Long) {
        if (snapshot == null || !snapshot.readOk) {
            failures++
        } else {
            failures = 0
            if (snapshot.complete) {
                freshAt = now
                lastFreshGear = snapshot.gear
            }
        }
        ageMs = if (freshAt != 0L) now - freshAt else Long.MAX_VALUE
        val silentFor = if (freshAt != 0L) now - freshAt else now - startedAt
        channelGone = failures >= READ_FAILURE_LIMIT || silentFor > CHANNEL_LOST_MS
    }
}

/** Tuning constants, validated on the car in October 2026. */
internal const val WATCHDOG_MS = 750L
internal const val COOL_DOWN_MS = 10_000L
internal const val FAST_TICK_MS = 150L
internal const val IDLE_TICK_MS = 1_000L
internal const val FIRST_FRAME_TIMEOUT_MS = 3_000L
internal const val SURFACE_TIMEOUT_MS = 8_000L
internal const val RETRY_DELAY_MS = 3_000L
internal const val REDISCOVER_MS = 60_000L
private const val READ_FAILURE_LIMIT = 2
private const val CHANNEL_LOST_MS = 3_000L

/** A shown window whose stream has been silent this long is frozen, not idle. */
internal const val FRAME_STALL_MS = 1_500L

internal fun frameStalled(shown: Boolean, deliveredFrames: Boolean, lastFrameAt: Long, shownAt: Long, now: Long): Boolean =
    shown && deliveredFrames && lastFrameAt > 0 && now - maxOf(lastFrameAt, shownAt) >= FRAME_STALL_MS

/**
 * Retry pacing after a camera failure: a NEW blinker side cancels the wait once (the driver is
 * asking right now), a held blinker keeps waiting. The remembered side survives teardowns — a
 * reset there would turn every held blinker into a fresh edge and the windows would flash.
 */
internal class SideViewRetryGate {
    private var retryAfter = 0L
    private var rememberedSide = SideViewSide.NONE

    fun observed(side: SideViewSide) {
        if (side == rememberedSide) return
        if (side != SideViewSide.NONE) retryAfter = 0L
        rememberedSide = side
    }

    fun failed(now: Long) {
        retryAfter = now + RETRY_DELAY_MS
    }

    fun waiting(now: Long) = now < retryAfter
}
