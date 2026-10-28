package com.shilapi.xcertplay.turncamera

import dalvik.system.PathClassLoader
import java.io.File
import java.lang.reflect.Method

/**
 * Read-only AVM camera enumeration for calibration (phase B evidence gathering).
 *
 * Mirrors what the settings UI's capture flow needs from the vendor stack WITHOUT opening
 * any camera: the camera count, the tags this firmware knows (both the fleet-known set and
 * whatever getValidCameraTag reports), the id behind each tag and its default preview size.
 * Facts verified on other models (BYDMate/Leopard 3 research, PolyForm-licensed project —
 * facts only, no code copied): tags pano_h/pano_l/apa/byd_apa; classes may live on the app
 * class path or in /system/framework/bmmcamera.jar; a jar may only ever have ONE loader
 * (ART binds its native library to the first).
 *
 * Everything is logged verbatim into [journal]; nothing here renders, opens or starts.
 */
internal class TurnCameraAvmEnumeration(
    /** Class lookup with an optional loader; null loader means the app class path. */
    private val classLookup: (String, ClassLoader?) -> Class<*> = { name, loader ->
        if (loader == null) Class.forName(name, false, TurnCameraAvmEnumeration::class.java.classLoader)
        else Class.forName(name, true, loader)
    },
    private val jarExists: (String) -> Boolean = { File(it).isFile },
    private val jarLoader: (String) -> ClassLoader = { PathClassLoader(it, TurnCameraAvmEnumeration::class.java.classLoader) },
) {
    data class CameraId(val tag: String, val id: Int, val width: Int?, val height: Int?, val fromFirmwareTags: Boolean)

    val journal = mutableListOf<String>()
    val cameras = mutableListOf<CameraId>()
    var cameraCount: Int? = null
        private set

    /** One loader per jar for the lifetime of this process, so a rerun cannot fail on natives. */
    private var jarClassLoader: ClassLoader? = null
    private var infoClass: Class<*>? = null
    private var infoLoader: ClassLoader? = null

    fun run(): Boolean {
        try {
            val info = lookupInfo() ?: return false
            cameraCount = (info.static("getCameraNumbers") as? Number)?.toInt().also {
                journal += "getCameraNumbers=$it"
            }
            val firmwareTags = runCatching {
                (info.static("getValidCameraTag") as? Any).also { journal += "getValidCameraTag=$it" }
            }.onFailure { journal += "getValidCameraTag failed: ${it.javaClass.simpleName}" }
                .getOrNull().let(::parseCameraTags)
            // Fleet-known tags first (the path the known cars answer), then firmware-named ones.
            for (tag in KNOWN_TAGS) claim(info, tag, fromFirmwareTags = false)
            for (tag in firmwareTags.filterNot { it in KNOWN_TAGS }) claim(info, tag, fromFirmwareTags = true)
        } catch (t: Throwable) {
            journal += "enumeration failed: ${t.javaClass.simpleName}: ${t.message}"
        }
        return cameras.isNotEmpty()
    }

    private fun lookupInfo(): Class<*>? {
        try {
            val cls = classLookup(BMM_CAMERA_INFO, null)
            infoLoader = null
            return cls.also { infoClass = it }
        } catch (_: ClassNotFoundException) {
            val loader = jarClassLoader ?: jarLoader(CAMERA_JAR).also { jarClassLoader = it }
            if (!jarExists(CAMERA_JAR)) {
                journal += "bmmcamera.jar: not found"
                return null
            }
            return try {
                classLookup(BMM_CAMERA_INFO, loader).also {
                    infoLoader = loader
                    journal += "bmmcamera.jar: loaded"
                }
            } catch (t: Throwable) {
                journal += "bmmcamera.jar: load failed: ${t.javaClass.simpleName}"
                null
            }
        }
    }

    private fun Class<*>.static(name: String): Any? = getMethod(name).invoke(null)

    private fun claim(info: Class<*>, tag: String, fromFirmwareTags: Boolean) {
        val id = try {
            (info.getMethod("getCameraId", String::class.java).invoke(null, tag) as? Number)?.toInt()
        } catch (t: Throwable) {
            journal += "getCameraId($tag) failed: ${t.javaClass.simpleName}"; null
        } ?: run { journal += "getCameraId($tag)=-1"; return }
        if (id < 0 || cameras.any { it.id == id }) {
            journal += "getCameraId($tag)=$id${if (id < 0) "" else " (already claimed)"}"
            return
        }
        val size = runCatching {
            val w = (info.getMethod("getDefaultPreviewWidth", Int::class.javaPrimitiveType).invoke(null, id) as? Number)?.toInt()
            val h = (info.getMethod("getDefaultPreviewHeight", Int::class.javaPrimitiveType).invoke(null, id) as? Number)?.toInt()
            w to h
        }.onFailure { journal += "defaultPreview($id) failed: ${it.javaClass.simpleName}" }.getOrNull()
        journal += "getCameraId($tag)=$id defaultPreview=${size?.first}?x${size?.second}?"
        cameras += CameraId(tag, id, size?.first, size?.second, fromFirmwareTags)
    }

    companion object {
        const val BMM_CAMERA_INFO = "android.hardware.BmmCameraInfo"
        const val CAMERA_JAR = "/system/framework/bmmcamera.jar"

        /** Fleet-known tags from other-model research; the firmware's own list is tried after. */
        val KNOWN_TAGS = listOf("pano_h", "pano_l", "apa", "byd_apa")

        /** A firmware may pack several tags into one string, or answer with an array/collection. */
        fun parseCameraTags(raw: Any?): List<String> {
            val parts = when (raw) {
                null -> emptyList()
                is String -> raw.split(TAG_SEPARATORS)
                is Array<*> -> raw.mapNotNull { it?.toString() }
                is Collection<*> -> raw.mapNotNull { it?.toString() }
                else -> listOf(raw.toString())
            }
            return parts.map { it.trim { c -> c.isWhitespace() || c in TAG_TRIM } }
                .filter { it.isNotBlank() }.distinct()
        }

        private val TAG_SEPARATORS = Regex("[,;|\\s]+")
        private const val TAG_TRIM = "[]{}()\"'"
    }
}

/**
 * Guided signal-value capture for calibration: walks the driver through labelled phases
 * (no signal → left → neutral → right → neutral → hazard on → hazard off), polls the four
 * experimental getters on the caller's worker and records, per phase, every distinct raw
 * value each getter produced. The resulting report fills the JSON profile's value encodings
 * without guessing. The caller owns threading and the phase prompt UI; this class is pure
 * bookkeeping so it is fully testable.
 */
internal class TurnCameraSignalCapture(
    private val clock: () -> Long,
    /** Reads the four raw ints; invoked on the caller's worker, must never throw. */
    private val read: () -> IntArray?,
) {
    data class Phase(val key: String, val millis: Long)

    data class GetterValues(val distinct: List<Int>, val min: Int?, val max: Int?)

    private val phases = mutableListOf<Phase>()
    private val recorded = mutableMapOf<String, Array<MutableSet<Int>?>>()
    private var currentKey: String? = null
    private var currentUntil = 0L

    fun phase(key: String, millis: Long) {
        phases += Phase(key, millis)
    }

    /** Feeds one poll tick; returns the still-open phase key or null when the phase ended. */
    fun tick(): String? {
        val key = currentKey ?: return null
        if (clock() >= currentUntil) return null
        val values = read() ?: return key
        val sets = recorded.getOrPut(key) { arrayOfNulls(4) }
        for (i in values.indices) {
            (sets[i] ?: mutableSetOf<Int>().also { sets[i] = it }).add(values[i])
        }
        return key
    }

    /** Starts the next unstarted phase; null while the current one is still open or all completed. */
    fun beginNext(): Phase? {
        if (currentKey != null && clock() < currentUntil) return null
        val next = phases.getOrNull(recorded.size) ?: return null
        currentKey = next.key
        currentUntil = clock() + next.millis
        return next
    }

    fun remainingPhases(): Int = phases.size - recorded.size

    fun reportText(): String = buildString {
        appendLine("signal capture (${phases.size} phases): raw int values per getter")
        appendLine("getters: turn, hazard, reverse, oem")
        for (phase in phases) {
            val sets = recorded[phase.key] ?: continue
            appendLine("phase ${phase.key}:")
            for (g in 0 until 4) {
                val values = sets[g]?.sorted()
                appendLine(
                    "  ${GETTER_NAMES[g]}=" +
                        (values?.joinToString(", ") { it.toString() } ?: "no data") +
                        (values?.let { " (min=${it.first()}, max=${it.last()})" } ?: ""),
                )
            }
        }
    }

    /** Phase→getter→distinct values, for tests and for the JSON suggestion step. */
    fun values(): Map<String, List<List<Int>>> =
        phases.associate { phase ->
            phase.key to (0 until 4).map { g -> recorded[phase.key]?.get(g)?.sorted() ?: emptyList() }
        }

    companion object {
        val GETTER_NAMES = listOf("turn", "hazard", "reverse", "oem")

        /** Other-model priors (Leopard 3, live-verified there) offered as a CHECK hint only. */
        const val HINT_TURN_MASK = "other model reported turn mask: 1=off, 2=left, 4=right; hazard: 6=on, 1=off"
    }
}

/**
 * Parking preview channel sweep: the driver steps through the AVM preview channels of one
 * camera id, one at a time, and the user marks which channel shows the left and the right
 * side. Pure bookkeeping — Surface supply and the vendor open live in the UI layer via
 * [VendorAvmCameraDriver], which already enforces one session and quarantine.
 */
internal class AvmPreviewSweeper(val cameraId: Int, val channels: IntRange) {
    var channel: Int = channels.first()
        private set
    var left: Int? = null
    var right: Int? = null

    fun next(): Int {
        if (channel < channels.last()) channel++
        return channel
    }

    fun previous(): Int {
        if (channel > channels.first()) channel--
        return channel
    }

    fun markLeft() {
        left = channel
    }

    fun markRight() {
        right = channel
    }

    fun complete(): Boolean = left != null && right != null && left != right

    /** Other-model prior for the CHECK hint only; never a default. */
    fun hint(): String = "other model (Leopard 3) sweep found: 2=left side, 3=right side"
}
