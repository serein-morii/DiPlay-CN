package com.shilapi.xcertplay

/**
 * The Geely/Lynk steering-wheel key codes and which of them to subscribe to.
 *
 * No Android types, so the double-fire rule is unit-tested without a device or Robolectric.
 */
internal object GeelyWheelKeyCodes {
    // KeyCode.KEYCODE_R_MEDIA_* in the head unit's com.geely.lib.oneosapi.input.
    const val KEY_PLAY_PAUSE = 200085
    const val KEY_NEXT = 200087
    const val KEY_PREVIOUS = 200088
    // KEYCODE_R_VOICE_ASSIST: the steering-wheel microphone key (long press used to open HiCar).
    const val KEY_VOICE_ASSIST = 200231

    val ALL = intArrayOf(KEY_PLAY_PAUSE, KEY_NEXT, KEY_PREVIOUS, KEY_VOICE_ASSIST)
    val SIRI_ONLY = intArrayOf(KEY_VOICE_ASSIST)

    /**
     * The key codes to subscribe to on the head unit's input service.
     *
     * With CarPlay audio on the car's Bluetooth, the head unit's own AVRCP already drives the
     * iPhone's music, so registering the media keys here too would double-fire play/pause and skip.
     * Only the microphone key is registered then (long press -> Siri); otherwise all of them are.
     */
    fun registeredCodes(bluetoothAudio: Boolean): IntArray =
        if (bluetoothAudio) SIRI_ONLY else ALL
}
