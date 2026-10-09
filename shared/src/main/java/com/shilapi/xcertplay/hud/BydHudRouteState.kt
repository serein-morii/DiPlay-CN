package com.shilapi.xcertplay.hud

internal data class BydHudGuidance(
    val distanceMeters: Int,
    /** Native HUD arrow (field 28). */
    val maneuver: Int,
    /** Gaode maneuver code: selects the HUD icon (field 8). */
    val gaode: Int = 0,
    val road: String = "",
    val arrivalEpochSeconds: Long? = null,
)

internal data class BydAppleManeuver(
    val distanceMeters: Int,
    val type: Int,
    val drivingSide: Int,
    val road: String = "",
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    val laneHighlight: Int = -1,
    val lanes: List<Int> = emptyList(),
)

internal enum class BydHudRouteChange {
    NONE,
    GUIDANCE,
    CLEAR,
}

/** Decodes the iAP2 route-guidance subset needed by the BYD windshield HUD and cluster. */
internal class BydHudRouteState(
    private val nanoTime: () -> Long = System::nanoTime,
    private val staleRouteNs: Long = STALE_ROUTE_NS,
    private val emptyListHideNs: Long = EMPTY_LIST_HIDE_NS,
    /**
     * The dashboard overlay keeps the last instruction across a wireless session drop: the iPhone
     * often sends NoRouteSet (0) while the tunnel is tearing down, which is not a real arrival.
     * Arrived (2) still ends the route. The overlay's own stale window retires a truly ended one.
     */
    private val keepAcrossNoRoute: Boolean = false,
) {
    private data class Maneuver(val type: Int, val drivingSide: Int, val afterRoad: String)

    private val maneuvers = mutableMapOf<Int, Maneuver>()
    private var routeActive = false
    private var activeIndex = -1
    private var distanceMeters = 0
    private var currentRoad = ""
    private var arrivalEpochSeconds: Long? = null
    private var remainingSeconds: Long? = null
    private var remainingMeters: Long? = null
    private var laneHighlight = -1
    private var lanes: List<Int> = emptyList()
    private var emptyListSinceNs: Long? = null
    private var lastRouteUpdateNs: Long? = null

    fun accept(messageId: Int, payload: ByteArray): BydHudRouteChange {
        if (!validTlvs(payload)) return BydHudRouteChange.NONE
        return when (messageId) {
            ROUTE_GUIDANCE_UPDATE -> parseRouteUpdate(payload)
            ROUTE_GUIDANCE_MANEUVER_UPDATE -> parseManeuverUpdate(payload)
            LANE_GUIDANCE_UPDATE -> parseLaneUpdate(payload)
            else -> BydHudRouteChange.NONE
        }
    }

    fun current(): BydHudGuidance? {
        val maneuver = activeManeuver() ?: return null
        val gaode = BydManeuverCodes.gaode(maneuver.type, maneuver.drivingSide)
        return BydHudGuidance(
            distanceMeters = distanceMeters,
            maneuver = BydManeuverCodes.hudArrow(gaode),
            gaode = gaode,
            road = roadFor(maneuver),
            arrivalEpochSeconds = arrivalEpochSeconds,
        )
    }

    /** Next maneuver as Apple sent it, for outputs with a richer icon set than the HUD. */
    fun currentApple(): BydAppleManeuver? {
        val maneuver = activeManeuver() ?: return null
        return BydAppleManeuver(
            distanceMeters, maneuver.type, maneuver.drivingSide,
            roadFor(maneuver), remainingSeconds, remainingMeters, arrivalEpochSeconds,
            laneHighlight, lanes,
        )
    }

    fun clear(): Boolean {
        val wasActive = routeActive
        routeActive = false
        activeIndex = -1
        distanceMeters = 0
        currentRoad = ""
        arrivalEpochSeconds = null
        remainingSeconds = null
        remainingMeters = null
        laneHighlight = -1
        lanes = emptyList()
        emptyListSinceNs = null
        lastRouteUpdateNs = null
        maneuvers.clear()
        return wasActive
    }

    private fun activeManeuver(): Maneuver? {
        if (!routeActive || activeIndex < 0) return null
        val updated = lastRouteUpdateNs ?: return null
        if (nanoTime() - updated >= staleRouteNs) return null
        val emptySince = emptyListSinceNs
        if (emptySince != null && nanoTime() - emptySince >= emptyListHideNs) return null
        return maneuvers[activeIndex]
    }

    // The road the driver turns onto is what the next instruction is about; fall back to the current one.
    private fun roadFor(maneuver: Maneuver): String = maneuver.afterRoad.ifEmpty { currentRoad }

    /** Apple destination maneuver types, or remaining distance already at the pin. */
    private fun isAtDestination(): Boolean {
        if (remainingMeters == 0L || distanceMeters == 0) return true
        val type = maneuvers[activeIndex]?.type ?: return false
        return type in DESTINATION_MANEUVER_TYPES
    }

    private fun parseRouteUpdate(data: ByteArray): BydHudRouteChange {
        // A teardown NoRouteSet is not fresh guidance. Do not let repeated teardown frames
        // extend the retained instruction's lifetime or replace its road/arrival metadata.
        // Arrival itself is often sent as NoRouteSet (0) rather than Arrived (2): if the
        // current instruction is already the destination (or remaining distance is 0),
        // treat that NoRouteSet as the real end so the card does not sit on the last turn.
        if (keepAcrossNoRoute) {
            var noRoute = false
            forEachTlv(data) { type, value, valueLength ->
                if (type == 0x01 && valueLength >= 1) noRoute = data[value] == 0.toByte()
            }
            if (noRoute) {
                if (isAtDestination()) {
                    return if (clear()) BydHudRouteChange.CLEAR else BydHudRouteChange.NONE
                }
                return BydHudRouteChange.NONE
            }
        }
        lastRouteUpdateNs = nanoTime()
        var state: Int? = null
        var distance: Int? = null
        var firstManeuver: Int? = null
        var listPresent = false
        forEachTlv(data) { type, value, valueLength ->
            when {
                type == 0x01 && valueLength >= 1 -> state = data[value].toInt() and 0xff
                type == 0x03 -> currentRoad = utf8(data, value, valueLength)
                type == 0x05 && valueLength >= 8 -> arrivalEpochSeconds = u64(data, value).takeIf { it > 0 }
                type == 0x06 && valueLength >= 8 -> remainingSeconds = u64(data, value).takeIf { it >= 0 }
                type == 0x07 && valueLength >= 4 -> remainingMeters = u32(data, value)
                type == 0x0a && valueLength >= 4 -> {
                    distance = u32(data, value).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                type == 0x0d -> {
                    listPresent = true
                    if (valueLength >= 2) firstManeuver = u16(data, value)
                }
            }
        }

        // Only NoRouteSet (0) and Arrived (2) end the route. A wireless handoff often sends
        // NoRouteSet while the session is still coming back — keep the overlay instruction then.
        if (state == 0 || state == 2) {
            return if (clear()) BydHudRouteChange.CLEAR else BydHudRouteChange.NONE
        }
        // The iPhone briefly sends an empty current list every few seconds and while rerouting. Keep the last
        // maneuver (and the cached 0x5202 details, which are never resent) and hide it only if the list stays
        // empty; the bridges' 1 s tick clears the outputs once current() turns null.
        if (listPresent && firstManeuver == null) {
            if (emptyListSinceNs == null) emptyListSinceNs = nanoTime()
            return BydHudRouteChange.NONE
        }
        if (firstManeuver != null) emptyListSinceNs = null
        if (state != null) routeActive = true
        if (firstManeuver != null) {
            activeIndex = firstManeuver!!
            routeActive = true
        }
        if (distance != null) distanceMeters = distance!!.coerceAtLeast(0)
        return if (current() != null) BydHudRouteChange.GUIDANCE else BydHudRouteChange.NONE
    }

    private fun parseManeuverUpdate(data: ByteArray): BydHudRouteChange {
        var index: Int? = null
        var type: Int? = null
        var drivingSide = 0
        var afterRoad = ""
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 2 -> index = u16(data, value)
                id == 0x03 && valueLength >= 1 -> type = data[value].toInt() and 0xff
                id == 0x04 -> afterRoad = utf8(data, value, valueLength)
                id == 0x08 && valueLength >= 1 -> drivingSide = data[value].toInt() and 0xff
            }
        }
        if (index != null && type != null) maneuvers[index!!] = Maneuver(type!!, drivingSide, afterRoad)
        return if (current() != null) BydHudRouteChange.GUIDANCE else BydHudRouteChange.NONE
    }

    private fun parseLaneUpdate(data: ByteArray): BydHudRouteChange {
        var highlight = -1
        val next = mutableListOf<Int>()
        forEachTlv(data) { id, value, valueLength ->
            when {
                id == 0x01 && valueLength >= 1 -> highlight = data[value].toInt() and 0xff
                id == 0x02 && valueLength >= 1 -> next += data[value].toInt() and 0xff
                id == 0x03 -> {
                    var offset = 0
                    while (offset + 1 <= valueLength) {
                        next += data[value + offset].toInt() and 0xff
                        offset++
                    }
                }
            }
        }
        lanes = next.take(8)
        laneHighlight = if (lanes.isEmpty()) -1 else highlight.coerceIn(-1, lanes.lastIndex)
        return if (current() != null) BydHudRouteChange.GUIDANCE else BydHudRouteChange.NONE
    }

    private inline fun forEachTlv(data: ByteArray, block: (Int, Int, Int) -> Unit) {
        var offset = 0
        while (offset < data.size) {
            val length = u16(data, offset)
            block(u16(data, offset + 2), offset + TLV_HEADER_BYTES, length - TLV_HEADER_BYTES)
            offset += length
        }
    }

    private fun validTlvs(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            if (offset + TLV_HEADER_BYTES > data.size) return false
            val length = u16(data, offset)
            if (length < TLV_HEADER_BYTES || length > data.size - offset) return false
            offset += length
        }
        return offset == data.size
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private fun u64(data: ByteArray, offset: Int): Long = (u32(data, offset) shl 32) or u32(data, offset + 4)

    // iAP2 utf8 parameters are NUL-terminated.
    private fun utf8(data: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && data[end] != 0.toByte()) end++
        return String(data, offset, end - offset, Charsets.UTF_8).trim()
    }

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = 0x5201
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = 0x5202
        const val LANE_GUIDANCE_UPDATE = 0x5204
        private const val TLV_HEADER_BYTES = 4
        private const val STALE_ROUTE_NS = 30_000_000_000L
        private const val EMPTY_LIST_HIDE_NS = 3_000_000_000L
        /** Apple iAP2 RouteGuidanceManeuverType values that mean the destination pin. */
        private val DESTINATION_MANEUVER_TYPES = setOf(10, 12, 24, 25, 27)
    }
}
