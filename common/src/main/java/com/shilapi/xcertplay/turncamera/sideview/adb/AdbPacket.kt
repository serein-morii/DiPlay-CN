package com.shilapi.xcertplay.turncamera.sideview.adb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One ADB message: a 24-byte little-endian header followed by its payload. */
class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER_BYTES + payload.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(command)
        putInt(arg0)
        putInt(arg1)
        putInt(payload.size)
        putInt(payload.sumOf { it.toInt() and 0xff }) // adbd before protocol 0x01000001 checks this sum
        putInt(command.inv())
        put(payload)
    }.array()

    companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257
        const val STLS = 0x534c5453

        const val VERSION = 0x01000000
        const val MAX_PAYLOAD = 256 * 1024
        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_PUBLIC_KEY = 3

        private const val HEADER_BYTES = 24

        fun read(input: InputStream): AdbPacket {
            val header = ByteBuffer.wrap(readFully(input, HEADER_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            header.int // payload sum
            if (header.int != command.inv() || length !in 0..MAX_PAYLOAD) throw IOException("bad ADB header")
            return AdbPacket(command, arg0, arg1, readFully(input, length))
        }

        private fun readFully(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                if (read < 0) throw EOFException("ADB stream ended")
                offset += read
            }
            return bytes
        }
    }
}
