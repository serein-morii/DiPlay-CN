package com.shilapi.xcertplay

import android.content.Intent
import com.shilapi.xcertplay.DiPlayActivity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CN ships its own four-channel updater; its install intent must work on every supported API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33])
class UpdateInstallCompatibilityTest {
    @Test fun installationUsesOnlySupportedPermissionApis() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val apk = File(activity.cacheDir, "updates/DiPlay.apk").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        assertEquals(true, AppUpdate.install(activity, apk))
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("application/vnd.android.package-archive", intent.type)
    }
}
