package com.shilapi.xcertplay.media

import android.content.Context
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AmbientMusicTickingTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun noLampUpdateRunsWhileTheFeatureIsOff() {
        context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().clear().commit()
        val sink = AmbientMusicController.openSink(context)
        try {
            settle()
            assertNull(field("ticking"))
            AmbientMusicController.settingsChanged(context)
            settle()
            assertNull(field("ticking"))
        } finally {
            AmbientMusicController.closeSink(sink)
            settle()
        }
    }

    @Test fun theCarsOwnRhythmKeepsTheUpdateRunningWithoutLampControl() {
        context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("carRhythm", true).commit()
        val sink = AmbientMusicController.openSink(context)
        try {
            settle()
            org.junit.Assert.assertNotNull(field("ticking"))
            context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().putBoolean("carRhythm", false).commit()
            AmbientMusicController.settingsChanged(context)
            settle()
            assertNull(field("ticking"))
        } finally {
            AmbientMusicController.closeSink(sink)
            context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE).edit().clear().commit()
            settle()
        }
    }

    private fun settle() {
        (field("executor") as ExecutorService).submit {}.get(2, TimeUnit.SECONDS)
    }

    private fun field(name: String): Any? = AmbientMusicController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(AmbientMusicController)
}
