package com.shilapi.xcertplay.hud

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BydClusterMapPauseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val release = CountDownLatch(1)

    @After
    fun tearDown() {
        release.countDown()
        BydClusterMapPause.streamControl = null
        BydClusterMapPause.onNaviMode = null
        BydClusterMapPause.clusterMapShown = false
        BydClusterMapPause.onNaviMode = null
    }

    @Test
    fun disablingPauseResumesMapWhileLayoutFollowingRemainsEnabled() {
        BydClusterMapPause.readMode = { BydClusterNaviMode.OFF }
        BydClusterMapPause.onNaviMode = {}
        BydClusterMapPause.clusterMapShown = true
        var streaming = true
        BydClusterMapPause.streamControl = { streaming = it }
        BydClusterMapPause::class.java.getDeclaredField("context")
            .apply { isAccessible = true }.set(null, context)
        val tick = BydClusterMapPause::class.java.getDeclaredMethod("tick").apply { isAccessible = true }
        BydOutputSettings.setClusterStreamPause(context, true)
        tick.invoke(BydClusterMapPause)
        org.junit.Assert.assertFalse(streaming)
        BydOutputSettings.setClusterStreamPause(context, false)
        tick.invoke(BydClusterMapPause)
        assertTrue(streaming)
    }

    @Test
    fun initializationDoesNotWaitForAPendingAdbRead() {
        val reading = CountDownLatch(1)
        // An adbd that accepted the read and has not answered yet.
        BydClusterMapPause.readMode = {
            reading.countDown()
            release.await()
            null
        }
        BydOutputSettings.setClusterStreamPause(context, true)
        BydClusterMapPause.clusterMapShown = true
        BydClusterMapPause.streamControl = {}
        BydClusterMapPause.initialize(context)
        assertTrue("the ticker should start reading", reading.await(5, TimeUnit.SECONDS))

        val initialized = CountDownLatch(1)
        thread { BydClusterMapPause.initialize(context); initialized.countDown() }

        assertTrue("initialize() waited for the adb read", initialized.await(1, TimeUnit.SECONDS))
    }
}
