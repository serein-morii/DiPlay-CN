package com.shilapi.xcertplay

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

// #883, #803: after the iPhone left, DiPlay kept holding the BYD cluster with no map, and the cluster
// stayed in BYD's simple mode until the head unit restarted.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayHostClusterHandbackTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
    }

    @After fun tearDown() {
        AirPlayPersistence.saveClusterMapEnabled(activity, false)
    }

    @Test fun theClusterIsHandedBackFifteenSecondsAfterTheMapStops() {
        clusterStream(true)
        clusterStream(false)
        idle(14)
        assertFalse(released())
        idle(1)
        assertTrue(released())
    }

    @Test fun aQuickReconnectKeepsTheCluster() {
        clusterStream(true)
        clusterStream(false)
        idle(5)
        clusterStream(true)
        idle(30)
        assertFalse(released())
    }

    @Test fun theNextMapTakesTheClusterAgain() {
        clusterStream(true)
        clusterStream(false)
        idle(15)
        assertTrue(released())
        clusterStream(true)
        assertFalse(released())
    }

    @Test fun nothingIsReleasedWhenTheClusterMapIsOff() {
        AirPlayPersistence.saveClusterMapEnabled(activity, false)
        clusterStream(true)
        clusterStream(false)
        idle(15)
        assertFalse(released())
    }

    private fun clusterStream(active: Boolean) {
        activity.javaClass.getDeclaredMethod("onScreenStreamStateChanged",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 0, SCREEN_TYPE_ALT, active)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun released(): Boolean = activity.javaClass.getDeclaredField("clusterReleasedWhileIdle")
        .apply { isAccessible = true }.getBoolean(activity)

    private companion object {
        /** The instrument-cluster screen stream (CarPlayHostActivity.SCREEN_TYPE_ALT). */
        const val SCREEN_TYPE_ALT = 111
    }
}
