package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList

/**
 * One lane from iAP2 `0x5204 LaneGuidanceInfoUpdate`.
 *
 * <p>Field ids come from the iOS `AccessoryNavigation.framework` and the RGI hook's TLV tables:
 * outer id 2 carries a lane, and inside it 0 = index, 1 = status, 2 = angle, 3 = angle-highlight.
 * Angles are signed degrees: 0 straight, positive right, negative left (measured on the car).
 */
data class LaneGuidanceLane(
    val index: Int,
    val status: Int,
    val angle: Int,
    val highlightAngle: Int?,
) {
    /** The car marks the lane it recommends with status 2 (measured on the car). */
    val recommended: Boolean get() = status == 2
}

/** Parser for the lane list in iAP2 `0x5204`. */
object Iap2LaneGuidance {

    private const val LANE_INFORMATION = 0x0002
    private const val FIELD_INDEX = 0x0000
    private const val FIELD_STATUS = 0x0001
    private const val FIELD_ANGLE = 0x0002
    private const val FIELD_ANGLE_HIGHLIGHT = 0x0003

    /**
     * Every lane carried by one `0x5204` payload. A malformed payload yields an empty list rather
     * than an exception: lane guidance is optional and must never break the session.
     */
    fun parse(payload: ByteArray): List<LaneGuidanceLane> {
        val outer = runCatching { Iap2ParameterList.parse(payload) }.getOrNull() ?: return emptyList()
        return outer.all(LANE_INFORMATION).mapNotNull { parameter ->
            val fields = runCatching { Iap2ParameterList.parse(parameter.payload) }.getOrNull()
                ?: return@mapNotNull null
            LaneGuidanceLane(
                index = fields.first(FIELD_INDEX)?.u16() ?: 0,
                status = fields.first(FIELD_STATUS)?.u8() ?: 0,
                angle = fields.first(FIELD_ANGLE)?.s16() ?: 0,
                highlightAngle = fields.first(FIELD_ANGLE_HIGHLIGHT)?.s16(),
            )
        }.sortedBy { it.index }
    }

    private fun Iap2Parameter.u16(): Int =
        if (payload.size >= 2) ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff) else 0

    private fun Iap2Parameter.u8(): Int = payload.firstOrNull()?.toInt()?.and(0xff) ?: 0

    private fun Iap2Parameter.s16(): Int = u16().let { if (it and 0x8000 != 0) it - 0x10000 else it }
}
