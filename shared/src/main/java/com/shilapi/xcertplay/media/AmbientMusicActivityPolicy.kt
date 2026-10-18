package com.shilapi.xcertplay.media

/** Decides whether enabled ambient control follows playback, holds its lowest level, or restores OEM state. */
internal enum class AmbientMusicTarget { RESTORE_OEM, LOWEST, STATIC, FOLLOW_PLAYBACK }

/**
 * What the lamps do while enabled control has no music to follow. The lowest raw level switches the lamps fully off
 * on a 2023 Han, so it is an explicit choice rather than the only idle state.
 */
enum class AmbientIdleBehavior {
    /** Hold the chosen color at the chosen brightness. */
    STEADY,
    /** Hand the lamps back to the car's own settings after [AmbientMusicActivityPolicy.RESTORE_AFTER_MILLIS]. */
    CAR,
    /** Hold the lowest level. */
    OFF,
}

/**
 * Whether the car's own music rhythm should run: CarPlay's media track is open and playing, and the iPhone has not
 * reported a pause ([phonePlaying] is null before it reports anything).
 */
internal fun carRhythmPlaying(rendererActive: Boolean, trackPlaying: Boolean?, phonePlaying: Boolean?): Boolean =
    rendererActive && trackPlaying == true && phonePlaying != false

internal object AmbientMusicActivityPolicy {
    /** Track changes and short pauses keep the lamps; the car's lighting returns only after this much silence. */
    const val RESTORE_AFTER_MILLIS = 15_000L

    /** Guidance routed onto the automotive media bus must not replace the music lamp owner. */
    fun acceptsAudio(audioType: String, payloadType: Int): Boolean =
        AudioChannelMapper.map(audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel == AudioChannel.MEDIA

    fun target(
        enabled: Boolean, music: Boolean, brightness: Int, rendererActive: Boolean, playing: Boolean,
        idle: AmbientIdleBehavior = AmbientIdleBehavior.OFF, idleMillis: Long = 0,
    ): AmbientMusicTarget {
        val silent = !rendererActive || !playing
        return when {
            !enabled -> AmbientMusicTarget.RESTORE_OEM
            silent && idle == AmbientIdleBehavior.CAR && idleMillis >= RESTORE_AFTER_MILLIS -> AmbientMusicTarget.RESTORE_OEM
            brightness <= 0 -> AmbientMusicTarget.LOWEST
            silent -> if (idle == AmbientIdleBehavior.OFF) AmbientMusicTarget.LOWEST else AmbientMusicTarget.STATIC
            !music -> AmbientMusicTarget.STATIC
            else -> AmbientMusicTarget.FOLLOW_PLAYBACK
        }
    }
}
