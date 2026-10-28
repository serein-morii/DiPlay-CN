package com.shilapi.xcertplay.transport

import android.util.Log
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * USBMUX version-2 host over an already claimed iPhone bulk pipe.
 *
 * This is deliberately limited to USBMUX framing and one minimal outbound TCP connection. It
 * does not parse lockdown messages, iAP2, TLS, NCM, AirPlay, or any media protocol. All calls
 * may block and must run away from Android's main thread.
 */
class Iap2UsbMuxHost private constructor(
    private val pipe: Iap2UsbSession,
    private val readTimeoutMillis: Long,
    private val onDiagnostic: (String) -> Unit,
) : Closeable {
    private val stateLock = Any()
    private val writeLock = Any()
    private val connections = mutableMapOf<Int, Iap2UsbMuxTcpConnection>()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var nextMuxSequence = 0
    private var nextMuxAcknowledgement = 0
    private var receivedFrames = 0
    private val loggedControlFrames = AtomicInteger()
    private val loggedLargeWrites = AtomicInteger()
    private var nextSourcePort = FIRST_SOURCE_PORT
    private lateinit var readerThread: Thread
    private val receiveFrames = UsbMuxFrameBuffer { line ->
        Log.w("xcertplay-usb", line)
        runCatching { onDiagnostic(line) }
    }

    /** Opens a TCP byte stream to the iPhone service on [destinationPort]. */
    fun connect(
        destinationPort: Int = LOCKDOWN_PORT,
        timeoutMillis: Long = CONNECT_TIMEOUT_MILLIS,
    ): Iap2UsbMuxTcpConnection {
        require(destinationPort in 1..0xffff) { "destinationPort must be a valid TCP port" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val connection = synchronized(stateLock) {
            checkOpenLocked()
            val sourcePort = allocateSourcePortLocked()
            Iap2UsbMuxTcpConnection(this, sourcePort, destinationPort).also {
                connections[sourcePort] = it
            }
        }
        try {
            connection.beginConnect()
            if (!connection.awaitConnected(timeoutMillis)) {
                connection.abort()
                throw IphoneUsbException.TimedOut("USBMUX TCP connection to port $destinationPort timed out")
            }
            return connection
        } catch (error: IphoneUsbException) {
            removeConnection(connection)
            throw error
        }
    }

    override fun close() {
        val activeConnections = synchronized(stateLock) {
            if (closed) return
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        activeConnections.forEach(Iap2UsbMuxTcpConnection::closeFromHost)
        pipe.close()
        if (::readerThread.isInitialized && Thread.currentThread() !== readerThread) {
            try {
                readerThread.join(readTimeoutMillis + CLOSE_JOIN_MARGIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    internal fun sendTcp(
        sourcePort: Int,
        destinationPort: Int,
        sequence: Int,
        acknowledgement: Int,
        flags: Int,
        payload: ByteArray,
    ) {
        if (payload.size > 512 && loggedLargeWrites.incrementAndGet() <= LOGGED_RX_FRAMES) Log.i("xcertplay-usb", "usbmux TX source=$sourcePort destination=$destinationPort bytes=${payload.size} seq=$sequence ack=$acknowledgement")
        val tcp = ByteArray(TCP_HEADER_BYTES + payload.size)
        putU16(tcp, 0, sourcePort)
        putU16(tcp, 2, destinationPort)
        putU32(tcp, 4, sequence)
        putU32(tcp, 8, acknowledgement)
        tcp[12] = (TCP_HEADER_BYTES / 4 shl 4).toByte()
        tcp[13] = flags.toByte()
        putU16(tcp, 14, TCP_WINDOW_FIELD)
        payload.copyInto(tcp, TCP_HEADER_BYTES)
        sendFrame(PROTOCOL_TCP, tcp)
    }

    internal fun removeConnection(connection: Iap2UsbMuxTcpConnection) {
        synchronized(stateLock) {
            if (connections[connection.sourcePort] === connection) {
                connections.remove(connection.sourcePort)
            }
        }
    }

    private fun begin() {
        val version = ByteArray(VERSION_MESSAGE_BYTES)
        putU32(version, 0, PROTOCOL_VERSION)
        putU32(version, 4, VERSION_MESSAGE_BYTES)
        putU32(version, 8, USBMUX_VERSION)
        pipe.write(version, HANDSHAKE_TIMEOUT_MILLIS.toInt())
        // The phone replies with the same proto=0, length=20, version=2 packet. Protocol 1 is not
        // a distinct "version reply" here; waiting for it discards the valid reply and times out.
        val deadline = System.nanoTime() + HANDSHAKE_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        var staleFrames = 0
        var reply: UsbMuxFrame
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                throw IphoneUsbException.TimedOut("Timed out waiting for the USBMUX version reply")
            }
            val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            reply = takeFrame(remainingMillis)
                ?: throw IphoneUsbException.TimedOut("Timed out waiting for the USBMUX version reply")
            if (
                reply.protocol == PROTOCOL_VERSION &&
                reply.length == VERSION_MESSAGE_BYTES &&
                reply.word8 == USBMUX_VERSION
            ) {
                break
            }
            // Android can leave already-received TCP payloads queued in the bulk endpoint when
            // an app process is replaced. They belong to the previous host instance and must not
            // be mistaken for the version reply sent in response to the new handshake.
            if (reply.protocol != PROTOCOL_TCP || ++staleFrames > MAX_STALE_HANDSHAKE_FRAMES) {
                throw IphoneUsbException.Protocol(
                    "Invalid USBMUX version reply: proto=${reply.protocol} " +
                        "length=${reply.length} version=${reply.word8}",
                )
            }
            Log.i("xcertplay-usb", "discarding stale usbmux TCP frame before version reply")
        }
        Log.i("xcertplay-usb", "usbmux version accepted: ${reply.word8}")
        // Optional reply padding is handled by the incremental framer. Retain a possible
        // fragmented/coalesced next frame instead of discarding the entire remainder.
        sendFrame(PROTOCOL_SETUP, byteArrayOf(SETUP_VALUE.toByte()))
        readerThread = Thread(::readerLoop, "iap2-usbmux-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Reads one complete USBMUX frame, keeping partial data buffered across reads. */
    private fun takeFrame(timeoutMillis: Long): UsbMuxFrame? {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        while (true) {
            synchronized(stateLock) {
                if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX host is closed")
                receiveFrames.takeFrame()?.let { frame ->
                    // LIVI only trusts the length field on receive: iPhone replies do not
                    // carry the 0xFEEDFACE word in the header's fourth field.
                    // One line per frame filled logcat within a minute of a wired session (#634).
                    if (++receivedFrames <= LOGGED_RX_FRAMES) Log.i(
                        "xcertplay-usb",
                        "usbmux rx proto=${frame.protocol} length=${frame.length} word8=0x" +
                            frame.word8.toUInt().toString(16),
                    )
                    nextMuxAcknowledgement = frame.sequence
                    return frame
                }
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return null
            val remainingMillis = (remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND
            val bytes = pipe.read(remainingMillis) ?: continue
            synchronized(stateLock) {
                receiveFrames.append(bytes)
            }
        }
    }

    private fun sendFrame(protocol: Int, payload: ByteArray) = synchronized(writeLock) {
        val sequenceAndAcknowledgement = synchronized(stateLock) {
            checkOpenLocked()
            nextMuxSequence to nextMuxAcknowledgement
        }
        val frame = ByteArray(MUX_HEADER_BYTES + payload.size)
        putU32(frame, 0, protocol)
        putU32(frame, 4, frame.size)
        putU32(frame, 8, MUX_MAGIC)
        putU16(frame, 12, sequenceAndAcknowledgement.first)
        putU16(frame, 14, sequenceAndAcknowledgement.second)
        payload.copyInto(frame, MUX_HEADER_BYTES)
        try {
            pipe.write(frame, WRITE_TIMEOUT_MILLIS)
        } catch (error: IphoneUsbException) {
            fail(error)
            throw error
        }
        synchronized(stateLock) {
            nextMuxSequence = (nextMuxSequence + 1) and 0xffff
        }
    }

    private fun readerLoop() {
        try {
            while (true) {
                synchronized(stateLock) {
                    if (closed) return
                }
                val frame = takeFrame(readTimeoutMillis) ?: continue
                if (frame.protocol == PROTOCOL_TCP) dispatchTcp(frame.payload)
            }
        } catch (error: IphoneUsbException) {
            fail(error)
        } catch (error: RuntimeException) {
            fail(IphoneUsbException.DeviceUnavailable("USBMUX reader failed", error))
        }
    }

    private fun dispatchTcp(frame: ByteArray) {
        val offset = 0
        val length = frame.size
        if (length < TCP_HEADER_BYTES) {
            throw IphoneUsbException.Protocol("USBMUX TCP frame is shorter than its header")
        }
        val tcpHeaderBytes = ((frame[offset + 12].toInt() ushr 4) and 0x0f) * 4
        if (tcpHeaderBytes < TCP_HEADER_BYTES || tcpHeaderBytes > length) {
            throw IphoneUsbException.Protocol("Invalid USBMUX TCP header length")
        }
        val destinationPort = readU16(frame, offset + 2)
        if (length == tcpHeaderBytes && (frame[13].toInt() and TCP_SYN_FIN_RST != 0 || loggedControlFrames.incrementAndGet() <= LOGGED_RX_FRAMES)) Log.i("xcertplay-usb", "usbmux TCP control destination=$destinationPort flags=${frame[13].toInt() and 0xff} ack=${readU32(frame, 8)} window=${readU16(frame, 14)}")
        val connection = synchronized(stateLock) { connections[destinationPort] } ?: return
        connection.onPacket(
            flags = frame[offset + 13].toInt() and 0xff,
            sequence = readU32(frame, offset + 4),
            payload = frame.copyOfRange(offset + tcpHeaderBytes, offset + length),
        )
    }

    private fun fail(error: IphoneUsbException) {
        val activeConnections = synchronized(stateLock) {
            if (closed) return
            failure = failure ?: error
            closed = true
            connections.values.toList().also { connections.clear() }
        }
        activeConnections.forEach { it.closeFromHost(error) }
        pipe.close()
    }

    private fun allocateSourcePortLocked(): Int {
        repeat(0xffff) {
            val candidate = nextSourcePort
            nextSourcePort = if (candidate == 0xffff) FIRST_SOURCE_PORT else candidate + 1
            if (candidate !in connections) return candidate
        }
        throw IphoneUsbException.DeviceUnavailable("USBMUX has no free TCP source ports")
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX host is closed")
    }

    companion object {
        const val LOCKDOWN_PORT = 62078
        private const val LOGGED_RX_FRAMES = 50

        private const val PROTOCOL_VERSION = 0
        private const val PROTOCOL_SETUP = 2
        private const val PROTOCOL_TCP = 6
        private const val USBMUX_VERSION = 2
        private const val SETUP_VALUE = 0x07
        private const val MUX_MAGIC = 0xfeedface.toInt()
        private const val VERSION_MESSAGE_BYTES = 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val MUX_HEADER_BYTES = 16
        private const val TCP_HEADER_BYTES = 20
        private const val TCP_SYN_FIN_RST = 0x07
        private const val TCP_WINDOW_FIELD = 512
        private const val FIRST_SOURCE_PORT = 1
        private const val HANDSHAKE_TIMEOUT_MILLIS = 60_000L
        private const val MAX_STALE_HANDSHAKE_FRAMES = 32
        private const val CONNECT_TIMEOUT_MILLIS = 5_000L
        private const val WRITE_TIMEOUT_MILLIS = 2_000
        private const val CLOSE_JOIN_MARGIN_MILLIS = 100L

        /** Performs the USBMUX v2 handshake and starts the framed reader. */
        fun open(
            pipe: Iap2UsbSession,
            readTimeoutMillis: Long = 1_000,
            onDiagnostic: (String) -> Unit = {},
        ): Iap2UsbMuxHost {
            require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
            return Iap2UsbMuxHost(pipe, readTimeoutMillis, onDiagnostic).also {
                try {
                    it.begin()
                } catch (error: Throwable) {
                    it.close()
                    throw error
                }
            }
        }

        private fun putU16(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 8).toByte()
            target[offset + 1] = value.toByte()
        }

        private fun putU32(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        private fun readU16(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

        private fun readU32(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 24) or
                ((source[offset + 1].toInt() and 0xff) shl 16) or
                ((source[offset + 2].toInt() and 0xff) shl 8) or
                (source[offset + 3].toInt() and 0xff)
    }
}

/** A blocking TCP byte stream carried by [Iap2UsbMuxHost]. */
class Iap2UsbMuxTcpConnection internal constructor(
    private val host: Iap2UsbMuxHost,
    internal val sourcePort: Int,
    private val destinationPort: Int,
) : BlockingDuplexByteStream {
    private val stateLock = Object()
    private val writeLock = Any()
    private val received = ArrayDeque<ByteArray>()
    private var nextSequence = 0
    private var nextAcknowledgement = 0
    private var connected = false
    private var closed = false
    private var failure: IphoneUsbException? = null

    /** Sends [data] as an ordered byte stream, split into USBMUX TCP payloads of at most 16 KiB. */
    override fun send(data: ByteArray) {
        synchronized(stateLock) { checkConnectedLocked() }
        var offset = 0
        while (offset < data.size) {
            val count = minOf(MAX_SEND_PAYLOAD_BYTES, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + count)
            synchronized(writeLock) {
                val sequenceAndAck = synchronized(stateLock) {
                    checkConnectedLocked()
                    nextSequence to nextAcknowledgement
                }
                host.sendTcp(
                    sourcePort,
                    destinationPort,
                    sequenceAndAck.first,
                    sequenceAndAck.second,
                    TCP_ACK,
                    chunk,
                )
                synchronized(stateLock) { nextSequence += count }
            }
            offset += count
        }
    }

    /**
     * Receives up to [maxBytes] from the byte stream. Returns null on timeout and an empty array
     * after FIN or local close; a peer RST is reported as a transport failure.
     */
    override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (received.isEmpty() && !closed) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                try {
                    stateLock.wait(remainingNanos / NANOS_PER_MILLISECOND, (remainingNanos % NANOS_PER_MILLISECOND).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while waiting for USBMUX TCP data")
                }
            }
            failure?.let { throw it }
            if (received.isEmpty()) {
                return ByteArray(0)
            }
            val packet = received.removeFirst()
            return if (packet.size <= maxBytes) {
                packet
            } else {
                received.addFirst(packet.copyOfRange(maxBytes, packet.size))
                packet.copyOf(maxBytes)
            }
        }
    }

    override fun close() {
        val shouldSendFin = synchronized(stateLock) {
            if (closed) return
            closed = true
            stateLock.notifyAll()
            connected
        }
        if (shouldSendFin) {
            try {
                synchronized(writeLock) {
                    val sequenceAndAck = synchronized(stateLock) { nextSequence to nextAcknowledgement }
                    host.sendTcp(
                        sourcePort,
                        destinationPort,
                        sequenceAndAck.first,
                        sequenceAndAck.second,
                        TCP_FIN or TCP_ACK,
                        ByteArray(0),
                    )
                }
            } catch (_: IphoneUsbException) {
                // The host may have already closed its USB pipe; the closed state remains final.
            }
        }
        host.removeConnection(this)
    }

    internal fun beginConnect() {
        sendControl(TCP_SYN)
    }

    internal fun awaitConnected(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
        synchronized(stateLock) {
            while (!connected && !closed) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return false
                try {
                    stateLock.wait(remainingNanos / NANOS_PER_MILLISECOND, (remainingNanos % NANOS_PER_MILLISECOND).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IphoneUsbException.DeviceUnavailable("Interrupted while connecting USBMUX TCP")
                }
            }
            failure?.let { throw it }
            return connected && !closed
        }
    }

    internal fun abort() {
        val shouldSendReset = synchronized(stateLock) {
            if (closed) return
            closed = true
            stateLock.notifyAll()
            true
        }
        if (shouldSendReset) {
            try {
                sendControl(TCP_RST or TCP_ACK)
            } catch (_: IphoneUsbException) {
                // A failed reset cannot make the local connection usable again.
            }
        }
        host.removeConnection(this)
    }

    internal fun closeFromHost(error: IphoneUsbException? = null) = synchronized(stateLock) {
        if (closed) return@synchronized
        failure = error ?: failure
        closed = true
        stateLock.notifyAll()
    }

    internal fun onPacket(flags: Int, sequence: Int, payload: ByteArray) {
        if ((flags and TCP_RST) != 0) {
            synchronized(stateLock) {
                failure = IphoneUsbException.DeviceUnavailable("USBMUX TCP connection was reset by the peer")
                closed = true
                stateLock.notifyAll()
            }
            host.removeConnection(this)
            return
        }
        if ((flags and TCP_SYN) != 0 && (flags and TCP_ACK) != 0) {
            synchronized(stateLock) {
                if (closed) return
                nextSequence += 1
                nextAcknowledgement = sequence + 1
            }
            sendControl(TCP_ACK)
            synchronized(stateLock) {
                if (!closed) {
                    connected = true
                    stateLock.notifyAll()
                }
            }
            return
        }
        if (payload.isNotEmpty()) {
            synchronized(stateLock) {
                if (closed) return
                nextAcknowledgement += payload.size
                received.addLast(payload)
                stateLock.notifyAll()
            }
            sendControl(TCP_ACK)
        }
        if ((flags and TCP_FIN) != 0) {
            synchronized(stateLock) {
                if (closed) return
                nextAcknowledgement += 1
            }
            sendControl(TCP_ACK)
            synchronized(stateLock) {
                closed = true
                stateLock.notifyAll()
            }
            host.removeConnection(this)
        }
    }

    private fun sendControl(flags: Int) = synchronized(writeLock) {
        val sequenceAndAck = synchronized(stateLock) { nextSequence to nextAcknowledgement }
        host.sendTcp(
            sourcePort,
            destinationPort,
            sequenceAndAck.first,
            sequenceAndAck.second,
            flags,
            ByteArray(0),
        )
    }

    private fun checkConnectedLocked() {
        failure?.let { throw it }
        if (!connected || closed) throw IphoneUsbException.DeviceUnavailable("USBMUX TCP connection is not open")
    }

    private companion object {
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_ACK = 0x10
        const val MAX_SEND_PAYLOAD_BYTES = 16 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
