package com.shilapi.xcertplay

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class NowPlayingArtworkQueueTest {
    private val worker = ManualExecutor()
    private val main = ManualExecutor()
    private val decoded = mutableListOf<Int>()
    private val published = mutableListOf<Pair<Int, Int?>>()
    private val discarded = mutableListOf<Int>()
    private var onDecode: (Int) -> Unit = {}
    private val queue = NowPlayingArtworkQueue(
        worker, main,
        decode = { bytes ->
            val value = bytes[0].toInt()
            decoded += value
            onDecode(value)
            value
        },
        publish = { _, id, image -> published += id to image },
        discard = { discarded += it },
    )

    @Test fun sustainedTransfersKeepOnlyFourPendingPayloadsAndOneWorkerTask() {
        val owner = queue.newSession()
        repeat(16) { id -> queue.submit(owner, id, ByteArray(2 * 1024 * 1024).apply { this[0] = id.toByte() }) }
        assertEquals(1, worker.size)
        worker.runNext()
        assertEquals(listOf(12, 13, 14, 15), decoded)
        assertEquals(1, main.size)
        main.runNext()
        assertEquals(listOf(12, 13, 14, 15), published.map { it.first })
    }

    @Test fun transfersArrivingDuringDecodeAlsoKeepOnlyTheLastFour() {
        val owner = queue.newSession()
        onDecode = { value ->
            if (value == 1) for (id in 2..20) queue.submit(owner, id, byteArrayOf(id.toByte()))
        }
        queue.submit(owner, 1, byteArrayOf(1))
        worker.runNext()
        assertEquals(listOf(1, 17, 18, 19, 20), decoded)
        assertEquals(listOf(1), discarded)
        assertEquals(1, main.size)
        main.runNext()
        assertEquals(listOf(17, 18, 19, 20), published.map { it.first })
    }

    @Test fun aBlockedMainThreadRetainsOnlyFourImagesAndOneDeliveryTask() {
        val owner = queue.newSession()
        repeat(16) { id ->
            queue.submit(owner, id, byteArrayOf(id.toByte()))
            worker.runNext()
        }
        assertEquals(1, main.size)
        assertEquals((0..11).toList(), discarded)
        main.runNext()
        assertEquals(listOf(12, 13, 14, 15), published.map { it.first })
    }

    @Test fun repeatedTransferIdsCoalesceBeforeDecodeAndBeforePublication() {
        val owner = queue.newSession()
        queue.submit(owner, 1, byteArrayOf(1))
        queue.submit(owner, 1, byteArrayOf(2))
        worker.runNext()
        queue.submit(owner, 1, byteArrayOf(3))
        worker.runNext()
        assertEquals(listOf(2, 3), decoded)
        assertEquals(listOf(2), discarded)
        main.runNext()
        assertEquals(listOf(1 to 3), published)
    }

    @Test fun supersededDecodedImageIsNotPublishedWhileItsReplacementIsQueued() {
        val owner = queue.newSession()
        queue.submit(owner, 1, byteArrayOf(1))
        worker.runNext()
        queue.submit(owner, 1, byteArrayOf(2))
        main.runNext()
        assertTrue(published.isEmpty())
        assertEquals(listOf(1), discarded)
        worker.runNext()
        main.runNext()
        assertEquals(listOf(1 to 2), published)
    }

    @Test fun teardownDropsQueuedBytesAndUnpublishedImages() {
        val owner = queue.newSession()
        queue.submit(owner, 1, byteArrayOf(1))
        worker.runNext()
        queue.submit(owner, 2, byteArrayOf(2))
        queue.clear()
        worker.runNext()
        main.runNext()
        assertEquals(listOf(1), decoded)
        assertEquals(listOf(1), discarded)
        assertTrue(published.isEmpty())
    }

    @Test fun aNewSessionRejectsOldCallbacksAndInFlightDecodeResults() {
        val old = queue.newSession()
        var next: Any? = null
        onDecode = { value ->
            if (value == 1) {
                next = queue.newSession()
                queue.submit(next!!, 2, byteArrayOf(2))
                queue.submit(old, 3, byteArrayOf(3))
            }
        }
        queue.submit(old, 1, byteArrayOf(1))
        worker.runNext()
        main.runNext()
        assertNotSame(old, next)
        assertEquals(listOf(1, 2), decoded)
        assertEquals(listOf(1), discarded)
        assertEquals(listOf(2 to 2), published)
    }

    @Test fun oversizedPayloadsAreRejectedBeforeDecode() {
        val owner = queue.newSession()
        queue.submit(owner, 1, ByteArray(2 * 1024 * 1024 + 1))
        assertEquals(0, worker.size)
        assertTrue(decoded.isEmpty())
    }

    @Test fun emptyArtworkDoesNotCancelPendingOrDecodedValidTransfer() {
        val owner = queue.newSession()
        queue.submit(owner, 7, byteArrayOf(42))
        queue.submit(owner, 7, ByteArray(0))
        worker.runNext()
        queue.submit(owner, 7, ByteArray(0))
        main.runNext()
        assertEquals(listOf(7 to 42), published)
        assertTrue(discarded.isEmpty())
        assertEquals(0, worker.size)
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size get() = tasks.size
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
    }
}
