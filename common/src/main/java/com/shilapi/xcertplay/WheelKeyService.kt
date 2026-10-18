package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.NavigationPlayback
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydOutputSettings

/**
 * Optional: steering-wheel keys zoom CarPlay's dashboard map and work as a CarPlay joystick. BYD's window
 * manager takes the wheel's keys (volume 291/292, the custom key 305, the media key 289) before any app
 * sees them and acts on them itself. An accessibility service that filters key events gets them earlier,
 * in the input filter, and may keep them. With the zoom setting on and the dashboard map streaming, the
 * mode key (BYD's custom key by default) switches the zoom keys (the volume keys by default) from volume
 * to map zoom until it is pressed again, or, in the timed behaviour, until a few seconds after the last
 * zoom. With the joystick setting on, the joystick key (BYD's media key by default) turns the joystick on
 * and off; while it is on the keys drive CarPlay's main screen as a car's rotary knob would (see
 * [WheelJoystick]). A call always keeps the keys for the call. During a CarPlay call the call key answers on
 * the iPhone (see [CarPlayCallKeys]), and with a CarPlay session DiLink 3's CarPlay voice keys open Siri.
 * With the Siri key setting on, a key the user assigns opens Siri while CarPlay is connected.
 * Every other key passes on unchanged. On
 * the Tang the console's volume sends the same codes as the wheel's, so it zooms and moves too.
 */
class WheelKeyService : AccessibilityService() {
    private val keys = WheelZoomKeys()
    private val navigationKeys = NavigationWheelKeyPolicy()
    private val joystick = WheelJoystick()
    private val siriKey = WheelSiriKey()
    /** A remapped media key is handled by the same accessibility input filter as the Siri key. */
    internal var sendMedia: (Int) -> Boolean = { index ->
        CarPlayBackgroundSession.snapshot()?.controller?.sendMediaButton(index) == true
    }
    private val handler = Handler(Looper.getMainLooper())
    private var doubleSiriFirstPressWasInCall = false
    private var pendingDoubleSiri: PendingServiceSiriDoublePress? = null
    private var delayedDoubleSiri: PendingServiceSiriDoublePress? = null
    private val requestDelayedSiri = Runnable {
        val pending = delayedDoubleSiri
        delayedDoubleSiri = null
        if (running !== this || pending == null || session() !== pending.session ||
            !WheelZoomSettings.siriDoublePress(this) ||
            !WheelZoomSettings.isSiriKey(this, pending.assignedKey) || inCall(this)) return@Runnable
        note("Siri double key sent=${requestSiri()} intervalMs=${pending.intervalMillis} " +
            "delayMs=${pending.triggerDelayMillis}")
    }
    private var learning: WheelZoomSettings.Role? = null
    private var learnt: ((WheelZoomSettings.Role, WheelKey) -> Unit)? = null
    private var learningCancelled: (() -> Unit)? = null
    private var learningRefused: ((WheelZoomSettings.Role) -> Unit)? = null
    private val endLearning = Runnable { clearLearning() }
    private val endTimedMode = Runnable {
        refreshEligibility()
        if (keys.timeOut() && eligibleRoute() != null) announce(zoomOn = false)
    }
    // This read is nonblocking: no ADB, input-device query or network lock in the periodic poll.
    internal var navigationPlayback: () -> com.shilapi.xcertplay.media.NavigationPlaybackSnapshot = { NavigationPlayback.snapshot() }
    internal var mapRoute: () -> Any? = { CarPlayBackgroundSession.snapshot()?.controller?.dashboardMapRoute() }
    internal var session: () -> Any? = { CarPlayBackgroundSession.snapshot()?.controller?.activeAirPlaySessionToken() }
    internal var knob: (AirPlayKnobState) -> Boolean = { CarPlayBackgroundSession.snapshot()?.controller?.sendKnob(it) == true }
    internal var routeActive: () -> Boolean = { CarPlayGlance.snapshot().maneuverType != null }
    internal var requestSiri: () -> Boolean = { CarPlayBackgroundSession.snapshot()?.controller?.requestSiri() == true }

    // With the auto-off setting the joystick ends a while after the last press and when a route starts,
    // so the keys go back to music and volume without a thought.
    private val joystickIdle = Runnable { if (joystick.end()) joystickEnded() }
    private var routeSeen = false
    private val watchRoute = object : Runnable {
        override fun run() {
            if (!joystick.on) return
            val route = routeActive()
            if (route && !routeSeen) {
                if (joystick.end()) joystickEnded()
                return
            }
            routeSeen = route
            handler.postDelayed(this, ROUTE_CHECK_MILLIS)
        }
    }
    private val pollEligibility = object : Runnable {
        override fun run() {
            if (running !== this@WheelKeyService) return
            refreshEligibility()
            handler.postDelayed(this, ELIGIBILITY_POLL_MILLIS)
        }
    }

    override fun onServiceConnected() {
        lastObservedDoubleSiriEvent = null
        running = this
        NavigationWheelSettings.recordAuthorization(this)
        CarPlayCallKeys.install(this)
        // Lets the watcher for other apps' calls run whenever this accessibility service does.
        PhoneAudioRoute.attach(this)
        refreshEligibility()
        handler.removeCallbacks(pollEligibility)
        handler.postDelayed(pollEligibility, ELIGIBILITY_POLL_MILLIS)
        WheelKeyLog.bound()
        note("wheel key service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (running === this) {
            running = null
            lastObservedDoubleSiriEvent = null
            note("wheel key service unbound")
        }
        clearLearning()
        keys.updateEligibility(null)
        joystick.end()
        stopJoystickTimers()
        handler.removeCallbacks(pollEligibility)
        handler.removeCallbacks(endTimedMode)
        clearDoubleSiri()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (running === this) {
            running = null
            lastObservedDoubleSiriEvent = null
            note("wheel key service destroyed")
        }
        clearLearning()
        keys.timeOut()
        joystick.end()
        clearDoubleSiri()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() { settingsChanged() }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        lastObservedDoubleSiriEvent = null
        val kept = handleKey(event)
        // A held key repeats many times a second; its first press is the evidence.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) recordPress(event, kept)
        return kept
    }

    /** One entry per press for the diagnostic report; it must never be in the way of the key itself. */
    private fun recordPress(event: KeyEvent, kept: Boolean) {
        runCatching {
            val text = WheelKeyLog.writesText(event.keyCode) || runCatching { event.isPrintingKey }.getOrDefault(true)
            val detail = when {
                text -> null
                navigationKeys.owns(event.keyCode, event.deviceId, event.downTime) -> "navigation volume"
                !kept && NavigationWheelKeyPolicy.volumeStep(event.keyCode) != null -> navigationGate().let {
                    "navigation volume not used: ${it.closedBy ?: "stream ${it.stream} did not take the change"}"
                }
                else -> null
            }
            WheelKeyLog.press(event.keyCode, event.scanCode, event.deviceId, kept, detail, text)
        }
    }

    private fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val physicalKey = PhysicalWheelKey(event.deviceId, event.keyCode, event.scanCode)
        if (keys.hasConsumedPress(physicalKey)) {
            refreshEligibility()
            val calling = inCall(this)
            if (calling) clearLearning()
            keys.onKey(null, down, event.repeatCount == 0, eligibleRoute() != null, calling,
                physicalKey = physicalKey)
            rearmTimedMode()
            return true
        }
        if (navigationKeys.owns(event.keyCode, event.deviceId, event.downTime)) return navigationKey(event)
        if (CarPlayCallKeys.onKey(this, event.keyCode, down)) return true
        val key = WheelKey.of(event)
        if (BydOutputSettings.carPlayCallControls(this) &&
            CarPlayMediaButton.opensSiriWhileCarPlay(event.keyCode) &&
            !(WheelZoomSettings.siriDoublePress(this) && WheelZoomSettings.isSiriKey(this, key)) &&
            session() != null) {
            if (!down) {
                val shortPressIgnored = WheelZoomSettings.siriLongPressOnly(this) && CarPlayMediaButton.isShortVoiceKey(event.keyCode)
                val sent = !shortPressIgnored && VoiceKeyPair.shared.allowCarPlayKey(event.eventTime) &&
                    CarPlayBackgroundSession.snapshot()?.controller?.requestSiri() == true
                note("CarPlay voice key ${event.keyCode}: Siri sent=$sent")
            }
            return true
        }
        refreshEligibility()
        val calling = inCall(this)
        if (calling) clearLearning()
        val enabled = WheelZoomSettings.enabled(this)
        // roleOf ignores whether a role is on, so it could hand the Siri key to an inactive default role.
        val role = when {
            WheelZoomSettings.isSiriKey(this, key) -> WheelZoomSettings.Role.SIRI
            WheelZoomSettings.isMediaPlayPauseKey(this, key) -> WheelZoomSettings.Role.MEDIA_PLAY_PAUSE
            WheelZoomSettings.anyEnabled(this) -> WheelZoomSettings.roleOf(this, key)
            else -> null
        }
        val activeSession = session()
        if (role == WheelZoomSettings.Role.SIRI && WheelZoomSettings.siriDoublePress(this) &&
            activeSession == null) {
            clearDoubleSiri()
        }
        if (role == WheelZoomSettings.Role.SIRI && WheelZoomSettings.siriDoublePress(this) &&
            learning == null && activeSession != null) {
            lastObservedDoubleSiriEvent = WheelKeyEventFingerprint.of(event)
            if (down && event.repeatCount == 0 && pendingDoubleSiri?.physicalKey == physicalKey) {
                pendingDoubleSiri = null
            }
            val intervalMillis = WheelZoomSettings.siriDoublePressIntervalMillis(this)
            val doubleAction = if (down && event.repeatCount == 0) {
                siriKey.onDoublePress(event.eventTime, intervalMillis.toLong())
            } else null
            if (doubleAction == WheelSiriKey.DoublePressAction.FIRST) {
                doubleSiriFirstPressWasInCall = calling
            }
            val confirmedPair = doubleAction == WheelSiriKey.DoublePressAction.CONFIRMED
            val confirmed = confirmedPair && !doubleSiriFirstPressWasInCall
            if (confirmedPair) doubleSiriFirstPressWasInCall = false
            if (confirmed) {
                pendingDoubleSiri = PendingServiceSiriDoublePress(
                    physicalKey,
                    key,
                    activeSession,
                    event.eventTime,
                    intervalMillis,
                    WheelZoomSettings.siriTriggerDelayMillis(this),
                )
            }
            val matchingRelease = pendingDoubleSiri?.takeIf { !down && it.physicalKey == physicalKey }
            val release = matchingRelease?.takeIf {
                event.eventTime - it.confirmedAtMillis in 0L..WheelSiriKey.MAX_SECOND_PRESS_MILLIS
            }
            if (matchingRelease != null) pendingDoubleSiri = null
            if (release != null) {
                delayedDoubleSiri = release
                handler.removeCallbacks(requestDelayedSiri)
                handler.postDelayed(requestDelayedSiri, release.triggerDelayMillis.toLong())
            }
            // Both presses stay vehicle-owned: the first can open its assistant and the second can
            // close it. DiPlay only observes the pair and opens Siri after the configured delay.
            return false
        }
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        val action = keys.onKey(
            role = role.takeIf { enabled },
            down = down,
            firstPress = event.repeatCount == 0,
            mapShown = eligibleRoute() != null,
            inCall = calling,
            physicalKey = physicalKey,
            onFirstPress = {
                learning?.let { role ->
                    if (role == WheelZoomSettings.Role.MEDIA_PLAY_PAUSE &&
                        CarPlayRemoteKeys.isProtectedNavigationKey(event.keyCode)) {
                        note("Wheel media key not assigned: navigation key ${event.keyCode} is reserved")
                        Toast.makeText(this, R.string.wheel_media_navigation_reserved, Toast.LENGTH_SHORT).show()
                        return@let WheelZoomKeys.Action.CONSUME
                    }
                    val done = learnt
                    val refused = learningRefused
                    clearLearning(notify = false)
                    val taken = WheelZoomSettings.conflict(this, role, key)
                    if (taken != null) {
                        note("$role key $key refused: it is the $taken key")
                        refused?.invoke(taken)
                    } else {
                        WheelZoomSettings.assign(this, role, key)
                        note("$role key is now $key")
                        done?.invoke(role, key)
                    }
                    WheelZoomKeys.Action.CONSUME
                } ?: mediaPress(role, calling) ?: siriPress(role, calling, event.eventTime) ?: joystickPress(role, calling)
            },
        )
        when (action) {
            WheelZoomKeys.Action.PASS -> return navigationKey(event)
            WheelZoomKeys.Action.CONSUME -> Unit
            WheelZoomKeys.Action.MODE_ON -> announce(zoomOn = true)
            WheelZoomKeys.Action.MODE_OFF -> announce(zoomOn = false)
            WheelZoomKeys.Action.ZOOM_IN, WheelZoomKeys.Action.ZOOM_OUT ->
                controller?.zoomDashboardMap(zoomIn = action == WheelZoomKeys.Action.ZOOM_IN)
        }
        rearmTimedMode()
        return true
    }

    private fun navigationKey(event: KeyEvent): Boolean = navigationKeys.onKey(
        event.action, event.keyCode, event.deviceId, event.downTime, event.repeatCount,
        eligible = navigationStream() != null,
    ) { delta -> adjustNavigation(delta) }

    private fun navigationStream(): Int? = navigationGate().stream

    private class NavigationGate(val closedBy: String?, val stream: Int? = null)

    /** The stream the volume keys may change now, or the first reason they stay with the head unit. */
    private fun navigationGate(): NavigationGate {
        if (!NavigationWheelSettings.enabled(this)) return NavigationGate("its setting is off")
        if (running !== this) return NavigationGate("the service is not bound")
        if (inCall(this)) return NavigationGate("a call is active")
        if (session() == null) return NavigationGate("no CarPlay session")
        val playback = navigationPlayback()
        if (!playback.active) return NavigationGate("no guidance is speaking")
        if (playback.priorityVoiceActive) return NavigationGate("Siri or a call is speaking")
        // Usage routing and a rejected legacy route cannot identify a safe volume stream.
        val stream = playback.legacyStreamType?.takeIf { it in 1..20 && it != AudioManager.STREAM_MUSIC }
            ?: return NavigationGate("navigation audio stream ${playback.legacyStreamType ?: "automatic"} has no volume of its own")
        return NavigationGate(null, stream)
    }

    private fun adjustNavigation(delta: Int): Boolean {
        val stream = navigationStream() ?: return false
        val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return try {
            val minimum = navigationWheelMinimum(stream, android.os.Build.VERSION.SDK_INT) {
                if (android.os.Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(it) else 0
            }
            val current = audio.getStreamVolume(stream)
            val maximum = audio.getStreamMaxVolume(stream)
            val target = navigationWheelTarget(current, minimum, maximum, delta) ?: return false
            if (navigationStream() != stream) return false
            audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
            // Failed/unsupported writes leave the initial press with the head unit.
            audio.getStreamVolume(stream) == target
        } catch (error: Exception) {
            Log.w(TAG, "navigation volume unavailable for stream $stream", error)
            WheelKeyLog.note("navigation volume unavailable for stream $stream: ${error.javaClass.simpleName}")
            false
        }
    }

    private fun eligibleRoute(): Any? = if (WheelZoomSettings.enabled(this)) mapRoute() else null

    private fun refreshEligibility() {
        val joystickAllowed = WheelZoomSettings.joystick(this)
        if (!WheelZoomSettings.anyEnabled(this)) clearLearning()
        if (keys.updateEligibility(eligibleRoute())) handler.removeCallbacks(endTimedMode)
        // A new CarPlay session, or the setting turned off, ends the joystick quietly.
        if (joystick.updateSession(session()) or (!joystickAllowed && joystick.end())) stopJoystickTimers()
    }

    // Without a CarPlay session, or in a call, the key keeps the car's own action.
    private fun siriPress(role: WheelZoomSettings.Role?, calling: Boolean, eventTime: Long): WheelZoomKeys.Action? {
        if (role != WheelZoomSettings.Role.SIRI || calling || session() == null) return null
        if (siriKey.opens(eventTime)) note("Siri key sent=${requestSiri()}")
        return WheelZoomKeys.Action.CONSUME
    }

    private fun mediaPress(role: WheelZoomSettings.Role?, calling: Boolean): WheelZoomKeys.Action? {
        if (role != WheelZoomSettings.Role.MEDIA_PLAY_PAUSE || calling || session() == null) return null
        note("Remapped wheel media play/pause sent=${sendMedia(CarPlayMediaButton.PLAY_PAUSE)}")
        return WheelZoomKeys.Action.CONSUME
    }

    // The joystick's part of a first press; null leaves the key to the zoom, or to the car.
    private fun joystickPress(role: WheelZoomSettings.Role?, calling: Boolean): WheelZoomKeys.Action? {
        if (!WheelZoomSettings.joystick(this)) return null
        when (val move = joystick.onKey(role, session = session() != null, inCall = calling)) {
            WheelJoystick.Action.PASS -> return null
            WheelJoystick.Action.ON -> {
                if (keys.timeOut()) handler.removeCallbacks(endTimedMode) // the joystick takes the volume keys
                announceJoystick(on = true)
                armJoystickTimers()
            }
            WheelJoystick.Action.OFF -> joystickEnded()
            else -> {
                knob(knobState(move))
                if (WheelZoomSettings.joystickAutoOff(this)) armJoystickIdle()
            }
        }
        return WheelZoomKeys.Action.CONSUME
    }

    // CarPlay moves its focus only when the knob turns; x/y nudges pan a focused map instead.
    private fun knobState(move: WheelJoystick.Action): AirPlayKnobState = when (move) {
        WheelJoystick.Action.PREVIOUS -> AirPlayKnobState(wheel = -1)
        WheelJoystick.Action.NEXT -> AirPlayKnobState(wheel = 1)
        WheelJoystick.Action.SELECT -> AirPlayKnobState(select = true)
        else -> AirPlayKnobState(back = true)
    }

    private fun joystickEnded() {
        stopJoystickTimers()
        announceJoystick(on = false)
    }

    private fun armJoystickTimers() {
        stopJoystickTimers()
        if (!joystick.on || !WheelZoomSettings.joystickAutoOff(this)) return
        armJoystickIdle()
        routeSeen = routeActive()
        handler.postDelayed(watchRoute, ROUTE_CHECK_MILLIS)
    }

    private fun armJoystickIdle() {
        handler.removeCallbacks(joystickIdle)
        handler.postDelayed(joystickIdle, WheelZoomSettings.JOYSTICK_IDLE_MILLIS)
    }

    private fun stopJoystickTimers() {
        handler.removeCallbacks(joystickIdle)
        handler.removeCallbacks(watchRoute)
    }

    // A toast with the keys at a glance on the centre screen; on the dashboard only the state (its font
    // is limited).
    private fun announceJoystick(on: Boolean) {
        note("joystick ${if (on) "on" else "off"}")
        val text = getString(if (on) R.string.wheel_joystick_on else R.string.wheel_joystick_off)
        handler.post {
            if (on) Toast.makeText(this, "$text\n${getString(R.string.wheel_joystick_hint)}", Toast.LENGTH_LONG).show()
            else Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        }
        BydNavigationOutputs.dashboardNote(text)
    }

    private fun clearLearning(notify: Boolean = true) {
        val cancelled = learningCancelled.takeIf { learning != null && notify }
        learning = null
        learnt = null
        learningCancelled = null
        learningRefused = null
        handler.removeCallbacks(endLearning)
        cancelled?.invoke()
    }

    private fun settingsChanged() {
        clearLearning()
        keys.timeOut()
        handler.removeCallbacks(endTimedMode)
        clearDoubleSiri()
        refreshEligibility()
        armJoystickTimers() // the auto-off setting may have changed
    }

    private fun clearDoubleSiri() {
        handler.removeCallbacks(requestDelayedSiri)
        pendingDoubleSiri = null
        delayedDoubleSiri = null
        doubleSiriFirstPressWasInCall = false
        siriKey.reset()
    }

    // In the timed behaviour zoom mode ends a few seconds after the last press.
    private fun rearmTimedMode() {
        handler.removeCallbacks(endTimedMode)
        if (keys.zoomMode && WheelZoomSettings.behaviour(this) == WheelZoomSettings.Behaviour.TIMED) {
            handler.postDelayed(endTimedMode, WheelZoomSettings.TIMED_MODE_MILLIS)
        }
    }

    // On the centre screen, and where the song shows on the dashboard: zoom with BYD's Bluetooth-music
    // icon (source 6), volume with the song's usual icon.
    private fun announce(zoomOn: Boolean) {
        note("zoom mode ${if (zoomOn) "on" else "off"}")
        handler.post {
            Toast.makeText(this, if (zoomOn) R.string.wheel_zoom_mode_on else R.string.wheel_zoom_mode_off, Toast.LENGTH_SHORT).show()
        }
        if (zoomOn) {
            BydNavigationOutputs.dashboardNote("🔍 ${getString(R.string.wheel_zoom_note_zoom)}", ZOOM_NOTE_SOURCE)
        } else {
            BydNavigationOutputs.dashboardNote("🔊 ${getString(R.string.wheel_zoom_note_volume)}")
        }
    }

    companion object {
        internal const val TAG = "DiPlay-WheelKeys"
        private const val ZOOM_NOTE_SOURCE = 6
        private const val ELIGIBILITY_POLL_MILLIS = 250L
        private const val ROUTE_CHECK_MILLIS = 1_000L
        internal const val LEARNING_TIMEOUT_MILLIS = 10_000L
        private const val RESTORE_GRACE_MILLIS = 4_000L
        private const val RESTORE_RETRY_MILLIS = 10_000L
        private const val RESTORE_ATTEMPTS = 6
        private const val RESTORE_PREFS = "wheel_key_restore"
        @Volatile private var running: WheelKeyService? = null
        @Volatile private var lastObservedDoubleSiriEvent: WheelKeyEventFingerprint? = null
        private val restoring = AtomicBoolean(false)

        fun connected(): Boolean = running != null

        /** The window uses this to avoid duplicating an event that the bound input filter processed. */
        internal fun observedDoubleSiri(event: KeyEvent): Boolean =
            running != null && lastObservedDoubleSiriEvent == WheelKeyEventFingerprint.of(event)

        /** The next key pressed is assigned to [role]; [done] runs on the service's thread. */
        /** A key that another active role uses goes to [refused] with that role, unassigned. */
        fun learn(role: WheelZoomSettings.Role, cancelled: () -> Unit = {}, refused: (WheelZoomSettings.Role) -> Unit = {},
            done: (WheelZoomSettings.Role, WheelKey) -> Unit): Boolean {
            val service = running ?: return false
            if (!WheelZoomSettings.anyEnabled(service)) return false
            service.clearLearning()
            service.learnt = done
            service.learningCancelled = cancelled
            service.learningRefused = refused
            service.learning = role
            service.handler.postDelayed(service.endLearning, LEARNING_TIMEOUT_MILLIS)
            return true
        }

        fun cancelLearning() { running?.onMain { clearLearning() } }

        fun isLearning(): Boolean = running?.learning != null

        fun settingsChanged() { running?.onMain { settingsChanged() } }

        fun enabledInSettings(context: Context): Boolean {
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return component(context).flattenToString() in list.orEmpty().split(':')
        }

        /**
         * BYD's settings have no accessibility page, so the user can turn the service on through the car's
         * own adb (allowed once on the car screen). Services already in the list stay there.
         */
        fun enableOverAdb(context: Context, mayAsk: Boolean = true): LocalAdb.Access = LocalAdb(AdbKeys.load(context)).use { adb ->
            val access = adb.connect(mayAsk)
            if (access != LocalAdb.Access.READY) return@use access
            if (!mayAsk && !needsRestore(context)) return@use if (connected()) access else LocalAdb.Access.UNREACHABLE
            val allowed = applyServiceSettings(context, adb::shell) {
                mayAsk || wanted(context)
            }
            if (allowed && mayAsk) NavigationWheelSettings.recordAuthorization(context)
            if (allowed) note("wheel key service allowed over adb")
            if (allowed) access else LocalAdb.Access.UNREACHABLE
        }

        private const val GRANT_EXIT = "DIPLAY_WHEEL_EXIT"
        private const val CRASH_RESET_PAUSE_SECONDS = 1
        private fun checkedShell(command: String, shell: (String) -> String?): String? {
            val lines = shell("( $command ); result=\$?; printf '\\n$GRANT_EXIT:%s\\n' \"\$result\"")
                ?.trimEnd()?.lines() ?: return null
            if (lines.lastOrNull() != "$GRANT_EXIT:0") return null
            return lines.dropLast(1).joinToString("\n").trim()
        }

        /** Failed reads and shell commands must not replace the accessibility list or claim success. */
        internal fun applyServiceSettings(context: Context, shell: (String) -> String?,
            shouldContinue: () -> Boolean = { true }): Boolean = synchronized(UsbPermissionSetup.accessibilityLock) {
            applyServiceSettingsLocked(context, shell, shouldContinue)
        }

        private fun applyServiceSettingsLocked(context: Context, shell: (String) -> String?,
            shouldContinue: () -> Boolean): Boolean {
            if (!shouldContinue()) return false
            val current = checkedShell("settings get secure enabled_accessibility_services", shell) ?: return false
            val lists = allowedServices(current, component(context).flattenToString()) ?: return false
            // Binding may finish after the read. Check again immediately before a destructive rebind,
            // after the continuation callback, without blocking the service's main-thread callback.
            // Android marks a service whose process died as crashed and does not bind it again until
            // its settings observer reads a list without it. Written back at once, the observer read
            // the full list both times, and the keys stayed on "Allowed, starting…" with the service
            // under "Crashed services" in dumpsys accessibility (#606), so the list is left without
            // it for a moment first.
            lists.first?.let {
                if (!shouldContinue()) return false
                if (!connected() && checkedShell(
                        "settings put secure enabled_accessibility_services '$it' && sleep $CRASH_RESET_PAUSE_SECONDS",
                        shell,
                    ) == null
                ) return false
            }
            for (command in listOf(
                "settings put secure enabled_accessibility_services '${lists.second}'",
                "settings put secure accessibility_enabled 1",
            )) {
                if (!shouldContinue() || checkedShell(command, shell) == null) return false
            }
            return enabledInSettings(context) &&
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }

        internal fun needsRestore(context: Context): Boolean = !connected() && wanted(context)

        /**
         * The call controls need the service too: outside the CarPlay screen the call key reaches DiPlay
         * only through it, and without it BYD's window manager opens its own phone app instead. So does the
         * watcher for other apps' calls, which runs only while something keeps DiPlay alive.
         */
        internal fun wanted(context: Context): Boolean = WheelZoomSettings.anyEnabled(context) ||
            BydOutputSettings.carPlayCallControls(context) || NavigationWheelSettings.restoreAllowed(context) ||
            BydOutputSettings.phoneAudioRouteApps(context)

        /**
         * Android takes the service off the allowed list when the app is force-stopped (BYD's system does
         * that), and an update or a crash can leave it unbound. With a wheel key setting or the call controls on, DiPlay
         * puts it back over the car's adb, already allowed, when it is still not running a few seconds after
         * a DiPlay screen opens or comes back to the front, so the keys work without a visit to the settings.
         * The front check matters when the service stays unbound after the retries at startup: the keys
         * stayed on "Allowed, starting…" with the car's adb working until DiPlay was restarted (#606).
         */
        fun restoreIfNeeded(context: Context) {
            val app = context.applicationContext
            if (!needsRestore(app) || !restoring.compareAndSet(false, true)) return
            Thread({
                try {
                    restoreWithRetries(
                        needsRestore = { needsRestore(app) },
                        restore = { enableOverAdb(app, mayAsk = false) },
                        sleep = Thread::sleep,
                    ) { attempt, access ->
                        Log.i(TAG, "wheel key service restore attempt=$attempt adb=$access bound=${connected()}")
                        // Without its number, a run of attempts that end the same way is one line of the report.
                        WheelKeyLog.note("wheel key service restore attempt adb=$access bound=${connected()}")
                        recordRestore(app, attempt, access)
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "wheel key service restore failed", error)
                    WheelKeyLog.note("wheel key service restore failed: ${error.javaClass.simpleName}")
                } finally {
                    restoring.set(false)
                }
            }, "diplay-wheel-keys-restore").start()
        }

        /**
         * At boot the car's adb can come up after DiPlay, and a single early attempt left the keys on
         * "Allowed, starting…" until DiPlay was force-stopped (#606, #619). Unreachable adb, or a service
         * that the rewritten list did not bind yet, is tried again; a key the driver has not approved is not.
         */
        internal fun restoreWithRetries(
            needsRestore: () -> Boolean,
            restore: () -> LocalAdb.Access,
            sleep: (Long) -> Unit,
            attempts: Int = RESTORE_ATTEMPTS,
            onAttempt: (Int, LocalAdb.Access) -> Unit,
        ) {
            for (attempt in 1..attempts) {
                sleep(if (attempt == 1) RESTORE_GRACE_MILLIS else RESTORE_RETRY_MILLIS)
                if (!needsRestore()) return
                val access = restore()
                onAttempt(attempt, access)
                if (access == LocalAdb.Access.NOT_APPROVED || access == LocalAdb.Access.UNSUPPORTED) return
            }
        }

        private fun recordRestore(context: Context, attempt: Int, access: LocalAdb.Access) {
            context.getSharedPreferences(RESTORE_PREFS, Context.MODE_PRIVATE).edit()
                .putLong("at", System.currentTimeMillis())
                .putInt("attempt", attempt)
                .putString("adb", access.name)
                .apply()
        }

        /** One line for the diagnostic report: why the keys may still read "Allowed, starting…". */
        fun report(context: Context): String {
            val prefs = context.getSharedPreferences(RESTORE_PREFS, Context.MODE_PRIVATE)
            val at = prefs.getLong("at", 0L)
            val last = if (at == 0L) "none" else
                "attempt=${prefs.getInt("attempt", 0)} adb=${prefs.getString("adb", null)} " +
                    "ageMs=${System.currentTimeMillis() - at}"
            return "Wheel key service: wanted=${wanted(context)} allowedInSettings=${enabledInSettings(context)} " +
                "bound=${connected()} lastRestore=$last " +
                "siriDoublePress=${WheelZoomSettings.siriDoublePress(context)} " +
                "siriDoubleIntervalMs=${WheelZoomSettings.siriDoublePressIntervalMillis(context)} " +
                "siriTriggerDelayMs=${WheelZoomSettings.siriTriggerDelayMillis(context)}"
        }

        /**
         * The allowed-services setting without and with [ours]: the first is null when [ours] is not listed,
         * the second keeps every other service in its place.
         */
        internal fun allowedServices(current: String?, ours: String): Pair<String?, String>? {
            val value = current?.trim() ?: return null
            val component = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")
            if (!component.matches(ours)) return null
            val listed = value.takeUnless { it.isEmpty() || it == "null" }?.split(':').orEmpty()
            if (listed.any { !component.matches(it) }) return null
            val others = listed.filter { it != ours }
            val without = if (ours in listed) others.joinToString(":") else null
            return without to (others + ours).joinToString(":")
        }

        private fun component(context: Context) = ComponentName(context, WheelKeyService::class.java)
    }

    private fun onMain(action: WheelKeyService.() -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }
}

/** Logcat and the diagnostic report: a head unit without adb has only the report. */
private fun note(message: String) {
    Log.i(WheelKeyService.TAG, message)
    WheelKeyLog.note(message)
}

// CarPlay's iAP2 call state can be active even when the head unit leaves Android's mode normal.
// Native Bluetooth calls also use a call/communication audio mode.
internal fun inCall(context: Context): Boolean =
    BydNavigationOutputs.carPlayCall() != null ||
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode.let { it != null && it != AudioManager.MODE_NORMAL }

/** Use the input-device ID for a held press; saved assignments still use the stable device name. */
private data class PhysicalWheelKey(val device: Int, val code: Int, val scan: Int)

private data class PendingServiceSiriDoublePress(
    val physicalKey: PhysicalWheelKey,
    val assignedKey: WheelKey,
    val session: Any,
    val confirmedAtMillis: Long,
    val intervalMillis: Int,
    val triggerDelayMillis: Int,
)

private data class WheelKeyEventFingerprint(
    val device: Int,
    val code: Int,
    val scan: Int,
    val action: Int,
    val repeat: Int,
    val downTime: Long,
    val eventTime: Long,
) {
    companion object {
        fun of(event: KeyEvent) = WheelKeyEventFingerprint(
            event.deviceId,
            event.keyCode,
            event.scanCode,
            event.action,
            event.repeatCount,
            event.downTime,
            event.eventTime,
        )
    }
}

/** Keep the system's key stream well-formed even if settings/calls/routes change mid-press. */
internal class WheelKeyPresses {
    private val consumed = mutableMapOf<Any, Boolean>()

    fun hasConsumedPress(key: Any): Boolean = consumed[key] == true

    fun filter(key: Any?, down: Boolean, firstPress: Boolean, decide: () -> WheelZoomKeys.Action): WheelZoomKeys.Action {
        key ?: return WheelZoomKeys.Action.PASS
        if (!down) return disposition(consumed.remove(key) ?: false)
        consumed[key]?.let { return disposition(it) }
        // A repeat whose DOWN was not seen is passed without starting a new action or capture.
        if (!firstPress) return WheelZoomKeys.Action.PASS
        return decide().also { consumed[key] = it != WheelZoomKeys.Action.PASS }
    }

    private fun disposition(consume: Boolean) = if (consume) WheelZoomKeys.Action.CONSUME else WheelZoomKeys.Action.PASS
}

/** A key as the head unit reports it; code, scan code and device name tell keys apart. */
data class WheelKey(val code: Int, val scan: Int, val device: String) {
    override fun toString(): String = "$code/$scan"

    fun encode(): String = "$code|$scan|$device"

    companion object {
        fun of(event: KeyEvent): WheelKey = WheelKey(event.keyCode, event.scanCode,
            runCatching { InputDevice.getDevice(event.deviceId)?.name }.getOrNull() ?: "?")

        fun decode(text: String?): WheelKey? = text?.split('|', limit = 3)?.takeIf { it.size == 3 }?.let {
            WheelKey(it[0].toIntOrNull() ?: return null, it[1].toIntOrNull() ?: return null, it[2])
        }
    }
}

/** What a key press does for the dashboard map zoom; no Android types, so it is unit-tested. */
class WheelZoomKeys {
    enum class Action { PASS, CONSUME, MODE_ON, MODE_OFF, ZOOM_IN, ZOOM_OUT }

    var zoomMode = false
        private set
    private var route: Any? = null
    private val presses = WheelKeyPresses()

    internal fun hasConsumedPress(key: Any): Boolean = presses.hasConsumedPress(key)

    /** A new phone/stream or a lost map never inherits the old session's zoom mode. */
    fun updateEligibility(route: Any?): Boolean {
        if (this.route == route) return false
        this.route = route
        return timeOut()
    }

    /** [role] is null for keys that have no role; [firstPress] is false for auto-repeats. */
    fun onKey(role: WheelZoomSettings.Role?, down: Boolean, firstPress: Boolean, mapShown: Boolean, inCall: Boolean,
        physicalKey: Any? = role, onFirstPress: (() -> Action?)? = null): Action {
        if (!mapShown) timeOut()
        return presses.filter(physicalKey, down, firstPress) {
            onFirstPress?.invoke() ?: decide(role, mapShown, inCall)
        }
    }

    private fun decide(role: WheelZoomSettings.Role?, mapShown: Boolean, inCall: Boolean): Action {
        if (role == null || !mapShown || inCall) return Action.PASS
        if (role == WheelZoomSettings.Role.MODE) {
            zoomMode = !zoomMode
            return if (zoomMode) Action.MODE_ON else Action.MODE_OFF
        }
        if (!zoomMode || (role != WheelZoomSettings.Role.ZOOM_IN && role != WheelZoomSettings.Role.ZOOM_OUT)) return Action.PASS
        return if (role == WheelZoomSettings.Role.ZOOM_IN) Action.ZOOM_IN else Action.ZOOM_OUT
    }

    /** The timed behaviour's few seconds ran out, or the joystick took the keys; true if that ended zoom mode. */
    fun timeOut(): Boolean {
        if (!zoomMode) return false
        zoomMode = false
        return true
    }
}

/**
 * What a key's first press does for the CarPlay joystick (repeats and releases follow it, see
 * [WheelKeyPresses]); no Android types, so it is unit-tested. CarPlay moves its focus only with a knob's
 * turn, so previous/next and the zoom keys (the volume roller) both turn it; play/pause selects and the
 * mode key goes back. Without a CarPlay session the joystick key keeps the car's action and the joystick
 * ends; a call keeps every key for the call.
 */
class WheelJoystick {
    enum class Action { PASS, ON, OFF, PREVIOUS, NEXT, SELECT, BACK }

    var on = false
        private set
    private var session: Any? = null

    /** A new CarPlay session (or none) never inherits the old one's joystick; true if that ended it. */
    fun updateSession(session: Any?): Boolean {
        if (this.session === session) return false
        this.session = session
        return end()
    }

    /** [role] is null for keys that have no role. */
    fun onKey(role: WheelZoomSettings.Role?, session: Boolean, inCall: Boolean): Action {
        if (role == null || inCall) return Action.PASS
        if (!session) {
            on = false
            return Action.PASS
        }
        if (role == WheelZoomSettings.Role.JOYSTICK) {
            on = !on
            return if (on) Action.ON else Action.OFF
        }
        if (!on) return Action.PASS
        return when (role) {
            WheelZoomSettings.Role.PREVIOUS, WheelZoomSettings.Role.ZOOM_IN -> Action.PREVIOUS
            WheelZoomSettings.Role.NEXT, WheelZoomSettings.Role.ZOOM_OUT -> Action.NEXT
            WheelZoomSettings.Role.SELECT -> Action.SELECT
            WheelZoomSettings.Role.MODE -> Action.BACK
            WheelZoomSettings.Role.JOYSTICK, WheelZoomSettings.Role.SIRI,
            WheelZoomSettings.Role.MEDIA_PLAY_PAUSE -> Action.PASS
        }
    }

    /** The joystick ends by itself (auto-off, the setting, the session); true if it was on. */
    fun end(): Boolean {
        if (!on) return false
        on = false
        return true
    }
}

/** When the Siri key opens Siri; no Android types, so it is unit-tested. */
class WheelSiriKey {
    private var lastPressMillis: Long? = null

    enum class DoublePressAction { FIRST, REPEAT, CONFIRMED }

    /**
     * Some head units send a held key as a new press about every 100 ms. Presses closer together than
     * [REPEAT_GAP_MILLIS] belong to the press before them, so a hold opens Siri once.
     */
    fun opens(eventTimeMillis: Long): Boolean {
        val last = lastPressMillis
        lastPressMillis = eventTimeMillis
        return last == null || eventTimeMillis - last >= REPEAT_GAP_MILLIS
    }

    /**
     * Distinguishes the first press, held-key repeats and the second distinct press inside
     * [maximumGapMillis]. Very close presses move the hold boundary so a long stream of repeats can
     * never look like a double press.
     */
    fun onDoublePress(eventTimeMillis: Long, maximumGapMillis: Long): DoublePressAction {
        require(maximumGapMillis >= MIN_DOUBLE_PRESS_GAP_MILLIS)
        val last = lastPressMillis
        val gap = last?.let { eventTimeMillis - it }
        return when {
            gap == null || gap <= 0 || gap > maximumGapMillis -> {
                lastPressMillis = eventTimeMillis
                DoublePressAction.FIRST
            }
            gap < MIN_DOUBLE_PRESS_GAP_MILLIS -> {
                lastPressMillis = eventTimeMillis
                DoublePressAction.REPEAT
            }
            else -> {
                lastPressMillis = null
                DoublePressAction.CONFIRMED
            }
        }
    }

    fun reset() { lastPressMillis = null }

    companion object {
        const val REPEAT_GAP_MILLIS = 400L
        const val MIN_DOUBLE_PRESS_GAP_MILLIS = 180L
        const val MAX_SECOND_PRESS_MILLIS = 1_500L
    }
}

/**
 * One press of BYD's voice key arrives as two keys: the CarPlay pair (327 short, 328 long) for the wheel key
 * service and the generic pair (304 short, 312 long) for the CarPlay window, about half a second apart.
 * Each opens Siri, and a second request ends the Siri session the first one began, so only the first of the
 * two within [WINDOW_MILLIS] is sent. No Android types, so it is unit-tested.
 */
class VoiceKeyPair {
    private var carPlayKeyMillis: Long? = null
    private var genericKeyMillis: Long? = null

    fun allowCarPlayKey(nowMillis: Long): Boolean = take(nowMillis, genericKeyMillis) { carPlayKeyMillis = it }
    fun allowGenericKey(nowMillis: Long): Boolean = take(nowMillis, carPlayKeyMillis) { genericKeyMillis = it }

    private fun take(now: Long, other: Long?, record: (Long) -> Unit): Boolean {
        if (other != null && now - other in 0 until WINDOW_MILLIS) return false
        record(now)
        return true
    }

    companion object {
        const val WINDOW_MILLIS = 1_500L

        /** Shared by the wheel key service and the CarPlay window, which run in the same process. */
        val shared = VoiceKeyPair()
    }
}

/** Settings for the wheel's dashboard map zoom, CarPlay joystick and Siri key. */
object WheelZoomSettings {
    private const val PREFS = "diplay_wheel_map_zoom"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BEHAVIOUR = "behaviour"
    private const val KEY_JOYSTICK = "joystick"
    private const val KEY_JOYSTICK_AUTO_OFF = "joystick_auto_off"
    private const val KEY_SIRI = "siri_key"
    private const val KEY_MEDIA_PLAY_PAUSE = "media_play_pause"
    private const val KEY_SIRI_DOUBLE_PRESS = "siri_double_press"
    private const val KEY_SIRI_DOUBLE_PRESS_INTERVAL = "siri_double_press_interval"
    private const val KEY_SIRI_TRIGGER_DELAY = "siri_trigger_delay"
    private const val KEY_SIRI_LONG_ONLY = "siri_long_press_only"
    private const val BYD_KEYS = "simulate-keys"
    const val TIMED_MODE_MILLIS = 5_000L
    const val JOYSTICK_IDLE_MILLIS = 15_000L
    val SIRI_DOUBLE_PRESS_INTERVALS = listOf(300, 400, 500, 600, 800, 1_000)
    val SIRI_TRIGGER_DELAYS = listOf(0, 100, 200, 300, 500, 800, 1_000, 1_500)
    const val DEFAULT_SIRI_DOUBLE_PRESS_INTERVAL_MILLIS = 600
    const val DEFAULT_SIRI_TRIGGER_DELAY_MILLIS = 300

    /**
     * Defaults are a BYD Tang's wheel: the custom key, volume up and down (the roller), the media key,
     * previous, next and play/pause. The Siri key has no default: DiPlay's screen already takes BYD's voice key.
     */
    enum class Role(val defaultKey: WheelKey?) {
        MODE(WheelKey(305, 300, BYD_KEYS)),
        ZOOM_IN(WheelKey(291, 115, BYD_KEYS)),
        ZOOM_OUT(WheelKey(292, 114, BYD_KEYS)),
        JOYSTICK(WheelKey(289, 89, BYD_KEYS)),
        PREVIOUS(WheelKey(88, 268, BYD_KEYS)),
        NEXT(WheelKey(87, 270, BYD_KEYS)),
        SELECT(WheelKey(353, 505, BYD_KEYS)),
        SIRI(null),
        MEDIA_PLAY_PAUSE(null),
    }

    /** The mode key switches zoom mode until pressed again, or turns it on for a few seconds. */
    enum class Behaviour { TOGGLE, TIMED }

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun behaviour(context: Context): Behaviour =
        Behaviour.entries.firstOrNull { it.name == prefs(context).getString(KEY_BEHAVIOUR, null) } ?: Behaviour.TOGGLE

    fun setBehaviour(context: Context, behaviour: Behaviour) {
        prefs(context).edit().putString(KEY_BEHAVIOUR, behaviour.name).apply()
        WheelKeyService.settingsChanged()
    }

    fun joystick(context: Context): Boolean = prefs(context).getBoolean(KEY_JOYSTICK, false)

    fun setJoystick(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_JOYSTICK, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun joystickAutoOff(context: Context): Boolean = prefs(context).getBoolean(KEY_JOYSTICK_AUTO_OFF, true)

    fun setJoystickAutoOff(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_JOYSTICK_AUTO_OFF, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun siriKey(context: Context): Boolean = prefs(context).getBoolean(KEY_SIRI, false)

    fun mediaPlayPause(context: Context): Boolean = prefs(context).getBoolean(KEY_MEDIA_PLAY_PAUSE, false)

    fun setMediaPlayPause(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEDIA_PLAY_PAUSE, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    /** With this on, only the wheel's long press opens Siri; a short press of the voice key does nothing. */
    fun siriLongPressOnly(context: Context): Boolean = prefs(context).getBoolean(KEY_SIRI_LONG_ONLY, false)

    fun setSiriLongPressOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIRI_LONG_ONLY, enabled).apply()
    }

    fun setSiriKey(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIRI, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun siriDoublePress(context: Context): Boolean = prefs(context).getBoolean(KEY_SIRI_DOUBLE_PRESS, false)

    fun setSiriDoublePress(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIRI_DOUBLE_PRESS, enabled).apply()
        WheelKeyService.settingsChanged()
    }

    fun siriDoublePressIntervalMillis(context: Context): Int =
        prefs(context).getInt(KEY_SIRI_DOUBLE_PRESS_INTERVAL, DEFAULT_SIRI_DOUBLE_PRESS_INTERVAL_MILLIS)
            .takeIf { it in SIRI_DOUBLE_PRESS_INTERVALS } ?: DEFAULT_SIRI_DOUBLE_PRESS_INTERVAL_MILLIS

    fun setSiriDoublePressIntervalMillis(context: Context, value: Int) {
        require(value in SIRI_DOUBLE_PRESS_INTERVALS)
        prefs(context).edit().putInt(KEY_SIRI_DOUBLE_PRESS_INTERVAL, value).apply()
        WheelKeyService.settingsChanged()
    }

    fun siriTriggerDelayMillis(context: Context): Int =
        prefs(context).getInt(KEY_SIRI_TRIGGER_DELAY, DEFAULT_SIRI_TRIGGER_DELAY_MILLIS)
            .takeIf { it in SIRI_TRIGGER_DELAYS } ?: DEFAULT_SIRI_TRIGGER_DELAY_MILLIS

    fun setSiriTriggerDelayMillis(context: Context, value: Int) {
        require(value in SIRI_TRIGGER_DELAYS)
        prefs(context).edit().putInt(KEY_SIRI_TRIGGER_DELAY, value).apply()
        WheelKeyService.settingsChanged()
    }

    /** Whether any setting needs the key service. */
    fun anyEnabled(context: Context): Boolean =
        enabled(context) || joystick(context) || siriKey(context) || mediaPlayPause(context)

    fun isSiriKey(context: Context, key: WheelKey): Boolean = siriKey(context) && key(context, Role.SIRI) == key

    fun isMediaPlayPauseKey(context: Context, key: WheelKey): Boolean =
        mediaPlayPause(context) && !CarPlayRemoteKeys.isProtectedNavigationKey(key.code) &&
            key(context, Role.MEDIA_PLAY_PAUSE) == key

    /**
     * The active role that already has [key], when [role] would share it with the Siri key: [roleOf] gives a
     * shared key to the earlier role, so the Siri key would never fire. Zoom and joystick keys keep their rules.
     */
    fun conflict(context: Context, role: Role, key: WheelKey): Role? {
        val zoom = enabled(context)
        val joystick = joystick(context)
        val active = Role.entries.filter {
            when (it) {
                Role.SIRI -> siriKey(context)
                Role.MEDIA_PLAY_PAUSE -> mediaPlayPause(context)
                Role.MODE, Role.ZOOM_IN, Role.ZOOM_OUT -> zoom || joystick
                else -> joystick
            }
        }
        return active.firstOrNull { it != role &&
            (it == Role.SIRI || role == Role.SIRI ||
                it == Role.MEDIA_PLAY_PAUSE || role == Role.MEDIA_PLAY_PAUSE) && key(context, it) == key }
    }

    fun key(context: Context, role: Role): WheelKey? =
        WheelKey.decode(prefs(context).getString("key_${role.name}", null)) ?: role.defaultKey

    fun assign(context: Context, role: Role, key: WheelKey) {
        prefs(context).edit().putString("key_${role.name}", key.encode()).apply()
        WheelKeyService.settingsChanged()
    }

    /** The zoom or joystick role with [key]; [isSiriKey] checks the Siri role, which has its own switch. */
    fun roleOf(context: Context, key: WheelKey): Role? =
        Role.entries.firstOrNull { it != Role.SIRI && it != Role.MEDIA_PLAY_PAUSE && key(context, it) == key }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
