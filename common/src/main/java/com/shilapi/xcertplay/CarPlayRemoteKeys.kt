package com.shilapi.xcertplay

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Android TV / hardware navigation keys -> CarPlay's native knob HID controller.
 *
 * CarPlay's non-touch focus model is rotary-first. A D-pad therefore maps to rotary wheel deltas
 * for normal focus traversal. Select and Back preserve real down/up transitions so long-press
 * semantics remain available to CarPlay. Touchscreen input is completely separate and unchanged.
 */
internal object CarPlayRemoteKeys {
    private const val WHEEL_STEP = 1
    private const val HELD_REPEAT_INTERVAL = 3

    /**
     * Standard TV/controller navigation, system and transport keys are never available for the
     * *new* custom play/pause assignment. This preserves the existing knob, Android MediaSession
     * and OEM/system handlers for Fire TV, Google TV and other HID remotes. A previously saved
     * assignment using one of these codes is deliberately ignored.
     */
    fun isProtectedNavigationKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_HEADSETHOOK,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_PLAY_PAUSE_DILINK3,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE,
        CarPlayMediaButton.KEYCODE_BYD_AUTO_MEDIA_VOICE_LONG,
        CarPlayMediaButton.KEYCODE_BYD_CARPLAY_VOICE,
        CarPlayMediaButton.KEYCODE_BYD_CARPLAY_VOICE_LONG,
        KeyEvent.KEYCODE_TV, KeyEvent.KEYCODE_TV_INPUT,
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
        KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> true
        else -> false
    }

    /**
     * With the external-controller setting on a touchscreen head unit, only keys from a real input
     * device drive the knob. The system navigation bar's Back is a virtual key and keeps leaving
     * CarPlay as before.
     */
    fun fromExternalController(event: KeyEvent, enabled: Boolean): Boolean =
        enabled && event.deviceId != KeyCharacterMap.VIRTUAL_KEYBOARD && event.device?.isVirtual != true

    fun dispatch(event: KeyEvent, controller: CarPlayController?): Boolean =
        dispatchToKnob(event) { state, momentary -> controller?.sendKnob(state, momentary) == true }

    internal fun dispatchToKnob(event: KeyEvent, sendKnob: (AirPlayKnobState, Boolean) -> Boolean): Boolean {
        val wheelDelta = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> -WHEEL_STEP

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN,
            KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> WHEEL_STEP

            else -> null
        }

        if (wheelDelta != null) {
            return dispatchWheel(event, sendKnob, wheelDelta)
        }

        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_A -> Button.SELECT

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_B -> Button.BACK

            else -> return false
        }

        return dispatchButton(event, sendKnob, button)
    }

    private fun dispatchWheel(
        event: KeyEvent,
        sendKnob: (AirPlayKnobState, Boolean) -> Boolean,
        delta: Int,
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val shouldSend =
                    event.repeatCount == 0 || event.repeatCount % HELD_REPEAT_INTERVAL == 0

                if (!shouldSend) {
                    true
                } else {
                    sendKnob(AirPlayKnobState(wheel = delta), true)
                }
            }

            // Relative wheel movement has no release state.
            KeyEvent.ACTION_UP -> true
            else -> false
        }
    }

    private fun dispatchButton(
        event: KeyEvent,
        sendKnob: (AirPlayKnobState, Boolean) -> Boolean,
        button: Button,
    ): Boolean {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    sendKnob(button.state(down = true), false)
                } else {
                    true
                }
            }

            KeyEvent.ACTION_UP ->
                sendKnob(AirPlayKnobState(), false)

            else -> false
        }
    }

    private enum class Button {
        SELECT,
        BACK;

        fun state(down: Boolean): AirPlayKnobState = when (this) {
            SELECT -> AirPlayKnobState(select = down)
            BACK -> AirPlayKnobState(back = down)
        }
    }
}
