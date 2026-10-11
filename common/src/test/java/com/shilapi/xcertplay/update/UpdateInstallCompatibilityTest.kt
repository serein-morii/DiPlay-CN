package com.shilapi.xcertplay.update

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.DiPlayActivity
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 28, 33])
class UpdateInstallCompatibilityTest {
    private val apkType = "application/vnd.android.package-archive"
    private val publicCopy = Uri.parse("content://media/external/downloads/42")

    // Robolectric gives each test a new cache dir; FileProvider keeps the first one in a static cache.
    @Before fun resetFileProviderCache() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    private fun install(publicUriReadable: Boolean, installers: Int, publicBytes: ByteArray = byteArrayOf(1)): Intent {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.updateInstallWorker = { it.run() }
        val apk = File(activity.cacheDir, "update/test/DiPlay.apk").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        if (publicUriReadable) shadowOf(activity.contentResolver).registerInputStream(publicCopy, ByteArrayInputStream(publicBytes))
        val fields = mapOf("updateFile" to apk, "updateSavedUri" to publicCopy)
        fields.forEach { (name, value) ->
            DiPlayActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
        }
        val query = Intent(Intent.ACTION_VIEW).setDataAndType(if (publicUriReadable) publicCopy else Uri.parse("content://x"), apkType)
        repeat(installers) {
            shadowOf(activity.packageManager).addResolveInfoForIntent(query, ResolveInfo().apply {
                activityInfo = ActivityInfo().apply { packageName = "installer$it"; name = "Install" }
            })
        }
        DiPlayActivity::class.java.getDeclaredMethod("installUpdate").apply { isAccessible = true }.invoke(activity)
        return shadowOf(activity).nextStartedActivity
    }

    @Test fun severalInstallersAskTheDriver() {
        val started = install(publicUriReadable = true, installers = 2)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val view = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(publicCopy, view.data)
        assertEquals(apkType, view.type)
    }

    @Test fun oneInstallerOpensDirectlyWithoutPermissionPrecheck() {
        val started = install(publicUriReadable = true, installers = 1)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(publicCopy, started.data)
        val report = UpdateAvailability.report(RuntimeEnvironment.getApplication(), System.currentTimeMillis())
        assertEquals(true, report.contains("lastInstall=direct uri=public installers=1"))
    }

    @Test fun swappedPublicCopyFallsBackToTheProviderUri() {
        val started = install(publicUriReadable = true, installers = 0, publicBytes = byteArrayOf(2))
        assertEquals(true, started.data!!.authority!!.endsWith(".update-apks"))
    }

    // Robolectric before API 29 opens unknown content URIs without error, so it cannot model a deleted row.
    @Config(sdk = [33])
    @Test fun deletedPublicCopyFallsBackToTheProviderUri() {
        val started = install(publicUriReadable = false, installers = 0)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("content", started.data!!.scheme)
        assertEquals(true, started.data!!.authority!!.endsWith(".update-apks"))
    }
}
