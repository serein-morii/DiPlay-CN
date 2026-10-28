package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.util.Log
import java.lang.ref.WeakReference

/**
 * Chooses which declaration of the CarPlay screen opens.
 *
 * Since 0.2.12 [CarPlayHostActivity] runs in a task of its own (taskAffinity `.carplay`), so CarPlay stays open
 * behind DiPlay's settings. Launchers that show DiPlay's task in a card or a picture-in-picture window (BYD's DiYou
 * desktop card, 嘟嘟桌面) then show only DiPlay's start page, and CarPlay opens outside the card (#463, #406, #1003).
 * With "Keep CarPlay in the launcher card" on, [SameTaskCarPlayHostActivity] opens instead, in DiPlay's own task.
 *
 * Only one CarPlay screen exists at a time. While one is open every start goes to it, and a start of the other
 * declaration (an older widget or notification, a USB attachment, a launcher that names the class) is forwarded.
 */
internal object CarPlayScreen {
    private const val TAG = "DiPlay"
    @Volatile private var open: WeakReference<CarPlayHostActivity>? = null

    fun target(context: Context): Class<out CarPlayHostActivity> =
        current()?.javaClass ?: preferred(context)

    fun intent(context: Context): Intent = Intent(context, target(context))

    /** The open CarPlay screen, if it is not closing. */
    fun current(): CarPlayHostActivity? = open?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    private fun preferred(context: Context): Class<out CarPlayHostActivity> =
        if (AirPlayPersistence.loadCarPlaySameTask(context)) SameTaskCarPlayHostActivity::class.java
        else CarPlayHostActivity::class.java

    /**
     * Called first in a new CarPlay screen. When the other declaration should open, starts it with the same intent,
     * closes [screen] and returns true; [screen] then skips its own setup and teardown.
     */
    fun forwarded(screen: CarPlayHostActivity): Boolean {
        val other = current()
        val target = other?.javaClass ?: preferred(screen)
        if (target != screen.javaClass) {
            Log.i(TAG, "CarPlay screen ${screen.javaClass.simpleName} forwarded to ${target.simpleName}")
            // The extras carry the USB device of an attachment and requests such as the picture controls.
            val started = OwnScreenStart.start(screen, Intent(screen.intent).setClass(screen, target).setFlags(0))
            // With no other screen open, CarPlay in the other task is better than no CarPlay screen at all.
            if (started || other != null) {
                if (screen.isTaskRoot) screen.finishAndRemoveTask() else screen.finish()
                return true
            }
        }
        open = WeakReference(screen)
        return false
    }

    fun destroyed(screen: CarPlayHostActivity) {
        if (open?.get() === screen) open = null
    }

    /**
     * After the setting changes, closes an open screen of the other declaration so that CarPlay opens in the chosen
     * task next time. The session keeps running, as it does when the screen is closed from the recent apps list.
     */
    fun closeIfElsewhere(context: Context): Boolean {
        val screen = current() ?: return false
        if (screen.javaClass == preferred(context)) return false
        // The separate task holds nothing else; DiPlay's own task keeps its other screens.
        if (screen is SameTaskCarPlayHostActivity) screen.finish() else screen.finishAndRemoveTask()
        return true
    }

    /** One diagnostic-report line: the setting and where the open screen runs. */
    fun report(context: Context): String = buildString {
        append("sameTask=${AirPlayPersistence.loadCarPlaySameTask(context)}")
        val screen = current()
        if (screen == null) {
            append(" open=none")
        } else {
            append(" open=${screen.javaClass.simpleName} task=${screen.taskId}")
            append(" taskRoot=${runCatching { screen.isTaskRoot }.getOrNull()}")
            append(" multiWindow=${screen.isInMultiWindowMode}")
            append(" display=${screen.window?.decorView?.display?.displayId}")
        }
    }
}
