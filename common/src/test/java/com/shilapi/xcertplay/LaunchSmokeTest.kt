package com.shilapi.xcertplay

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Launch-path smoke: anything that crashes here crashes the app on open. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class LaunchSmokeTest {
    @Test fun diplayActivityLaunches() {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        controller.create().start().resume()
        controller.get()
    }

    @Test fun carPlayHostLaunches() {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java)
        controller.create().start().resume()
        controller.get()
    }

    // Han DM-i / DiLink 5 is 1920-wide; the two-column menu reflow only runs there.
    @Test
    @Config(sdk = [29], qualifiers = "en-w1920dp-h1080dp-mdpi")
    fun carPlayHostLaunchesOnWideHeadUnit() {
        val controller = Robolectric.buildActivity(CarPlayHostActivity::class.java)
        controller.create().start().resume()
        controller.get()
    }
}
