package com.shilapi.xcertplay.hud

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** A zero-data OS lock, held through the worker's final restoration. Process death releases it. */
internal class AmbientWorkerOwnerLock private constructor(
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        try { if (lock.isValid) lock.release() } finally { file.close() }
    }

    companion object {
        // Tests supply a temporary file. The production worker never accepts a path argument.
        fun tryAcquire(path: File): AmbientWorkerOwnerLock? {
            if (isSymlink(path)) throw IOException("lock path is a symlink")
            val file = RandomAccessFile(path, "rw")
            try {
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) { file.close(); return null }
                return AmbientWorkerOwnerLock(file, lock)
            } catch (failure: Throwable) {
                file.close()
                throw failure
            }
        }

        // Only the last component is checked, so a symlinked parent still works. The check is not atomic with the
        // open: a link planted between the two is still followed. A dangling link has no canonical form, so it is
        // found by its directory entry.
        private fun isSymlink(path: File): Boolean {
            val parent = path.absoluteFile.parentFile?.canonicalFile ?: return false
            val entry = File(parent, path.name)
            return entry.canonicalPath != entry.path || (!entry.exists() && parent.list()?.contains(path.name) == true)
        }
    }
}
