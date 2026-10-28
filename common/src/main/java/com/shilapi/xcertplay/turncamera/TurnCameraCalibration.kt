package com.shilapi.xcertplay.turncamera

import android.content.Context
import org.json.JSONObject

/** Local display draft, NOT vehicle parameters or evidence that a camera is safe to open. */
internal data class TurnCameraCalibration(
    val source: Source = Source.VENDOR_AVM,
    val cameraId: String = "",
    // Anchors span the available travel: 0 = left/top edge, 1 = right/bottom edge.
    val windowX: Float = 0.05f,
    val windowY: Float = 0.1f,
    val windowWidth: Float = 0.4f,
    val windowHeight: Float = 0.6f,
    val imageX: Float = 0f,
    val imageY: Float = 0f,
    val zoom: Float = 1f,
    val rotation: Int = 0,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val fill: Boolean = false,
) {
    enum class Source { VENDOR_AVM, CAMERA2 }

    fun normalized(): TurnCameraCalibration {
        fun Float.bound(min: Float, max: Float, fallback: Float) =
            if (isFinite()) coerceIn(min, max) else fallback
        val w = windowWidth.bound(.1f, 1f, .4f)
        val h = windowHeight.bound(.1f, 1f, .6f)
        return copy(
            cameraId = cameraId.takeIf { validId(source, it) }.orEmpty(),
            windowX = windowX.bound(0f, 1f, .05f),
            windowY = windowY.bound(0f, 1f, .1f),
            windowWidth = w, windowHeight = h,
            imageX = imageX.bound(-1f, 1f, 0f), imageY = imageY.bound(-1f, 1f, 0f),
            zoom = zoom.bound(.5f, 4f, 1f),
            rotation = if (rotation in listOf(0, 90, 180, 270)) rotation else 0,
        )
    }

    /** Source pixels -> window pixels. Mirrors act in source axes, then clockwise rotation,
     * fit/fill + zoom, then translation in screen axes. Independent of locale and density. */
    fun mapPoint(x: Float, y: Float, sourceWidth: Float, sourceHeight: Float,
                 targetWidth: Float, targetHeight: Float): Pair<Float, Float> {
        require(x.isFinite() && y.isFinite())
        require(listOf(sourceWidth, sourceHeight, targetWidth, targetHeight).all { it.isFinite() && it > 0 })
        val p = normalized()
        val dx = (x - sourceWidth / 2) * if (p.mirrorX) -1 else 1
        val dy = (y - sourceHeight / 2) * if (p.mirrorY) -1 else 1
        val (rx, ry) = when (p.rotation) {
            90 -> -dy to dx
            180 -> -dx to -dy
            270 -> dy to -dx
            else -> dx to dy
        }
        val swap = p.rotation == 90 || p.rotation == 270
        val sx = targetWidth / if (swap) sourceHeight else sourceWidth
        val sy = targetHeight / if (swap) sourceWidth else sourceHeight
        val scale = (if (p.fill) maxOf(sx, sy) else minOf(sx, sy)) * p.zoom
        return (targetWidth / 2 + rx * scale + p.imageX * targetWidth) to
            (targetHeight / 2 + ry * scale + p.imageY * targetHeight)
    }

    companion object {
        fun validId(source: Source, id: String): Boolean = id.isEmpty() || when (source) {
            Source.VENDOR_AVM -> id.matches(Regex("[0-9]{1,10}")) && id.toIntOrNull() != null
            Source.CAMERA2 -> id.length <= 128 && id.none { it.isISOControl() }
        }
    }
}

/** Separate, non-exported preferences: left/right drafts must not travel to another car.
 * No enable/verified bit is saved here. Loading or saving never calls a camera API. */
internal object TurnCameraCalibrationStore {
    private const val PREFS = "diplay_turn_camera_calibration"
    fun load(context: Context, side: TurnCameraSide): TurnCameraCalibration {
        val raw = runCatching { context.getSharedPreferences(PREFS, 0).getString(side.name, null) }.getOrNull()
            ?: return TurnCameraCalibration()
        return runCatching {
            require(raw.length <= 4096)
            val j = JSONObject(raw)
            require(j.getInt("version") == 1)
            TurnCameraCalibration(
                source = TurnCameraCalibration.Source.valueOf(j.getString("source")), cameraId = j.getString("id"),
                windowX = j.getDouble("x").toFloat(), windowY = j.getDouble("y").toFloat(),
                windowWidth = j.getDouble("w").toFloat(), windowHeight = j.getDouble("h").toFloat(),
                imageX = j.getDouble("ix").toFloat(), imageY = j.getDouble("iy").toFloat(), zoom = j.getDouble("zoom").toFloat(),
                rotation = j.getInt("rotation"), mirrorX = j.getBoolean("mx"), mirrorY = j.getBoolean("my"), fill = j.getBoolean("fill"),
            ).normalized()
        }.getOrDefault(TurnCameraCalibration())
    }

    fun save(context: Context, profiles: Map<TurnCameraSide, TurnCameraCalibration>) {
        require(profiles.values.all { TurnCameraCalibration.validId(it.source, it.cameraId) })
        val editor = context.getSharedPreferences(PREFS, 0).edit()
        profiles.forEach { (side, draft) ->
            val p = draft.normalized()
            val j = JSONObject().put("version", 1).put("source", p.source.name).put("id", p.cameraId)
                .put("x", p.windowX).put("y", p.windowY).put("w", p.windowWidth).put("h", p.windowHeight)
                .put("ix", p.imageX).put("iy", p.imageY).put("zoom", p.zoom).put("rotation", p.rotation)
                .put("mx", p.mirrorX).put("my", p.mirrorY).put("fill", p.fill)
            editor.putString(side.name, j.toString())
        }
        editor.apply()
        // Changing IDs or geometry revokes the experiment's previous consent, even
        // when it is the same firmware. A later return to projection must not auto-arm it.
        TurnCameraExperimentSettings.setEnabled(context, false)
    }
}
