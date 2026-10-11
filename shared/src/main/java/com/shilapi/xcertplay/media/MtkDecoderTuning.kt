package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.os.Build

/** Optional MediaTek decoder modes exposed by old MTK ACodec implementations. */
internal enum class MtkDecoderMode(val diagnosticName: String) {
    NONE("off"),
    LOW_LATENCY("low-latency"),
    LOW_LATENCY_NO_REORDER("low-latency+no-reorder"),
    ;

    fun fallback(): MtkDecoderMode = when (this) {
        LOW_LATENCY_NO_REORDER -> LOW_LATENCY
        LOW_LATENCY -> NONE
        NONE -> NONE
    }

    fun attempts(): List<MtkDecoderMode> = when (this) {
        LOW_LATENCY_NO_REORDER -> listOf(LOW_LATENCY_NO_REORDER, LOW_LATENCY, NONE)
        LOW_LATENCY -> listOf(LOW_LATENCY, NONE)
        NONE -> listOf(NONE)
    }
}

/** Detection and tuning for legacy MTK OMX and dedicated Codec2 low-latency decoders. */
object MtkDecoderTuning {
    const val LOW_LATENCY_KEY = "vdec-lowlatency"

    // This is the spelling used by MTK's ACodec even though it maps to NoReorderMode.
    const val NO_REORDER_KEY = "vdec-no-record"

    fun isDecoderName(name: String): Boolean =
        name.startsWith("OMX.MTK.", ignoreCase = true) ||
            name.startsWith("c2.mtk.", ignoreCase = true)

    /** The published vdec-* bridge is an ACodec/OMX extension, not a Codec2 vendor parameter. */
    fun supportsLegacyAcodecKeys(name: String): Boolean =
        name.startsWith("OMX.MTK.", ignoreCase = true)

    fun isCodec2LowLatencyDecoder(name: String, advertisesLowLatency: Boolean): Boolean =
        name.startsWith("c2.mtk.", ignoreCase = true) &&
            (advertisesLowLatency || name.endsWith(".lowlatency", ignoreCase = true) ||
                name.endsWith(".low_latency", ignoreCase = true))

    /** Prefer a dedicated MTK Codec2 low-latency component; otherwise use the default legacy OMX decoder. */
    internal fun selectedDecoder(candidates: List<MtkDecoderCandidate>): MtkDecoderCandidate? =
        candidates.firstOrNull { isCodec2LowLatencyDecoder(it.name, it.lowLatency) }
            ?: candidates.firstOrNull()?.takeIf { supportsLegacyAcodecKeys(it.name) }

    /** The MTK decoder path to expose for [mime], or null when neither safe path is available. */
    fun selectedDecoderName(mime: String): String? = candidates(mime).let(::selectedDecoder)?.name

    /** A dedicated Codec2 low-latency component for [mime], selected explicitly instead of using ACodec keys. */
    fun codec2LowLatencyDecoderName(mime: String): String? = candidates(mime)
        .firstOrNull { isCodec2LowLatencyDecoder(it.name, it.lowLatency) }?.name

    /** The default decoder for [mime] only when it can consume MTK's legacy ACodec keys. */
    fun legacyAcodecDecoderName(mime: String): String? = candidates(mime)
        .firstOrNull()?.name?.takeIf(::supportsLegacyAcodecKeys)

    // The codec list does not change while DiPlay runs, and Settings asks on every render.
    private val candidatesByMime = java.util.concurrent.ConcurrentHashMap<String, List<MtkDecoderCandidate>>()

    private fun candidates(mime: String): List<MtkDecoderCandidate> =
        candidatesByMime.getOrPut(mime.lowercase()) { listCandidates(mime) }

    private fun listCandidates(mime: String): List<MtkDecoderCandidate> = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.mapNotNull { info ->
            if (info.isEncoder || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias) ||
                info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@mapNotNull null
            val lowLatency = runCatching {
                info.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            }.getOrDefault(false)
            MtkDecoderCandidate(info.name, lowLatency)
        }
    }.getOrDefault(emptyList())
}

internal data class MtkDecoderCandidate(val name: String, val lowLatency: Boolean = false)

internal fun appliedMtkDecoderMode(requested: MtkDecoderMode, codecName: String): MtkDecoderMode =
    requested.takeIf { MtkDecoderTuning.supportsLegacyAcodecKeys(codecName) } ?: MtkDecoderMode.NONE

internal fun MtkDecoderMode.formatKeys(): List<String> = when (this) {
    MtkDecoderMode.NONE -> emptyList()
    MtkDecoderMode.LOW_LATENCY -> listOf(MtkDecoderTuning.LOW_LATENCY_KEY)
    MtkDecoderMode.LOW_LATENCY_NO_REORDER ->
        listOf(MtkDecoderTuning.LOW_LATENCY_KEY, MtkDecoderTuning.NO_REORDER_KEY)
}
