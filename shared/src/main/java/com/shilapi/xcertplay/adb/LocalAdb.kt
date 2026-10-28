package com.shilapi.xcertplay.adb

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A shell on the head unit's own adbd ("ADB over network", 127.0.0.1:5555), for the few commands an
 * ordinary app may not run, such as switching the BYD cluster's navigation mode. Speaks the plain
 * ADB protocol (AOSP adb/protocol.txt); the TLS variant used by Android 11+ wireless debugging is
 * not supported.
 *
 * adbd trusts a key once the driver approves it in the car's "Allow debugging?" dialog. Only
 * [connect] with `mayAsk = true` offers the key for approval. Background use never does, so the
 * dialog cannot appear while driving.
 */
class LocalAdb(
    private val key: KeyPair,
    private val host: String = "127.0.0.1",
    private val port: Int = 5555,
) : Closeable {
    enum class Access { READY, NOT_APPROVED, UNREACHABLE, UNSUPPORTED }

    @Volatile private var socket: Socket? = null
    private val cancelled = AtomicBoolean(false)
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var nextStreamId = 1

    @Synchronized
    fun connect(mayAsk: Boolean): Access {
        if (cancelled.get()) return Access.UNREACHABLE
        if (socket?.isClosed == false) return Access.READY
        return try {
            val address = InetSocketAddress(host, port)
            val opened = Socket().also { socket = it }.apply {
                if (cancelled.get()) throw IOException("ADB operation cancelled")
                connect(address, CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
                tcpNoDelay = true
            }
            socket = opened
            input = BufferedInputStream(opened.getInputStream())
            output = opened.getOutputStream()
            send(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD, "host::\u0000".toByteArray()))
            handshake(mayAsk).also { if (it != Access.READY) closeQuietly() }
        } catch (_: SocketTimeoutException) {
            closeQuietly()
            if (mayAsk) Access.NOT_APPROVED else Access.UNREACHABLE
        } catch (_: IOException) {
            closeQuietly()
            Access.UNREACHABLE
        }
    }

    /** Runs [command] in adbd's shell and returns its output, or null if the link failed. */
    fun shell(command: String): String? = shell(command, READ_TIMEOUT_MS)

    /** Same as [shell], with a larger bounded read window for a one-shot slow command. */
    @Synchronized
    fun shell(command: String, readTimeoutMillis: Int): String? = shell(command, readTimeoutMillis, null)

    /** Same as [shell], pushing [stdin] to the command right after the stream opens; no EOF is implied. */
    @Synchronized
    fun shell(command: String, readTimeoutMillis: Int, stdin: ByteArray?): String? {
        if (cancelled.get()) return null
        if (socket?.isClosed != false && connect(mayAsk = false) != Access.READY) return null
        return try {
            require(readTimeoutMillis in 1..MAX_COMMAND_TIMEOUT_MS)
            socket?.soTimeout = readTimeoutMillis
            val local = nextStreamId++
            send(AdbPacket(AdbPacket.OPEN, local, 0, "shell:$command\u0000".toByteArray()))
            var remote = 0
            var stdinSent = false
            var finished = false
            val text = StringBuilder()
            while (!finished) {
                val packet = receive()
                when {
                    packet.command == AdbPacket.OKAY && packet.arg1 == local -> {
                        remote = packet.arg0
                        if (stdin != null && !stdinSent) {
                            stdinSent = true
                            send(AdbPacket(AdbPacket.WRTE, local, remote, stdin))
                        }
                    }
                    packet.command == AdbPacket.WRTE && packet.arg1 == local -> {
                        text.append(String(packet.payload, Charsets.UTF_8))
                        send(AdbPacket(AdbPacket.OKAY, local, packet.arg0, ByteArray(0)))
                    }
                    packet.command == AdbPacket.CLSE && packet.arg1 == local -> {
                        if (remote != 0) send(AdbPacket(AdbPacket.CLSE, local, remote, ByteArray(0)))
                        finished = true
                    }
                    // A stream left over from an earlier, interrupted command: close it and move on.
                    packet.command == AdbPacket.WRTE ->
                        send(AdbPacket(AdbPacket.CLSE, packet.arg1, packet.arg0, ByteArray(0)))
                }
            }
            text.toString().trim().also { socket?.soTimeout = READ_TIMEOUT_MS }
        } catch (_: IOException) {
            closeQuietly()
            null
        }
    }

    /** Exclusive interactive stream. Use a dedicated LocalAdb instance; never mix with [shell]. */
    @Synchronized
    fun openShell(command: String): InteractiveShell? {
        if (cancelled.get()) return null
        if (socket?.isClosed != false && connect(mayAsk = false) != Access.READY) return null
        return try {
            val local = nextStreamId++
            send(AdbPacket(AdbPacket.OPEN, local, 0, "shell:$command\u0000".toByteArray()))
            val reply = receive()
            if (reply.command != AdbPacket.OKAY || reply.arg1 != local) throw IOException("interactive open rejected")
            InteractiveShell(local, reply.arg0, ::send, ::receive,
                { timeout -> socket?.soTimeout = timeout }, ::closeQuietly)
        } catch (_: IOException) {
            closeQuietly()
            null
        }
    }

    /** One request at a time, with strict stream ownership, byte/packet limits and a total deadline. */
    class InteractiveShell internal constructor(
        private val local: Int,
        private val remote: Int,
        private val sendPacket: (AdbPacket) -> Unit,
        private val readPacket: () -> AdbPacket,
        private val setTimeout: (Int) -> Unit,
        private val closeConnection: () -> Unit,
    ) : Closeable {
        @Volatile private var closed = false
        private companion object {
            val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "diplay-adb-stream-deadline").apply { isDaemon = true }
            }
        }
        @Synchronized
        fun exchangeBounded(request: String, timeoutMillis: Int = 5000): String? {
            if (closed || request.toByteArray(Charsets.UTF_8).size > 1024 || '\n' in request || '\r' in request) return null
            val boundedMillis = timeoutMillis.coerceIn(1, 10000)
            // Socket read timeout alone restarts per read; an independent deadline also bounds drips.
            val deadlineTask = deadlines.schedule({
                closed = true
                closeConnection()
            }, boundedMillis.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            return try {
                val deadline = System.nanoTime() + boundedMillis * 1_000_000L
                sendPacket(AdbPacket(AdbPacket.WRTE, local, remote, "$request\n".toByteArray(Charsets.UTF_8)))
                var acknowledged = false
                val response = InteractiveResponseBuffer()
                var packets = 0
                while (!acknowledged || !response.complete) {
                    if (++packets > 64) throw IOException("interactive packet limit")
                    val remaining = (deadline - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) throw SocketTimeoutException("interactive deadline")
                    setTimeout(remaining.toInt().coerceAtLeast(1))
                    val packet = readPacket()
                    if (packet.arg0 != remote || packet.arg1 != local) throw IOException("wrong interactive stream")
                    when (packet.command) {
                        AdbPacket.OKAY -> acknowledged = true
                        AdbPacket.WRTE -> {
                            sendPacket(AdbPacket(AdbPacket.OKAY, local, remote, ByteArray(0)))
                            response.accept(packet.payload)
                        }
                        AdbPacket.CLSE -> throw IOException("interactive stream ended")
                        else -> throw IOException("unexpected interactive packet")
                    }
                }
                response.text()
            } catch (_: IOException) {
                close()
                null
            } finally {
                deadlineTask.cancel(false)
            }
        }
        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            runCatching { sendPacket(AdbPacket(AdbPacket.CLSE, local, remote, ByteArray(0))) }
            closeConnection()
        }
    }

    @Synchronized
    override fun close() = closeQuietly()

    /** Retires this client and unblocks pending I/O without waiting for its synchronized operation. */
    fun cancelPendingOperations() {
        cancelled.set(true)
        runCatching { socket?.close() }
    }

    private fun handshake(mayAsk: Boolean): Access {
        var packet = receive()
        if (packet.command == AdbPacket.STLS) return Access.UNSUPPORTED
        if (packet.command == AdbPacket.CNXN) return Access.READY
        if (packet.command != AdbPacket.AUTH || packet.arg0 != AdbPacket.AUTH_TOKEN) return Access.UNREACHABLE
        send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, AdbKeys.sign(packet.payload, key.private)))
        packet = receive()
        if (packet.command == AdbPacket.CNXN) return Access.READY
        if (!mayAsk) return Access.NOT_APPROVED
        // adbd did not know the key: offer it, which opens the approval dialog on the car's screen.
        send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_PUBLIC_KEY, 0, AdbKeys.publicKeyMessage(key.public)))
        return awaitApproval()
    }

    private fun awaitApproval(): Access {
        val pendingInput = input ?: throw IOException("not connected")
        val deadline = System.nanoTime() + APPROVAL_TIMEOUT_MS * 1_000_000L
        socket?.soTimeout = APPROVAL_RECHECK_MS
        try {
            while (System.nanoTime() < deadline) {
                // 保留超时前收到的半包，静默检查后仍可继续解析原连接的响应。
                pendingInput.mark(AdbPacket.MAX_PAYLOAD + 24)
                try {
                    val packet = receive()
                    return if (packet.command == AdbPacket.CNXN) Access.READY else Access.NOT_APPROVED
                } catch (_: SocketTimeoutException) {
                    pendingInput.reset()
                    // 部分车机保存了授权密钥，却不唤醒原连接；只用已保存的密钥检查，不再弹窗。
                    val approved = LocalAdb(key, host, port).use { it.connect(mayAsk = false) == Access.READY }
                    if (approved) {
                        closeQuietly()
                        return connect(mayAsk = false)
                    }
                }
            }
            return Access.NOT_APPROVED
        } finally {
            socket?.soTimeout = READ_TIMEOUT_MS
        }
    }

    private fun send(packet: AdbPacket) {
        val out = output ?: throw IOException("not connected")
        out.write(packet.encode())
        out.flush()
    }

    private fun receive(): AdbPacket = AdbPacket.read(input ?: throw IOException("not connected"))

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        nextStreamId = 1
    }

    internal companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 5_000
        /** How long an asking connect waits for the driver to approve DiPlay on the car's screen. */
        const val APPROVAL_TIMEOUT_MS = 60_000
        const val APPROVAL_RECHECK_MS = 1_000
        const val MAX_COMMAND_TIMEOUT_MS = 30_000
    }
}

/** ADB payload boundaries need not coincide with UTF-8 characters or response lines. */
internal class InteractiveResponseBuffer {
    private val bytes = java.io.ByteArrayOutputStream()
    var complete = false
        private set
    fun accept(payload: ByteArray) {
        if (complete || bytes.size() + payload.size > 4096) throw IOException("interactive response limit")
        bytes.write(payload)
        val text = bytes.toString("UTF-8")
        if (text == "done\n" || text.endsWith("\ndone\n")) complete = true
    }
    fun text(): String = bytes.toString("UTF-8").removeSuffix("done\n").trimEnd('\n', '\r')
}
