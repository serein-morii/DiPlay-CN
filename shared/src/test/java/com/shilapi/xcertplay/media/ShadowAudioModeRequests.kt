package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowAudioManager

/**
 * The audio mode as AudioService keeps it: one request per process, the most recent one in force, and
 * MODE_NORMAL withdrawing the caller's own request (`AudioService.setModeInt`). Robolectric keeps only the
 * last mode set, so it cannot show DiPlay holding a mode after the process that set it first lets go.
 * Everything this test process sets is DiPlay's request; [otherProcess] stands for Telecom or the car's phone.
 */
@Implements(AudioManager::class)
open class ShadowAudioModeRequests : ShadowAudioManager() {
    @Implementation override fun setMode(mode: Int) = request(DIPLAY, mode)

    @Implementation override fun getMode(): Int = synchronized(requests) {
        requests.firstOrNull()?.second ?: AudioManager.MODE_NORMAL
    }

    companion object {
        private const val DIPLAY = "diplay"
        private const val OTHER = "other"
        private val requests = mutableListOf<Pair<String, Int>>() // newest first

        fun otherProcess(mode: Int) = request(OTHER, mode)

        private fun request(owner: String, mode: Int) = synchronized(requests) {
            requests.removeAll { it.first == owner }
            if (mode != AudioManager.MODE_NORMAL) requests.add(0, owner to mode)
        }

        @Resetter @JvmStatic fun clearRequests() = synchronized(requests) { requests.clear() }
    }
}
