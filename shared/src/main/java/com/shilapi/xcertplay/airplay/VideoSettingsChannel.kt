package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "CarPlayVideo Settings App" data stream (type 130, [VideoInCar.SETTINGS_CHANNEL_UUID]) the iPhone
 * opens once video in car is declared; it tears the session down when this stream is refused. Framing
 * as the iAP tunnel: ChaCha20-Poly1305 frames with a 2-byte little-endian length, carrying packages
 * with a 32-byte header (size, 12-byte type such as "sync"/"rply", 4-byte command, 8-byte sequence
 * number) and a body. Each "sync" is answered with an empty "rply", as AirPlay data streams expect;
 * the bodies are not needed to play.
 */
class VideoSettingsChannel(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
    private val expectedPeer: InetAddress,
    private val log: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var readCounter = 0L
    private var writeCounter = 0L

    fun listen(bindAddress: InetAddress): Int {
        val bound = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }
        server = bound
        Thread({ accept(bound) }, "diplay-video-settings").apply { isDaemon = true }.start()
        return bound.localPort
    }

    private fun accept(bound: ServerSocket) {
        try {
            val accepted = bound.acceptFrom(expectedPeer) { log("video settings channel rejected sender outside the control session") }
            socket = accepted
            log("video settings channel connected")
            run(accepted)
        } catch (error: Exception) {
            if (!closed.get()) log("video settings channel ended: ${error.message}")
        } finally {
            close()
        }
    }

    private fun run(sock: Socket) {
        val input = sock.getInputStream()
        val output = sock.getOutputStream()
        val buffer = ByteArray(16 * 1024)
        var ciphertext = ByteArray(0)
        var plaintext = ByteArray(0)
        while (!closed.get()) {
            val read = input.read(buffer)
            if (read < 0) return
            ciphertext += buffer.copyOf(read)
            var offset = 0
            while (ciphertext.size - offset >= 2) {
                val length = (ciphertext[offset].toInt() and 0xff) or ((ciphertext[offset + 1].toInt() and 0xff) shl 8)
                val frame = 2 + length + TAG
                if (ciphertext.size - offset < frame) break
                val aad = ciphertext.copyOfRange(offset, offset + 2)
                plaintext += AirPlayCrypto.chachaOpen(
                    readKey, AirPlayCrypto.nonce64(readCounter++), ciphertext.copyOfRange(offset + 2, offset + frame), aad,
                )
                offset += frame
            }
            ciphertext = ciphertext.copyOfRange(offset, ciphertext.size)
            plaintext = handlePackages(plaintext, output)
        }
    }

    private fun handlePackages(buffer: ByteArray, output: OutputStream): ByteArray {
        var offset = 0
        while (buffer.size - offset >= HEADER) {
            val size = u32(buffer, offset)
            if (size < HEADER || size > MAX_PACKAGE) throw IllegalStateException("bad package size $size")
            if (buffer.size - offset < size) break
            if (ascii(buffer, offset + 4, 12) == "sync") reply(output, buffer.copyOfRange(offset + 20, offset + 28))
            offset += size
        }
        return buffer.copyOfRange(offset, buffer.size)
    }

    private fun reply(output: OutputStream, seq: ByteArray) {
        val header = ByteArray(HEADER)
        header[3] = HEADER.toByte()
        "rply".toByteArray(Charsets.US_ASCII).copyInto(header, 4)
        seq.copyInto(header, 20)
        val aad = byteArrayOf((header.size and 0xff).toByte(), (header.size shr 8).toByte())
        val sealed = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter++), header, aad)
        output.write(aad + sealed)
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        runCatching { server?.close() }
    }

    private fun u32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun ascii(b: ByteArray, o: Int, n: Int) =
        String(b.copyOfRange(o, o + n), Charsets.US_ASCII).trimEnd('\u0000')

    private companion object {
        const val HEADER = 32
        const val TAG = 16
        const val MAX_PACKAGE = 8 * 1024 * 1024
    }
}
