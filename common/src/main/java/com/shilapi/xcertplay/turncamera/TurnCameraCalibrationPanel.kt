package com.shilapi.xcertplay.turncamera

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** Editor of two local drafts. The only preview is an explicitly labelled synthetic chart.
 * Uses its settings host's dimensions and widgets rather than defining another style system. */
internal class TurnCameraCalibrationPanel(
    context: Context,
    initial: Map<TurnCameraSide, TurnCameraCalibration>,
    private val gapDp: Int,
    private val controlHeightDp: Int,
    private val label: (String) -> TextView,
    private val button: (String, () -> Unit) -> View,
) : LinearLayout(context) {
    val profiles = TurnCameraSide.entries.associateWith { initial[it] ?: TurnCameraCalibration() }.toMutableMap()
    private var side = TurnCameraSide.LEFT
    private val preview = TurnCameraCalibrationPreview(context)
    private val p get() = profiles.getValue(side)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun text(id: Int) = context.getString(id)

    init { orientation = VERTICAL; render() }

    private fun update(value: TurnCameraCalibration) { profiles[side] = value; preview.profile = value }

    private fun action(title: String, click: () -> Unit) {
        addView(button(title, click), LayoutParams(-1, -2).apply {
            topMargin = dp(gapDp)
        })
    }

    private fun select(title: String, values: List<String>, current: Int, selected: (Int) -> Unit) {
        action("$title · ${values[current]}") {
            AlertDialog.Builder(context).setTitle(title)
                .setSingleChoiceItems(values.toTypedArray(), current) { dialog, index ->
                    selected(index); dialog.dismiss(); render()
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun slider(title: Int, low: Int, high: Int, value: Int, change: (Int) -> TurnCameraCalibration) {
        val caption = label("${text(title)} · $value%")
        addView(caption, LayoutParams(-1, -2).apply { topMargin = dp(gapDp) })
        addView(SeekBar(context).apply {
            max = high - low; progress = value - low
            // Physical image/window coordinates never reverse with the UI language.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            contentDescription = text(title)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val selected = progress + low
                    caption.text = "${text(title)} · $selected%"
                    update(change(selected))
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }, LayoutParams(-1, dp(controlHeightDp)))
    }

    private fun render() {
        removeAllViews()
        addView(label(text(R.string.settings_turn_camera_calibration_notice)))
        // Full-width, aspect-correct preview at every density and window size.
        (preview.parent as? android.view.ViewGroup)?.removeView(preview)
        preview.profile = p
        preview.contentDescription = text(R.string.settings_turn_camera_calibration_notice)
        addView(preview, LayoutParams(-1, -2).apply { topMargin = dp(gapDp) })
        select(text(R.string.settings_turn_camera_side), listOf(text(R.string.settings_turn_camera_left), text(R.string.settings_turn_camera_right)), side.ordinal) {
            side = TurnCameraSide.entries[it]
        }
        select(text(R.string.settings_turn_camera_source), listOf("Vendor AVMCamera", "Camera2"), p.source.ordinal) {
            update(p.copy(source = TurnCameraCalibration.Source.entries[it], cameraId = ""))
        }
        addView(label(text(R.string.settings_turn_camera_id)), LayoutParams(-1, -2).apply { topMargin = dp(gapDp) })
        addView(EditText(context).apply {
            setText(p.cameraId); setSingleLine(true)
            setTextColor(label("").currentTextColor)
            contentDescription = text(R.string.settings_turn_camera_id)
            inputType = if (p.source == TurnCameraCalibration.Source.VENDOR_AVM) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            filters = arrayOf(android.text.InputFilter.LengthFilter(128))
            minHeight = dp(controlHeightDp)
            doAfterTextChanged {
                val id = it.toString()
                update(p.copy(cameraId = id))
                error = if (TurnCameraCalibration.validId(p.source, id)) null else text(R.string.settings_turn_camera_id_invalid)
            }
        }, LayoutParams(-1, -2))
        slider(R.string.settings_turn_camera_window_x, 0, 100, (p.windowX * 100).roundToInt()) { p.copy(windowX = it / 100f) }
        slider(R.string.settings_turn_camera_window_y, 0, 100, (p.windowY * 100).roundToInt()) { p.copy(windowY = it / 100f) }
        slider(R.string.settings_turn_camera_window_width, 10, 100, (p.windowWidth * 100).roundToInt()) { p.copy(windowWidth = it / 100f) }
        slider(R.string.settings_turn_camera_window_height, 10, 100, (p.windowHeight * 100).roundToInt()) { p.copy(windowHeight = it / 100f) }
        slider(R.string.settings_turn_camera_image_x, -100, 100, (p.imageX * 100).roundToInt()) { p.copy(imageX = it / 100f) }
        slider(R.string.settings_turn_camera_image_y, -100, 100, (p.imageY * 100).roundToInt()) { p.copy(imageY = it / 100f) }
        slider(R.string.settings_turn_camera_zoom, 50, 400, (p.zoom * 100).roundToInt()) { p.copy(zoom = it / 100f) }
        select(text(R.string.settings_turn_camera_rotation), listOf("0°", "90°", "180°", "270°"), p.rotation / 90) { update(p.copy(rotation = it * 90)) }
        select(text(R.string.settings_turn_camera_mirror_x), listOf(text(R.string.disabled_value), text(R.string.enabled_value)), if (p.mirrorX) 1 else 0) { update(p.copy(mirrorX = it == 1)) }
        select(text(R.string.settings_turn_camera_mirror_y), listOf(text(R.string.disabled_value), text(R.string.enabled_value)), if (p.mirrorY) 1 else 0) { update(p.copy(mirrorY = it == 1)) }
        select(text(R.string.settings_turn_camera_fit), listOf(text(R.string.settings_turn_camera_fit_inside), text(R.string.settings_turn_camera_fill)), if (p.fill) 1 else 0) { update(p.copy(fill = it == 1)) }
        action(text(R.string.settings_turn_camera_reset)) { update(TurnCameraCalibration()); render() }
    }
}
