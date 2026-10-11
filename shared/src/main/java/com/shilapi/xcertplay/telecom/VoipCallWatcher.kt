package com.shilapi.xcertplay.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Reports whether another app holds a voice-over-IP call. Such a call records from VOICE_COMMUNICATION and
 * plays the far end with the matching usage, which is the only audio the car's phone state treats well: it
 * nulls the echo. Plain MIC recordings, the kind voice notes and video use, are muted in that state, so they
 * never count here.
 *
 * A recording alone is not enough. While a call holds the phone state, BYD's own audio layer keeps
 * VOICE_RECOGNITION and VOICE_COMMUNICATION recorders open for as long as the state lasts, including the one
 * DiPlay holds, so recordings never go away on their own. Those helpers play nothing; the call does.
 */
@RequiresApi(Build.VERSION_CODES.O)
internal class VoipCallWatcher(context: Context, private val onChange: (Boolean) -> Unit) {
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var recordingSources = emptyList<Int>()
    private var playbackUsages = emptyList<Int>()
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) =
            update(recordings = configs)
    }
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) =
            update(playbacks = configs)
    }

    fun start() {
        val manager = audio ?: return
        manager.registerAudioRecordingCallback(recordingCallback, handler)
        manager.registerAudioPlaybackCallback(playbackCallback, handler)
        update(manager.activeRecordingConfigurations, manager.activePlaybackConfigurations)
    }

    fun stop() {
        audio?.unregisterAudioRecordingCallback(recordingCallback)
        audio?.unregisterAudioPlaybackCallback(playbackCallback)
    }

    private fun update(
        recordings: List<AudioRecordingConfiguration>? = null,
        playbacks: List<AudioPlaybackConfiguration>? = null,
    ) {
        recordings?.let { list -> recordingSources = list.map { it.clientAudioSource } }
        playbacks?.let { list -> playbackUsages = list.mapNotNull { it.audioAttributes?.usage } }
        val voip = voipActive(recordingSources, playbackUsages) && !PhoneAudioRoute.ownCarPlayCallActive()
        Log.i(TAG, "recording sources=$recordingSources playback usages=$playbackUsages voip=$voip")
        onChange(voip)
    }

    companion object {
        private const val TAG = "DiPlay-PhoneAudio"

        fun voipActive(recordingSources: List<Int>, playbackUsages: List<Int>): Boolean =
            recordingSources.any { it == MediaRecorder.AudioSource.VOICE_COMMUNICATION } &&
                playbackUsages.any { it == AudioAttributes.USAGE_VOICE_COMMUNICATION }
    }
}
