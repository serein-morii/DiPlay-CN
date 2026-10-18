package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class OwnScreenStartTest {
    /** Refuses starts the way the DiYou launcher card did (#463). */
    class CardActivity : Activity() {
        override fun startActivity(intent: Intent?) {
            throw IllegalArgumentException("There is no running activity in rootTask=Task{#68 visible=false}")
        }
    }

    private val app get() = shadowOf(RuntimeEnvironment.getApplication())

    @Test fun aRefusedStartOpensTheScreenInANewTaskInstead() {
        val activity = Robolectric.buildActivity(CardActivity::class.java).setup().get()
        val log = mutableListOf<String>()

        val opened = OwnScreenStart.start(activity,
            Intent(activity, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), log::add)

        assertTrue(opened)
        val started = app.nextStartedActivity
        assertEquals(CarPlayHostActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertEquals(1, log.size)
        assertTrue(log.single().startsWith("Opening CarPlayHostActivity refused: There is no running activity in rootTask"))
    }

    @Test fun anAcceptedStartStaysInTheCallersTask() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val log = mutableListOf<String>()

        assertTrue(OwnScreenStart.start(activity, Intent(activity, DiPlayActivity::class.java), log::add))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(DiPlayActivity::class.java.name, started.component?.className)
        assertFalse(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(app.nextStartedActivity)
        assertTrue(log.isEmpty())
    }
}
