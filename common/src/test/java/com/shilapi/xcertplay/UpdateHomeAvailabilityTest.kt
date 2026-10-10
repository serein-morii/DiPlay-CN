package com.shilapi.xcertplay

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.update.UpdateAvailability
import com.shilapi.xcertplay.update.UpdateRelease
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w900dp-h600dp-land")
class UpdateHomeAvailabilityTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @After fun clear() = UpdateAvailability.clearAllForTest(application)

    @Test @org.junit.Ignore("CN updater has a different flow") fun cachedUpdateAppearsBelowTheVersionAndOpensTheExistingDownloadFlow() {
        val release = release()
        UpdateAvailability.save(application, release)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val text = activity.getString(R.string.update_available_home)
        val update = descendants(activity.window.decorView).filterIsInstance<TextView>()
            .single { it.text == text }

        assertTrue(update.isClickable)
        assertTrue(update.isFocusable)
        assertEquals(DiPlayPalette.DARK.warning, update.currentTextColor)
        update.performClick()

        assertEquals("about", ReflectionHelpers.getField<String>(activity, "page"))
        assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any {
            it.text == activity.getString(R.string.update_download)
        })
        controller.pause().stop().destroy()
    }

    @Config(sdk = [29], qualifiers = "en-w400dp-h900dp-port")
    @Test fun cachedUpdateKeepsAnArmLengthTargetInCompactHome() {
        val release = release()
        UpdateAvailability.save(application, release)
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        val activity = controller.get()
        val update = descendants(activity.window.decorView).filterIsInstance<TextView>().single {
            it.text == activity.getString(R.string.update_available_home)
        }

        assertTrue(update.minHeight >= (48 * activity.resources.displayMetrics.density).toInt())
        assertTrue(update.isClickable)
        controller.pause().stop().destroy()
    }

    private fun release() = UpdateRelease(
        tagName = "v99.0.0",
        apkName = "DiPlay.apk",
        apkUrl = "https://example.com/DiPlay.apk",
        checksumsUrl = "https://example.com/SHA256SUMS.txt",
    )

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
