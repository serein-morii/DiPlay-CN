package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "bplist: too short" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        require(offsetSize in 1..8 && refSize in 1..8) { "bplist: bad offset or reference size" }
        val declaredObjects = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        val topObject = readBigEndianLong(bytes, (trailer + 16).toLong(), 8)
        val offsetTable = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)
        // The peer chooses these numbers. Each object is at least one byte of the data, so no count above its length is real.
        require(declaredObjects in 1..trailer.toLong() && topObject in 0 until declaredObjects) { "bplist: bad object count" }
        require(offsetTable in magic.size.toLong()..trailer.toLong() &&
            offsetTable + declaredObjects * offsetSize <= trailer) { "bplist: bad offset table" }
        val numObjects = declaredObjects.toInt()

        val offsets = LongArray(numObjects)
        for (index in offsets.indices) {
            val offset = readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
            require(offset in magic.size.toLong() until offsetTable) { "bplist: object offset out of range" }
            offsets[index] = offset
        }
        // Each object read in a plain tree uses at least one reference byte, so reads rarely exceed the size. The caps
        // keep the memory of the result bounded however large the body is.
        val allowance = bytes.size.toLong() * WORK_PER_BYTE + WORK_BASE
        val limits = Limits(minOf(allowance, MAX_READS), minOf(allowance, MAX_PAYLOAD_BYTES))
        return readObject(bytes, offsets, refSize, topObject.toInt(), 0, limits)
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    private fun readObject(bytes: ByteArray, offsets: LongArray, refSize: Int, index: Int, depth: Int, limits: Limits): Any? {
        require(depth <= MAX_DEPTH) { "bplist: nested too deep" }
        require(--limits.reads >= 0) { "bplist: expands to more objects than its size allows" }
        require(index in offsets.indices) { "bplist: reference out of range" }
        var position = offsets[index].toInt()
        val markerByte = bytes[position].toInt() and 0xff
        val type = markerByte ushr 4
        val nibble = markerByte and 0x0f
        position++

        fun readCount(): Int {
            if (nibble != 0x0f) return nibble
            val sizeMarker = bytes[position].toInt() and 0xff
            position++
            val intBytes = 1 shl (sizeMarker and 0x0f)
            require(intBytes <= 8) { "bplist: count wider than 8 bytes" }
            val count = readBigEndianLong(bytes, position.toLong(), intBytes)
            position += intBytes
            // A count is a length of what follows it, so it cannot exceed the data that is left.
            require(count in 0..(bytes.size - position).toLong()) { "bplist: count larger than the data" }
            return count.toInt()
        }

        return when (type) {
            0x0 -> when (nibble) {
                0x00 -> null
                0x08 -> false
                0x09 -> true
                else -> throw IllegalArgumentException("bplist: unsupported primitive 0x0$nibble")
            }
            0x1 -> {
                require(nibble <= 3) { "bplist: integer wider than 8 bytes" }
                val size = 1 shl nibble
                readBigEndianLong(bytes, position.toLong(), size)
            }
            0x2 -> {
                val size = 1 shl nibble
                when (size) {
                    4 -> Float.fromBits(readBigEndianLong(bytes, position.toLong(), size).toInt())
                    8 -> Double.fromBits(readBigEndianLong(bytes, position.toLong(), size))
                    else -> throw IllegalArgumentException("bplist: unsupported real size $size")
                }
            }
            // Date: 8-byte real, seconds since 2001-01-01 UTC.
            0x3 -> java.util.Date(
                Math.round((Double.fromBits(readBigEndianLong(bytes, position.toLong(), 8)) + APPLE_EPOCH_SECONDS) * 1000),
            )
            0x4 -> {
                val count = readCount()
                charge(limits, count.toLong())
                bytes.copyOfRange(position, position + count)
            }
            // UID (keyed archives): its integer value.
            0x8 -> {
                require(nibble <= 7) { "bplist: uid wider than 8 bytes" }
                readBigEndianLong(bytes, position.toLong(), nibble + 1)
            }
            0x5 -> {
                val count = readCount()
                charge(limits, count.toLong())
                String(bytes, position, count, Charsets.US_ASCII)
            }
            0x6 -> {
                val count = readCount()
                require(count.toLong() * 2 <= bytes.size - position) { "bplist: string longer than the data" }
                charge(limits, count.toLong() * 2)
                String(bytes, position, count * 2, Charsets.UTF_16BE)
            }
            0xa, 0xc -> {
                val count = readCount()
                require(count.toLong() * refSize <= bytes.size - position) { "bplist: array larger than the data" }
                // Not `count`: a plist that refers to itself would allocate that much at every level before the depth limit.
                val array = ArrayList<Any?>(minOf(count, INITIAL_CAPACITY))
                for (i in 0 until count) {
                    val reference = readReference(bytes, position + i.toLong() * refSize, refSize)
                    array.add(readObject(bytes, offsets, refSize, reference, depth + 1, limits))
                }
                array
            }
            0xd -> {
                val count = readCount()
                require(count.toLong() * 2 * refSize <= bytes.size - position) { "bplist: dictionary larger than the data" }
                val dict = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val keyReference = readReference(bytes, position + i.toLong() * refSize, refSize)
                    val valueReference = readReference(bytes, position + (count + i).toLong() * refSize, refSize)
                    val key = readObject(bytes, offsets, refSize, keyReference, depth + 1, limits)
                    require(key is String) { "bplist: dictionary key is not a string" }
                    dict[key] = readObject(bytes, offsets, refSize, valueReference, depth + 1, limits)
                }
                dict
            }
            else -> throw IllegalArgumentException("bplist: unsupported object type 0x${type.toString(16)}")
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readReference(bytes: ByteArray, offset: Long, size: Int): Int {
        val reference = readBigEndianLong(bytes, offset, size)
        require(reference in 0..Int.MAX_VALUE.toLong()) { "bplist: reference out of range" }
        return reference.toInt()
    }

    /** Payload that is copied on every read of an object costs as much of the budget as it is long. */
    private fun charge(limits: Limits, size: Long) {
        // A short string costs one read like any object. Equal keys are stored once, so dictionaries read them often.
        if (size <= SMALL_PAYLOAD_BYTES) return
        limits.payload -= size
        require(limits.payload >= 0) { "bplist: shared data is read more often than its size allows" }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        require(offset >= 0 && offset + size <= bytes.size) { "bplist: value runs past the data" }
        var value = 0L
        var base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private class Limits(var reads: Long, var payload: Long)

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    private const val APPLE_EPOCH_SECONDS = 978_307_200.0

    // Real CarPlay messages nest a few levels. A plist that refers to itself would otherwise recurse until the stack ends.
    private const val MAX_DEPTH = 32
    private const val WORK_PER_BYTE = 4L
    private const val WORK_BASE = 1024L
    // Real messages hold a few hundred objects; each read costs about 30 bytes of the result.
    private const val MAX_READS = 100_000L
    // The largest real payload is a 512 KB picture that is read once.
    private const val MAX_PAYLOAD_BYTES = 4L * 1024 * 1024
    private const val SMALL_PAYLOAD_BYTES = 64L
    private const val INITIAL_CAPACITY = 64
}
