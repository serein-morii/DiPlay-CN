package com.shilapi.xcertplay

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SessionLogFileLatencyTest {
    private val line = "connection state " + "x".repeat(300)
    private val counter = AtomicInteger()
    private val linesPerCycle = (SessionLogFile.MAX_BYTES / (line.length + 20)).toInt() + 5

    private fun numbered() = "$line ${counter.incrementAndGet()}"

    private fun nanosOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return System.nanoTime() - start
    }

    private fun percentile(sorted: List<Long>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    private fun SessionLogFile.flushed() = assertTrue("writer did not drain", awaitIdle(30_000))

    /** Holds the file lock, which stalls the writer thread without a test hook in production code. */
    private fun stallWriter(log: SessionLogFile, holdMillis: Long): Thread {
        val lock = SessionLogFile::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(log)
        val held = CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) { held.countDown(); Thread.sleep(holdMillis) }
        }
        holder.start()
        assertTrue(held.await(5, java.util.concurrent.TimeUnit.SECONDS))
        return holder
    }

    /** Leaves every archive present and the current file empty. */
    private fun fillWithArchives(folder: File): SessionLogFile {
        repeat(SessionLogFile.REPORT_NAMES.size) { session ->
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("session=$session")
            repeat(linesPerCycle) { log.append(numbered()) }
            log.flushed()
            log.close()
        }
        val log = SessionLogFile(folder.resolve("diplay.log"))
        log.reset("final")
        return log
    }

    private fun firstLineOf(file: File) = file.useLines { it.first() }

    @Test fun callerLatencyPerLineStaysFarBelowOneDiskOpenPerCall() {
        val folder = Files.createTempDirectory("diplay-latency").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("started")
            // Batches stay below the 4096-line queue, so every timed call is an accepted line.
            val samples = ArrayList<Long>()
            repeat(20) {
                repeat(500) { samples += nanosOf { log.append(line) } }
                log.flushed()
            }
            samples.sort()
            log.close()
            println("SESSIONLOG per-call p50=${percentile(samples, 0.5) / 1000}us p99=${percentile(samples, 0.99) / 1000}us max=${samples.last() / 1000}us")
            // main measures 62 us (one file open per call); the queue is sub-microsecond. 20 us is 3x under main.
            assertTrue("p50 ${percentile(samples, 0.5)}ns", percentile(samples, 0.5) < 20_000)
        } finally { folder.deleteRecursively() }
    }

    @Test fun theCallThatCrossesTheSizeLimitDoesNotCopyArchivesOnTheCallerThread() {
        val folder = Files.createTempDirectory("diplay-rotation").toFile()
        try {
            val log = fillWithArchives(folder)
            val cycleMaxima = mutableListOf<Long>()
            val archiveHeads = mutableSetOf<String>()
            repeat(5) {
                var worst = 0L
                repeat(linesPerCycle) { worst = maxOf(worst, nanosOf { log.append(numbered()) }) }
                cycleMaxima += worst
                log.flushed()
                archiveHeads += firstLineOf(folder.resolve("previous.log"))
            }
            log.close()
            println("SESSIONLOG rotation-cycle worst call ms=${cycleMaxima.map { it / 1_000_000 }}")
            assertTrue("rotations happened: $archiveHeads", archiveHeads.size >= 3)
            assertTrue("median worst call ${cycleMaxima.sorted()[2]}ns", cycleMaxima.sorted()[2] < 4_000_000)
        } finally { folder.deleteRecursively() }
    }

    @Test(timeout = 120_000) fun aSecondThreadIsNotBlockedBehindTheRotatingCaller() {
        val folder = Files.createTempDirectory("diplay-contention").toFile()
        try {
            val log = fillWithArchives(folder)
            val perThread = linesPerCycle / 2
            val blocked = mutableListOf<Long>()
            val archiveHeads = mutableSetOf<String>()
            repeat(5) {
                val start = CountDownLatch(1)
                var otherWorst = 0L
                val other = Thread {
                    start.await()
                    repeat(perThread) { otherWorst = maxOf(otherWorst, nanosOf { log.append(numbered()) }) }
                }
                other.start()
                start.countDown()
                repeat(perThread) { log.append(numbered()) }
                other.join(30_000)
                blocked += otherWorst
                log.flushed()
                archiveHeads += firstLineOf(folder.resolve("previous.log"))
            }
            log.close()
            println("SESSIONLOG second-thread worst blocked ms=${blocked.map { it / 1_000_000 }}")
            assertTrue("rotations happened: $archiveHeads", archiveHeads.size >= 3)
            assertTrue("median blocked ${blocked.sorted()[2]}ns", blocked.sorted()[2] < 4_000_000)
        } finally { folder.deleteRecursively() }
    }

    @Test fun linesKeepTheirOrderAndFormatAndAreRedactedOnceAfterClose() {
        val folder = Files.createTempDirectory("diplay-order").toFile()
        try {
            val file = folder.resolve("diplay.log")
            SessionLogFile(file).use { log ->
                log.reset("header")
                repeat(300) { log.append("12:00:00.000  line $it peer=192.168.1.5") }
                log.append("12:00:00.000  password=secret")
                log.append("12:00:00.000  tail")
                log.flushed()
            }
            val lines = file.readLines()
            assertEquals(listOf("header") + (0 until 300).map { "12:00:00.000  line $it peer=[ip]" } + "12:00:00.000  tail", lines)
        } finally { folder.deleteRecursively() }
    }

    @Test fun rotationStillKeepsTheCurrentFileBoundedAndHistoryIntact() {
        val folder = Files.createTempDirectory("diplay-bound").toFile()
        try {
            val log = fillWithArchives(folder)
            repeat(3_000) { log.append(line) }
            log.append("last line")
            log.flushed()
            log.close()
            assertTrue(folder.resolve("diplay.log").length() <= SessionLogFile.MAX_BYTES + 701)
            assertTrue(folder.resolve("diplay.log").readText().trimEnd().endsWith("last line"))
            assertTrue(folder.resolve("previous.log").length() > 0)
            assertEquals(8, folder.listFiles()!!.size)
        } finally { folder.deleteRecursively() }
    }

    @Test fun droppedTraceLinesNeverEvictLegitimateQueuedLines() {
        val folder = Files.createTempDirectory("diplay-trace").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("header")
            val stall = stallWriter(log, 1_000)
            log.append("first")
            val legit = (0 until 100).map { "legit $it" }
            legit.forEach(log::append)
            repeat(5_000) { log.append("TRACE tx $it") }
            repeat(5_000) { log.append("PHONE rx $it") }
            stall.join()
            log.flushed()
            log.close()
            assertEquals(listOf("header", "first") + legit, folder.resolve("diplay.log").readLines())
        } finally { folder.deleteRecursively() }
    }

    @Test fun anOverflowLeavesOneMarkerWithTheNumberOfDroppedLines() {
        val folder = Files.createTempDirectory("diplay-overflow").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("header")
            val stall = stallWriter(log, 1_000)
            val sent = 5_000
            repeat(sent) { log.append("legit $it") }
            stall.join()
            log.flushed()
            log.close()
            val lines = folder.resolve("diplay.log").readLines()
            val markers = lines.filter { it.contains("lines dropped") }
            assertEquals(lines.toString().take(200), 1, markers.size)
            val dropped = Regex("(\\d+) lines dropped").find(markers.single())!!.groupValues[1].toInt()
            assertTrue(dropped > 0)
            assertEquals(sent, lines.count { it.startsWith("legit") } + dropped)
            val kept = lines.filter { it.startsWith("legit") }.map { it.removePrefix("legit ").toInt() }
            assertEquals(kept.sorted(), kept)
        } finally { folder.deleteRecursively() }
    }

    @Test fun closeReturnsWithinItsCapWhileTheDiskIsStalled() {
        val folder = Files.createTempDirectory("diplay-close-time").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("header")
            val stall = stallWriter(log, 1_500)
            log.append("Activity destroyed")
            val elapsed = nanosOf { log.close() }
            stall.join()
            println("SESSIONLOG close with stalled disk ms=${elapsed / 1_000_000}")
            assertTrue("close took ${elapsed / 1_000_000} ms", elapsed < 700_000_000)
        } finally { folder.deleteRecursively() }
    }

    @Test fun linesStillQueuedWhenCloseGivesUpAreWrittenWhenTheDiskRecovers() {
        val folder = Files.createTempDirectory("diplay-close-flush").toFile()
        try {
            val log = SessionLogFile(folder.resolve("diplay.log"))
            log.reset("header")
            val stall = stallWriter(log, 1_500)
            log.append("one")
            log.append("Activity destroyed")
            log.close()
            stall.join()
            log.flushed()
            assertEquals(listOf("header", "one", "Activity destroyed"), folder.resolve("diplay.log").readLines())
        } finally { folder.deleteRecursively() }
    }
}
