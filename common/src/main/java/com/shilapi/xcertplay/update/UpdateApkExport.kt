package com.shilapi.xcertplay.update

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Copies a verified APK where a file manager can reach it, for head units that cannot use the system installer. */
internal object UpdateApkExport {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val DOWNLOADS_DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/DiPlay"

    /** Returns the path to show the driver, or null when no public copy could be made. */
    fun copy(context: Context, apk: File): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return copyToDownloads(context.contentResolver, apk)
            } catch (_: Exception) {
                // Fall through: some head units ship without a working Downloads provider.
            }
        }
        return try {
            val directory = File(context.getExternalFilesDir(null) ?: return null, "update")
            copyToDirectory(directory, apk).absolutePath
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToDirectory(directory: File, apk: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update storage is unavailable")
        val destination = File(directory, apk.name)
        if (!destination.isFile || UpdateChecksums.sha256Hex(destination) != UpdateChecksums.sha256Hex(apk)) {
            val staged = File.createTempFile("diplay-update-", ".partial", directory)
            try {
                apk.copyTo(staged, overwrite = true)
                if (!staged.renameTo(destination)) throw IOException("Update could not be published")
            } finally {
                staged.delete()
            }
        }
        // Keep only the newest APK so a long-lived head unit does not collect old releases.
        directory.listFiles()?.filter { it.isFile && it != destination && it.name.endsWith(".apk") }
            ?.forEach { it.delete() }
        return destination
    }

    private fun copyToDownloads(resolver: ContentResolver, apk: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_DIRECTORY)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the update")
        try {
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Update destination is unavailable")
            apk.inputStream().use { input -> output.use { input.copyTo(it) } }
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the update")
            // MediaStore renames on a name clash, so report the name it actually stored.
            val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: apk.name
            // Only after the new APK is published may older app-owned Downloads copies be removed.
            // A broken provider must not turn a successful export into a failed update.
            runCatching {
                val oldCopies = mutableListOf<Uri>()
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.MIME_TYPE}=?",
                    arrayOf("$DOWNLOADS_DIRECTORY/", APK_MIME),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        oldCopies += ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                    }
                }
                // A provider may canonicalize the inserted URI to a different volume name.
                // Compare row IDs, not URI strings, so cleanup cannot delete the new APK.
                oldCopies.filterNot { sameDownloadRow(it, uri) }
                    .forEach { old -> runCatching { resolver.delete(old, null, null) } }
            }
            return "$DOWNLOADS_DIRECTORY/$name"
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    internal fun sameDownloadRow(first: Uri, second: Uri): Boolean =
        ContentUris.parseId(first) == ContentUris.parseId(second)
}
