package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [AtomicFileRenameTestShadow::class])
class HotspotJoinJournalTest {
    private val codec = object : HotspotJoinJournal.Codec<String> {
        override fun encode(config: String) = config.toByteArray()
        override fun decode(bytes: ByteArray) = String(bytes)
    }
    @Test fun eachTransactionStageReplacesTheExistingJournalAndSurvivesRecreation() {
        val directory = Files.createTempDirectory("synthetic-hotspot-stages").toFile()
        try {
            val record = HotspotJoinTransaction.Record("token", "firmware-a", "synthetic-before",
                "synthetic-after", HotspotJoinTransaction.Stage.PREPARED)
            for (stage in HotspotJoinTransaction.Stage.entries) {
                HotspotJoinJournal(directory, "firmware-a", codec).save(record.at(stage))
                val saved = HotspotJoinJournal(directory, "firmware-a", codec).load()!!
                assertEquals(stage, saved.stage)
                assertEquals(record.original, saved.original)
                assertEquals(record.target, saved.target)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun storesCompletePrivateSnapshotsAndRefusesCorruptionOrFirmwareChanges() {
        val directory = Files.createTempDirectory("synthetic-hotspot").toFile()
        try {
            val store = HotspotJoinJournal(directory, "firmware-a", codec)
            val record = HotspotJoinTransaction.Record("opaque-token", "firmware-a", "synthetic-secret",
                "synthetic-secret-plus-ie", HotspotJoinTransaction.Stage.PENDING)
            store.save(record)
            assertEquals(record.original, HotspotJoinJournal(directory, "firmware-a", codec).load()!!.original)
            try { HotspotJoinJournal(directory, "firmware-b", codec).load(); fail("Firmware changed") }
            catch (_: java.io.IOException) { }
            val file = directory.resolve("journal")
            val bytes = file.readBytes(); bytes[bytes.lastIndex] = (bytes.last() + 1).toByte(); file.writeBytes(bytes)
            try { store.load(); fail("Corrupt backup") } catch (_: java.io.IOException) { }
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedDirectorySyncIsNotReportedAsADurableBackup() {
        val directory = Files.createTempDirectory("synthetic-hotspot-sync").toFile()
        try {
            val store = HotspotJoinJournal(directory, "firmware-a", codec) { throw java.io.IOException() }
            try {
                store.save(HotspotJoinTransaction.Record("token", "firmware-a", "synthetic-before",
                    "synthetic-after", HotspotJoinTransaction.Stage.PENDING))
                fail("Unverified durable rename")
            } catch (_: java.io.IOException) { }
        } finally { directory.deleteRecursively() }
    }
    @Test fun failedFileSyncPreventsTheHotspotWrite() {
        val directory = Files.createTempDirectory("synthetic-hotspot-file-sync").toFile()
        try {
            var failSync = false
            var syncCalls = 0
            val store = HotspotJoinJournal(directory, "firmware-a", codec,
                syncFile = { syncCalls++; if (failSync) throw java.io.IOException() else it.fd.sync() })
            var current = "synthetic-before"
            var writes = 0
            val port = object : HotspotJoinTransaction.Port<String> {
                override fun firmware() = "firmware-a"
                override fun read() = current
                override fun repaired(config: String) = "synthetic-after"
                override fun hotspotOff() = true
                override fun write(config: String): Boolean { writes++; current = config; return true }
            }
            val transaction = HotspotJoinTransaction(port, store)
            val token = transaction.prepare().token!!
            assertEquals(1, syncCalls)
            failSync = true
            assertEquals(HotspotJoinTransaction.Status.STORAGE_FAILED, transaction.apply(token).status)
            assertEquals(0, writes)
            assertEquals("synthetic-before", current)
            assertEquals(HotspotJoinTransaction.Stage.PREPARED, store.load()!!.stage)
        } finally { directory.deleteRecursively() }
    }
}
