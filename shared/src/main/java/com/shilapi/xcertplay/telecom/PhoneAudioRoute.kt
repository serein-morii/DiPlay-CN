package com.shilapi.xcertplay.telecom

import android.content.Context
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.hud.BydOutputSettings
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Why DiPlay wants the car in its phone audio state. */
enum class PhoneAudioReason {
    /** A call on the iPhone is ringing, dialing or connected over CarPlay. */
    CARPLAY_CALL,

    /** Another app on the head unit holds a voice-over-IP call. */
    VOIP_APP,
}

/** Starts and ends the Telecom call that puts the car's audio into its phone state. */
internal interface PhoneAudioBackend {
    /** Blocks until the call is active; false when Telecom refused it. */
    fun start(): Boolean

    fun stop()

    /** False once the system has ended the call on its own. */
    fun isActive(): Boolean
}

internal interface RouteScheduler {
    fun execute(block: () -> Unit)

    /** Runs [block] after [delayMillis]; the returned function cancels it. */
    fun schedule(delayMillis: Long, block: () -> Unit): () -> Unit
}

/**
 * Holds one Telecom call open while any enabled reason wants the car's phone audio state, and ends it
 * shortly after the last reason goes away. All backend work runs in order on the scheduler.
 *
 * A refused call is not retried at once: [carPlayCallsUseCarAudio] turns false for [failureBackoffMillis], so
 * calls in that window fall back to DiPlay's own echo canceller instead of having neither.
 */
internal class PhoneAudioRouteController(
    private val backend: PhoneAudioBackend,
    private val scheduler: RouteScheduler,
    private val enabled: (PhoneAudioReason) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val releaseDelayMillis: Long = RELEASE_DELAY_MILLIS,
    private val failureBackoffMillis: Long = FAILURE_BACKOFF_MILLIS,
) {
    private val lock = Any()
    private val wanted = mutableSetOf<PhoneAudioReason>()
    private var held = false // scheduler thread
    private var cancelRelease: (() -> Unit)? = null // scheduler thread
    @Volatile private var failedAt = NEVER

    fun setWanted(reason: PhoneAudioReason, value: Boolean) {
        synchronized(lock) { if (value) wanted += reason else wanted -= reason }
        evaluate()
    }

    /** A setting changed: start or end the call to match. */
    fun settingsChanged() = evaluate()

    /** True while the CarPlay call's echo is left to the car, so DiPlay's own canceller must stay out of it. */
    fun carPlayCallsUseCarAudio(): Boolean = enabled(PhoneAudioReason.CARPLAY_CALL) && !failedRecently()

    private fun failedRecently(): Boolean = failedAt != NEVER && now() - failedAt < failureBackoffMillis

    private fun needed(): Boolean =
        synchronized(lock) { wanted.toList() }.any(enabled) && !failedRecently()

    private fun evaluate() {
        scheduler.execute {
            if (needed()) {
                cancelRelease?.invoke()
                cancelRelease = null
                if (!held || !backend.isActive()) {
                    held = false
                    if (backend.start()) {
                        held = true
                        Log.i(TAG, "car phone audio state on")
                    } else {
                        failedAt = now()
                        Log.w(TAG, "Telecom refused the call that selects the car's phone audio state")
                    }
                }
            } else if (held && cancelRelease == null) {
                cancelRelease = scheduler.schedule(releaseDelayMillis) {
                    cancelRelease = null
                    if (!needed()) {
                        backend.stop()
                        held = false
                        Log.i(TAG, "car phone audio state off")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DiPlay-PhoneAudio"
        private const val NEVER = Long.MIN_VALUE

        /** Bridges the gap between two calls, or a call and call waiting, without leaving and re-entering the state. */
        const val RELEASE_DELAY_MILLIS = 2_000L
        const val FAILURE_BACKOFF_MILLIS = 5 * 60_000L
    }
}

/**
 * Puts the car's audio into its phone state by holding a self-managed Telecom call.
 *
 * On a BYD DiLink 3 head unit, Telecom tells the car's audio layer about any call, and the voice processing
 * the car applies to a phone call, echo cancellation included, only runs in that state. A third-party call
 * played as media never reaches it, so the far end hears its own voice back. Any app may register a
 * self-managed call (the normal permission MANAGE_OWN_CALLS), so DiPlay holds one for as long as a call
 * needs the car's audio. Both uses are off by default, see [BydOutputSettings.phoneAudioRoute] and
 * [BydOutputSettings.phoneAudioRouteApps].
 */
object PhoneAudioRoute {
    private const val TAG = "DiPlay-PhoneAudio"

    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-phone-audio").apply { isDaemon = true } }
    }
    @Volatile private var controller: PhoneAudioRouteController? = null
    private var watcher: VoipCallWatcher? = null // guarded by this
    @Volatile private var carPlayCallActive = false

    /** Safe to call often; the first call sets everything up and later ones re-read the settings. */
    @Synchronized
    fun attach(context: Context) {
        val app = context.applicationContext
        if (controller == null) {
            controller = PhoneAudioRouteController(
                backend = TelecomPhoneAudioBackend(app),
                scheduler = object : RouteScheduler {
                    override fun execute(block: () -> Unit) {
                        executor.execute { runCatching(block).onFailure { Log.w(TAG, "phone audio step failed", it) } }
                    }

                    override fun schedule(delayMillis: Long, block: () -> Unit): () -> Unit {
                        val task = executor.schedule(
                            { runCatching(block).onFailure { Log.w(TAG, "phone audio step failed", it) } },
                            delayMillis,
                            TimeUnit.MILLISECONDS,
                        )
                        return { task.cancel(false) }
                    }
                },
                enabled = { reason -> enabledFor(app, reason) },
            )
        }
        syncWatcher(app)
    }

    /** The iPhone has a call (any phase), or none any more. */
    fun carPlayCall(active: Boolean) {
        carPlayCallActive = active
        controller?.setWanted(PhoneAudioReason.CARPLAY_CALL, active)
    }

    /** Whether a call on another app currently wants the car's phone audio. */
    internal fun voipApp(active: Boolean) {
        controller?.setWanted(PhoneAudioReason.VOIP_APP, active)
    }

    /** One of the two settings changed. */
    fun settingsChanged(context: Context) {
        attach(context)
        controller?.settingsChanged()
    }

    /**
     * True when the car's phone audio state handles the CarPlay call's echo, so DiPlay must not also
     * run its own canceller or the platform effects on the call microphone.
     */
    fun carPlayCallsUseCarAudio(): Boolean = controller?.carPlayCallsUseCarAudio() == true

    /** DiPlay's own call microphone shows up in the recording list; it is not another app's call. */
    internal fun ownCarPlayCallActive(): Boolean = carPlayCallActive

    private fun enabledFor(context: Context, reason: PhoneAudioReason): Boolean = when (reason) {
        PhoneAudioReason.CARPLAY_CALL -> BydOutputSettings.phoneAudioRoute(context)
        PhoneAudioReason.VOIP_APP -> BydOutputSettings.phoneAudioRouteApps(context)
    }

    @Synchronized
    private fun syncWatcher(context: Context) {
        // The recording and playback callbacks the watcher relies on arrive with Android 8.
        val wantWatcher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && BydOutputSettings.phoneAudioRouteApps(context)
        if (wantWatcher && watcher == null) {
            watcher = VoipCallWatcher(context) { active -> voipApp(active) }.also { it.start() }
            Log.i(TAG, "watching for voice-over-IP calls in other apps")
        } else if (!wantWatcher && watcher != null) {
            watcher?.stop()
            watcher = null
            voipApp(false)
        }
    }
}
