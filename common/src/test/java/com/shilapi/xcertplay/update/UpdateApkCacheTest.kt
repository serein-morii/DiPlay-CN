package com.shilapi.xcertplay.update

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class UpdateApkCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private val release = UpdateRelease(
        "v0.2.18-cn.2",
        "DiPlay-cn-v0.2.18-cn.2.apk",
        "https://example.test/update.apk",
        "https://example.test/update.sha256",
    )

    @Test fun publishedPackageCanBeReusedButTamperingForcesAnotherDownload() {
        val directory = UpdateApkCache.directory(temporary.root, release).apply { mkdirs() }
        val stagedApk = File(directory, "download.partial").apply { writeText("verified package") }
        val stagedChecksums = File(directory, "checksums.partial").apply {
            writeText("${UpdateChecksums.sha256Hex(stagedApk)}  ${release.apkName}\n")
        }
        assertNull(UpdateApkCache.verifiedApk(temporary.root, release))
        val published = UpdateApkCache.publish(temporary.root, release, stagedApk, stagedChecksums)
        assertEquals(published, UpdateApkCache.verifiedApk(temporary.root, release))
        published.appendText("changed")
        assertNull(UpdateApkCache.verifiedApk(temporary.root, release))
    }

    @Test fun theSameReleaseUsesOneDownloadLock() {
        assertSame(UpdateApkCache.lock(release), UpdateApkCache.lock(release))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pathTraversalInReleaseNameIsRejected() {
        UpdateApkCache.directory(temporary.root, release.copy(tagName = "../other"))
    }
}
