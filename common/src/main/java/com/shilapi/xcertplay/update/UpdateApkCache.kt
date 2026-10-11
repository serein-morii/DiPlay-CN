package com.shilapi.xcertplay.update

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Private, version-scoped copies of verified update packages. Cache eviction only costs another download. */
internal object UpdateApkCache {
    private val releaseLocks = ConcurrentHashMap<String, Any>()
    private val validTag = Regex("^v\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?-cn\\.\\d+$")

    fun directory(cacheDir: File, release: UpdateRelease): File {
        require(validTag.matches(release.tagName)) { "Invalid update version" }
        require(release.apkName == "DiPlay-cn-${release.tagName}.apk") { "Invalid update filename" }
        return File(File(cacheDir, "update"), release.tagName)
    }

    fun lock(release: UpdateRelease): Any = releaseLocks.computeIfAbsent(release.tagName) { Any() }

    /** A private APK is reusable only while its saved published checksum still matches. */
    fun verifiedApk(cacheDir: File, release: UpdateRelease): File? {
        val directory = directory(cacheDir, release)
        val apk = File(directory, release.apkName)
        val checksums = File(directory, UpdateCatalog.CHECKSUMS_FILE)
        if (!apk.isFile || !checksums.isFile || checksums.length() > 1024 * 1024) return null
        val expected = runCatching { UpdateChecksums.parse(checksums.readText()) }.getOrNull() ?: return null
        val actual = runCatching { UpdateChecksums.sha256Hex(apk) }.getOrNull() ?: return null
        return apk.takeIf { UpdateChecksums.matches(expected, release.apkName, actual) }
    }

    /** Publish the already verified pair only after both downloads and APK inspection succeed. */
    fun publish(cacheDir: File, release: UpdateRelease, stagedApk: File, stagedChecksums: File): File {
        val directory = directory(cacheDir, release)
        val apk = File(directory, release.apkName)
        val checksums = File(directory, UpdateCatalog.CHECKSUMS_FILE)
        if (apk.exists() && !apk.delete()) throw IOException("Could not replace the update APK")
        if (!stagedApk.renameTo(apk)) throw IOException("Could not save the update APK")
        if (checksums.exists() && !checksums.delete()) {
            apk.delete()
            throw IOException("Could not replace update checksums")
        }
        if (!stagedChecksums.renameTo(checksums)) {
            apk.delete()
            throw IOException("Could not save update checksums")
        }
        return apk
    }
}
