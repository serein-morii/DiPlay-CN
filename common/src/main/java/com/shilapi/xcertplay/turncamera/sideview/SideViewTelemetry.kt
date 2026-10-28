package com.shilapi.xcertplay.turncamera.sideview

import android.content.Context
import com.shilapi.xcertplay.turncamera.sideview.adb.AdbKeys
import com.shilapi.xcertplay.turncamera.sideview.adb.LocalAdb

/**
 * Vehicle signal source for the turn side-view feature, written for DiPlay-CN.
 *
 * The head unit's autoservice Binder answers fid reads only for privileged callers, so the reads
 * go through the car's own ADB (127.0.0.1:5555 — the same channel DiPlay already uses for the
 * cluster song card, sharing the same approved key). One interactive shell stays open for the
 * loop; each tick is a single round trip that reads every signal at once:
 *
 *   service call autoservice <tx> i32 <device> i32 <fid>   (one per signal, joined by ';')
 *
 * Addresses and encodings below are the values verified live on the 2025 Han DM-i (DiLink 5.0)
 * in October 2026; every value stays overridable through [SideViewPrefs] so a different trim can
 * be retuned from the settings row without a rebuild.
 */
internal class SideViewTelemetry(context: Context) {
    private val appContext = context.applicationContext
    private val adb = LocalAdb(AdbKeys.load(appContext))

    /** Interactive shell reused by every tick; rebuilt after any failure. */
    private var shell: LocalAdb.InteractiveShell? = null

    /** One poll: null fields for signals that answered a sentinel or failed. */
    data class Snapshot(
        val blink: Int?,
        val speedKmh: Float?,
        val gear: Int?,
        val bsdLeft: Int?,
        val bsdRight: Int?,
        /** False when the transport itself failed — the caller treats that as "no data". */
        val readOk: Boolean,
    ) {
        val complete: Boolean get() = blink != null && speedKmh != null && gear != null
    }

    fun close() {
        shell?.close()
        shell = null
        adb.close()
    }

    fun poll(addresses: Addresses, includeBsd: Boolean): Snapshot? {
        val calls = buildString {
            append("service call autoservice 5 i32 ${addresses.blinkDevice} i32 ${addresses.blinkFid}")
            append("; service call autoservice 7 i32 ${addresses.speedDevice} i32 ${addresses.speedFid}")
            append("; service call autoservice 5 i32 ${addresses.gearDevice} i32 ${addresses.gearFid}")
            if (includeBsd) {
                append("; service call autoservice 5 i32 ${addresses.bsdDevice} i32 ${addresses.bsdLeftFid}")
                append("; service call autoservice 5 i32 ${addresses.bsdDevice} i32 ${addresses.bsdRightFid}")
            }
            append("; echo done")
        }
        val reply = exchange(calls) ?: return null
        // Non-zero exception codes keep their slot as null so later signals cannot shift onto
        // the wrong field when one call in the middle fails.
        val words = Regex("""Parcel\(\s*([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})""")
            .findAll(reply)
            .map { match ->
                val exception = match.groupValues[1].toLongOrNull(16)
                val value = match.groupValues[2].toLongOrNull(16)
                if (exception == 0L) value else null
            }
            .toList()
        val needed = if (includeBsd) 5 else 3
        if (words.size < needed) return null
        return Snapshot(
            blink = words[0]?.let(::cleanInt),
            speedKmh = words[1]?.let(::cleanFloat),
            gear = words[2]?.let(::cleanInt),
            bsdLeft = if (includeBsd) words[3]?.let(::cleanInt) else null,
            bsdRight = if (includeBsd) words[4]?.let(::cleanInt) else null,
            readOk = true,
        )
    }

    private fun exchange(request: String): String? {
        var stream = shell
        if (stream == null) {
            if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return null
            stream = adb.openShell("sh") ?: return null
            shell = stream
        }
        val reply = stream.exchangeBounded(request, READ_TIMEOUT_MS)
        if (reply == null) shell = null // the bounded exchange already closed it
        return reply
    }

    /** Addresses in force, from the preference overrides or the verified defaults. */
    data class Addresses(
        val blinkDevice: Int, val blinkFid: Int,
        val speedDevice: Int, val speedFid: Int,
        val gearDevice: Int, val gearFid: Int,
        val bsdDevice: Int, val bsdLeftFid: Int, val bsdRightFid: Int,
    )

    companion object {
        private const val READ_TIMEOUT_MS = 2_000

        /**
         * Verified on the Han DM-i (Oct 2026): blink mask 1=off / 2=left / 4=right / 6=hazard,
         * gear 1=P / 2=R / 3=N / 4=D, speed is a tx-7 float, BSD radar reports 2 when an object
         * sits in the blind spot.
         */
        val VERIFIED = Addresses(
            blinkDevice = 1004, blinkFid = 950009900,
            speedDevice = 1013, speedFid = -1807745016,
            gearDevice = 1011, gearFid = 555745336,
            bsdDevice = 1038, bsdLeftFid = 1098907664, bsdRightFid = 1098907666,
        )

        /** Autoservice sentinel words (feature link down, not initialised, protocol rejects). */
        fun cleanInt(raw: Long): Int? = when (raw.toInt()) {
            0x0000FFFF, 0x000FFFFF, -10013, -10011 -> null
            else -> raw.toInt()
        }

        /** tx 7 answers float bits; NaN/Inf/-1.0 mean "no value". */
        fun cleanFloat(raw: Long): Float? {
            val value = Float.fromBits(raw.toInt())
            return if (value.isNaN() || value.isInfinite() || value == -1.0f) null else value
        }

        /** One-shot read of the blink mask, for the settings row's signal check. */
        fun blinkOnce(context: Context, addresses: Addresses): Int? {
            val oneShot = LocalAdb(AdbKeys.load(context.applicationContext))
            try {
                if (oneShot.connect(mayAsk = false) != LocalAdb.Access.READY) return null
                val raw = oneShot.shell(
                    "service call autoservice 5 i32 ${addresses.blinkDevice} i32 ${addresses.blinkFid}")
                    .orEmpty()
                val match = Regex("""Parcel\(\s*00000000\s+([0-9a-fA-F]{8})""").find(raw) ?: return null
                return match.groupValues[1].toLongOrNull(16)?.let(::cleanInt)
            } finally {
                runCatching { oneShot.close() }
            }
        }
    }
}
