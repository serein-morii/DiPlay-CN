package com.shilapi.xcertplay.network

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Preserve Android rename replacement semantics on hosts where File.renameTo cannot overwrite. */
@Implements(AtomicFile::class)
class AtomicFileRenameTestShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(source: File, target: File) {
            // AtomicFile also replaces an empty directory accidentally left at its base path.
            if (target.isDirectory) Files.delete(target.toPath())
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
