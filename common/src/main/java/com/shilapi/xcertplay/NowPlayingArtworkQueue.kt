package com.shilapi.xcertplay

import com.shilapi.xcertplay.transport.Iap2FileTransferReceiver
import java.util.concurrent.Executor

/** Bounds compressed artwork waiting for decode and decoded images waiting for the main thread. */
internal class NowPlayingArtworkQueue<Image : Any>(
    private val worker: Executor,
    private val main: Executor,
    private val decode: (ByteArray) -> Image?,
    private val publish: (owner: Any, id: Int, image: Image?) -> Unit,
    private val discard: (Image) -> Unit,
) {
    private data class Request(val owner: Any, val id: Int, val version: Long, val bytes: ByteArray)
    private data class Result<Image>(val request: RequestInfo, val image: Image?)
    // Results must not retain the compressed payload while waiting for the main thread.
    private data class RequestInfo(val owner: Any, val id: Int, val version: Long)

    private val lock = Any()
    private var owner: Any? = null
    private var version = 0L
    private val latest = LongArray(256)
    private val pending = LinkedHashMap<Int, Request>()
    private val ready = LinkedHashMap<Int, Result<Image>>()
    private var workerScheduled = false
    private var deliveryScheduled = false

    /** Runs bounded metadata recovery on the existing worker, away from the UI and PCM. */
    fun execute(task: Runnable) = worker.execute(task)

    /** A fresh token also invalidates a decode already in progress. */
    fun newSession(): Any = synchronized(lock) {
        clearLocked()
        Any().also { owner = it }
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        owner = null
        pending.clear()
        ready.values.forEach { it.image?.let(discard) }
        ready.clear()
    }

    fun submit(expected: Any, id: Int, bytes: ByteArray) {
        if (bytes.isEmpty() || id !in 0..255 || bytes.size > Iap2FileTransferReceiver.DEFAULT_MAXIMUM_ARTWORK_BYTES) return
        val schedule = synchronized(lock) {
            if (owner !== expected) return
            latest[id] = ++version
            pending.remove(id)
            pending[id] = Request(expected, id, version, bytes)
            while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
            if (workerScheduled) false else { workerScheduled = true; true }
        }
        // Only one worker runnable is queued, regardless of the number of received transfers.
        if (schedule) worker.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val request = synchronized(lock) {
                if (pending.isEmpty()) { workerScheduled = false; return }
                pending.remove(pending.keys.first())!!
            }
            val info = RequestInfo(request.owner, request.id, request.version)
            if (!isCurrent(info)) continue
            val image = try { decode(request.bytes) } catch (_: RuntimeException) { null }
            val schedule = synchronized(lock) {
                if (!isCurrentLocked(info)) {
                    image?.let(discard)
                    false
                } else {
                    ready.remove(info.id)?.image?.let(discard)
                    ready[info.id] = Result(info, image)
                    while (ready.size > MAX_PENDING) ready.remove(ready.keys.first())?.image?.let(discard)
                    if (deliveryScheduled) false else { deliveryScheduled = true; true }
                }
            }
            if (schedule) main.execute(::deliver)
        }
    }

    private fun deliver() {
        while (true) {
            val result = synchronized(lock) {
                if (ready.isEmpty()) { deliveryScheduled = false; return }
                ready.remove(ready.keys.first())!!
            }
            if (isCurrent(result.request)) {
                // The receiver checks this token again under its own session lock. Never call it
                // while holding our lock: session teardown acquires the locks in the other order.
                publish(result.request.owner, result.request.id, result.image)
            } else result.image?.let(discard)
        }
    }

    private fun isCurrent(info: RequestInfo): Boolean = synchronized(lock) { isCurrentLocked(info) }
    private fun isCurrentLocked(info: RequestInfo): Boolean = owner === info.owner && latest[info.id] == info.version

    private companion object {
        const val MAX_PENDING = 4 // At most 8 MiB compressed, plus the one decode in progress.
    }
}
