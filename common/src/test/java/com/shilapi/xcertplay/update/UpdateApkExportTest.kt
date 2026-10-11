package com.shilapi.xcertplay.update

import android.content.Context
import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Android 9 and older have no MediaStore Downloads, so the copy goes to the app's external files. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateApkExportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun copiesTheApkAndKeepsOnlyTheNewestOne() {
        val directory = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }
        File(directory, "DiPlay-0.2.14.apk").writeText("old")
        val apk = File(context.cacheDir, "DiPlay-0.2.15.apk").apply { writeText("new") }

        val path = UpdateApkExport.copy(context, apk)

        assertEquals(File(directory, "DiPlay-0.2.15.apk").absolutePath, path)
        assertEquals("new", File(path!!).readText())
        assertFalse(File(directory, "DiPlay-0.2.14.apk").exists())
        assertTrue(apk.exists())
    }

    @Test fun failedCopyKeepsThePreviousUsableApk() {
        val directory = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }
        val previous = File(directory, "DiPlay-old.apk").apply { writeText("old") }
        val missing = File(context.cacheDir, "DiPlay-new.apk")
        missing.delete()

        assertEquals(null, UpdateApkExport.copy(context, missing))
        assertEquals("old", previous.readText())
    }

    @Test fun aCanonicalizedUriStillIdentifiesTheNewDownload() {
        val inserted = Uri.parse("content://media/external_primary/downloads/23")
        val queried = Uri.parse("content://media/external/downloads/23")
        val older = Uri.parse("content://media/external/downloads/22")
        assertTrue(UpdateApkExport.sameDownloadRow(inserted, queried))
        assertFalse(UpdateApkExport.sameDownloadRow(inserted, older))
    }
}
