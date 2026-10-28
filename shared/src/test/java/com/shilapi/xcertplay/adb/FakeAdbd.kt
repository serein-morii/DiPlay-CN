package com.shilapi.xcertplay.adb

import java.net.ServerSocket
import java.security.KeyPair
import java.security.Signature
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** One-connection adbd for tests: token auth with [key], then answers each OPEN with [output]. */
internal class FakeAdbd(private val key: KeyPair, knowsKey: Boolean, approves: Boolean = false, output: String = "") {
    private val server = ServerSocket(0)
    val port = server.localPort
    val opened = CopyOnWriteArrayList<String>()
    @Volatile var offeredKey = false

    init {
        thread(isDaemon = true) {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                fun send(packet: AdbPacket) = out.write(packet.encode())
                AdbPacket.read(input) // CNXN
                val token = ByteArray(20) { it.toByte() }
                send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, token))
                val signature = AdbPacket.read(input)
                val valid = Signature.getInstance("NONEwithRSA").run {
                    initVerify(key.public)
                    update(byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14) + token)
                    verify(signature.payload)
                }
                if (!(valid && knowsKey)) {
                    send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, token))
                    val offer = runCatching { AdbPacket.read(input) }.getOrNull() ?: return@use
                    offeredKey = offer.arg0 == AdbPacket.AUTH_PUBLIC_KEY
                    if (!approves) return@use
                }
                send(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD, "device::\u0000".toByteArray()))
                while (true) {
                    val open = runCatching { AdbPacket.read(input) }.getOrNull() ?: break
                    if (open.command != AdbPacket.OPEN) continue
                    opened += String(open.payload, Charsets.UTF_8).trimEnd('\u0000')
                    send(AdbPacket(AdbPacket.OKAY, 77, open.arg0, ByteArray(0)))
                    send(AdbPacket(AdbPacket.WRTE, 77, open.arg0, output.toByteArray()))
                    AdbPacket.read(input) // OKAY
                    send(AdbPacket(AdbPacket.CLSE, 77, open.arg0, ByteArray(0)))
                    AdbPacket.read(input) // CLSE
                }
            }
            server.close()
        }
    }
}
