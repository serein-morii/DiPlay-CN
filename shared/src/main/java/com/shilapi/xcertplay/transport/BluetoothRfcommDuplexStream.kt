package com.shilapi.xcertplay.transport

import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import kotlin.math.min

/** Nullable platform getters matter on head units that expose a socket without usable streams. */
internal interface BluetoothRfcommSocketAccess {
    fun inputStream(): InputStream?
    fun outputStream(): OutputStream?
    fun close()
}

class BluetoothRfcommStreamException internal constructor(
    val operation: Operation,
    val reason: Reason,
    cause: Throwable? = null,
) : IOException("Bluetooth RFCOMM ${operation.name.lowercase()} failed: ${reason.name.lowercase()}", cause) {
    enum class Operation { INPUT_STREAM, OUTPUT_STREAM, READ, WRITE }
    enum class Reason { STREAM_UNAVAILABLE, STREAM_ACCESS_FAILED, READ_FAILED, WRITE_FAILED }
}

/**
 * A bounded [BlockingDuplexByteStream] over an already-open RFCOMM socket.
 *
 * Android's RFCOMM input has no per-read timeout, so one daemon reader performs the blocking
 * reads. [close] closes the owned socket, which unblocks that reader.
 */
class BluetoothRfcommDuplexStream internal constructor(
    private val socket: BluetoothRfcommSocketAccess,
    private val onDiagnostic: (String) -> Unit = {},
) : BlockingDuplexByteStream {
    constructor(socket: BluetoothSocket, onDiagnostic: (String) -> Unit = {}) : this(
        object : BluetoothRfcommSocketAccess {
            override fun inputStream(): InputStream? = socket.inputStream
            override fun outputStream(): OutputStream? = socket.outputStream
            override fun close() = socket.close()
        },
        onDiagnostic,
    )

    private val lock = Object()
    private val sendLock = Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var peerEnded = false
    private var closed = false
    private var socketCloseStarted = false
    private var failure: IOException? = null
    private var readCalls = 0L
    private var receivedBytes = 0L
    private var flushAllowed = true

    /** Bytes the peer has delivered so far; zero for as long as nothing answers. */
    val bytesReceived: Long get() = synchronized(lock) { receivedBytes }

    // Acquire both streams before starting any reader or iAP2 protocol thread. Android getters
    // have platform-nullability, and some vendor sockets return null after connect() succeeds.
    private val input = acquireStream(BluetoothRfcommStreamException.Operation.INPUT_STREAM, socket::inputStream)
    private val output = acquireStream(BluetoothRfcommStreamException.Operation.OUTPUT_STREAM, socket::outputStream)

    private val reader = Thread(::readLoop, "xcertplay-bluetooth-rfcomm-reader").apply {
        isDaemon = true
    }

    init {
        report("Bluetooth RFCOMM streams ready input=true output=true")
        reader.start()
    }

    override fun send(data: ByteArray) {
        synchronized(sendLock) {
            synchronized(lock) {
                failure?.let { throw it }
                if (closed) throw IOException("Bluetooth RFCOMM stream is closed")
            }
            try {
                output.write(data)
                if (flushAllowed) flushOutput()
            } catch (error: Exception) {
                val io = BluetoothRfcommStreamException(
                    BluetoothRfcommStreamException.Operation.WRITE,
                    BluetoothRfcommStreamException.Reason.WRITE_FAILED,
                    error,
                )
                throw fail(io)
            }
        }
    }

    /**
     * Android's socket flush polls the TIOCOUTQ ioctl. A Ford SYNC+ (i.MX8, Android 8.1) refuses
     * that ioctl with EACCES although the write itself succeeded (#582). The bytes are already
     * queued, so this socket stops flushing instead of failing the link.
     */
    private fun flushOutput() {
        try {
            output.flush()
        } catch (error: IOException) {
            if (!refusedIoctl(error)) throw error
            flushAllowed = false
            report("Bluetooth RFCOMM flush refused by the head unit; sending without flush")
        }
    }

    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }

        val deadlineNanos = deadlineAfter(timeoutMillis)
        synchronized(lock) {
            while (true) {
                takePendingLocked(maxBytes)?.let { return it }
                failure?.let { throw it }
                if (peerEnded || closed) return EMPTY

                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    lock.wait(
                        remainingNanos / NANOS_PER_MILLISECOND,
                        (remainingNanos % NANOS_PER_MILLISECOND).toInt(),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }

    /** Closes the owned socket and waits briefly for the reader to leave its blocking read. */
    override fun close() {
        val firstClose = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                lock.notifyAll()
                true
            }
        }
        if (!firstClose) return

        var closeFailure = closeSocketOnce()
        if (Thread.currentThread() !== reader) {
            try {
                reader.join(CLOSE_JOIN_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                closeFailure = combine(
                    closeFailure,
                    IOException("Interrupted while closing the Bluetooth RFCOMM reader", interrupted),
                )
            }
            if (reader.isAlive) {
                closeFailure = combine(
                    closeFailure,
                    IOException("Bluetooth RFCOMM reader did not stop after close"),
                )
            }
        }
        if (closeFailure != null) throw closeFailure
    }

    private fun readLoop() {
        var readFailure: IOException? = null
        try {
            while (true) {
                val readSize = synchronized(lock) {
                    while (!closed && failure == null && pendingBytes >= MAX_PENDING_BYTES) {
                        lock.wait()
                    }
                    if (closed || failure != null) return
                    min(READ_CHUNK_BYTES, MAX_PENDING_BYTES - pendingBytes)
                }

                val buffer = ByteArray(readSize)
                readCalls++
                when (val count = input.read(buffer)) {
                    -1 -> {
                        if (!isStopping()) reportReadTerminal("ENDED")
                        synchronized(lock) {
                            peerEnded = true
                            lock.notifyAll()
                        }
                        return
                    }

                    0 -> Unit
                    else -> synchronized(lock) {
                        if (closed) return
                        receivedBytes += count
                        pending.addLast(if (count == buffer.size) buffer else buffer.copyOf(count))
                        pendingBytes += count
                        lock.notifyAll()
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            if (!isStopping()) {
                readFailure = IOException("Bluetooth RFCOMM reader was interrupted", interrupted)
            }
        } catch (io: IOException) {
            if (!isStopping()) readFailure = BluetoothRfcommStreamException(
                BluetoothRfcommStreamException.Operation.READ,
                BluetoothRfcommStreamException.Reason.READ_FAILED,
                io,
            )
        } catch (failure: Throwable) {
            if (!isStopping()) readFailure = BluetoothRfcommStreamException(
                BluetoothRfcommStreamException.Operation.READ,
                BluetoothRfcommStreamException.Reason.READ_FAILED,
                failure,
            )
            if (failure is Error) throw failure
        } finally {
            readFailure?.let {
                reportReadTerminal("FAILED")
                fail(it)
            }
            val closeFailure = closeSocketOnce()
            if (readFailure == null && closeFailure != null && !isClosed() && !endedCleanly()) {
                fail(closeFailure)
            }
        }
    }

    private fun takePendingLocked(maxBytes: Int): ByteArray? {
        val chunk = pending.pollFirst() ?: return null
        pendingBytes -= chunk.size
        if (chunk.size <= maxBytes) {
            lock.notifyAll()
            return chunk
        }

        val head = chunk.copyOf(maxBytes)
        val tail = chunk.copyOfRange(maxBytes, chunk.size)
        pending.addFirst(tail)
        pendingBytes += tail.size
        lock.notifyAll()
        return head
    }

    private fun fail(io: IOException): IOException {
        synchronized(lock) {
            failure?.let { return it }
            // Closing a socket intentionally unblocks both read and write/flush. Such a write
            // exception during bootstrap handoff is cancellation, not a new transport failure.
            if (closed) return IOException("Bluetooth RFCOMM stream is closed")
            failure = io
            lock.notifyAll()
        }
        reportFailure(io)
        closeSocketOnce()?.let { if (it !== io) io.addSuppressed(it) }
        return io
    }

    private fun <T : Any> acquireStream(
        operation: BluetoothRfcommStreamException.Operation,
        acquire: () -> T?,
    ): T {
        try {
            return acquire() ?: throw BluetoothRfcommStreamException(
                operation, BluetoothRfcommStreamException.Reason.STREAM_UNAVAILABLE,
            )
        } catch (error: Throwable) {
            val failure = if (error is BluetoothRfcommStreamException) error else
                BluetoothRfcommStreamException(operation, BluetoothRfcommStreamException.Reason.STREAM_ACCESS_FAILED, error)
            reportFailure(failure)
            closeSocketOnce()?.let(failure::addSuppressed)
            if (error is Error) throw error
            throw failure
        }
    }

    private fun reportFailure(error: IOException) {
        val streamFailure = error as? BluetoothRfcommStreamException
        report("Bluetooth RFCOMM stream result=FAILED operation=${streamFailure?.operation ?: "UNKNOWN"} " +
            "reason=${streamFailure?.reason ?: "UNKNOWN"} failureClass=${error.javaClass.simpleName} " +
            "causeClass=${error.cause?.javaClass?.simpleName ?: "none"} " +
            "nestedCauseClass=${error.cause?.cause?.javaClass?.simpleName ?: "none"}")
    }

    private fun reportReadTerminal(result: String) {
        report("Bluetooth RFCOMM reader result=$result beforeFirstByte=${receivedBytes == 0L} " +
            "readCalls=$readCalls receivedBytes=$receivedBytes")
    }

    private fun report(message: String) {
        try { onDiagnostic(message) } catch (_: Exception) {
            // A diagnostic callback cannot prevent transport setup or owned-socket cleanup.
        }
    }

    private fun closeSocketOnce(): IOException? {
        synchronized(lock) {
            if (socketCloseStarted) return null
            socketCloseStarted = true
        }
        return try {
            socket.close()
            null
        } catch (failure: Throwable) {
            if (failure is Error) throw failure
            IOException("Could not close the Bluetooth RFCOMM socket", failure)
        }
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun isStopping(): Boolean = synchronized(lock) { closed || failure != null }

    private fun endedCleanly(): Boolean = synchronized(lock) { closed || peerEnded }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun combine(first: IOException?, second: IOException): IOException {
        if (first == null) return second
        if (first !== second) first.addSuppressed(second)
        return first
    }

    private companion object {
        private const val READ_CHUNK_BYTES = 8_192
        private const val MAX_PENDING_BYTES = 65_536
        private const val CLOSE_JOIN_MILLIS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val EMPTY = ByteArray(0)
    }
}

private val PERMISSION_ERRNO = Regex("\\bE(ACCES|PERM)\\b")

/** A permission refusal as android.system.ErrnoException words it: "ioctl failed: EACCES (…)". */
internal fun refusedIoctl(error: Throwable): Boolean =
    generateSequence(error) { it.cause }.take(8).any { it.message?.contains(PERMISSION_ERRNO) == true }
