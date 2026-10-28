package com.shilapi.xcertplay.airplay

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control channel decodes plists that a peer on the network sends before it is authenticated. A hostile
 * plist must fail with an Exception, which the control loop catches. An OutOfMemoryError or a
 * StackOverflowError is not caught there, so it ends the thread and the app.
 */
class BplistCodecHostileInputTest {
    private fun longBytes(value: Long) = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }

    /** A bplist built by hand: [objects] in order, one-byte references, the top object is object 0. */
    private fun plist(objects: List<ByteArray>, declaredObjects: Long = objects.size.toLong(), offsetSize: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("bplist00".toByteArray())
        val offsets = ArrayList<Int>()
        for (objectBytes in objects) {
            offsets.add(out.size())
            out.write(objectBytes)
        }
        val tableOffset = out.size()
        for (offset in offsets) out.write(longBytes(offset.toLong()), 8 - offsetSize, offsetSize)
        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = 1 // reference size
        longBytes(declaredObjects).copyInto(trailer, 8)
        longBytes(0).copyInto(trailer, 16)
        longBytes(tableOffset.toLong()).copyInto(trailer, 24)
        out.write(trailer)
        return out.toByteArray()
    }

    private val falseObject = byteArrayOf(0x08)

    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    @Test fun anObjectCountLargerThanThePlistCanHoldIsRejected() {
        val hostile = plist(listOf(falseObject), declaredObjects = 0x7fffffffL)
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(hostile) }
    }

    @Test fun aDictionaryThatContainsItselfIsRejected() {
        val dictionary = byteArrayOf(0xd1.toByte(), 1, 0) // one entry: key is object 1, value is object 0 (itself)
        val key = byteArrayOf(0x51, 'k'.code.toByte())
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(listOf(dictionary, key))) }
    }

    @Test fun anArrayCountLargerThanThePlistCanHoldIsRejected() {
        val array = byteArrayOf(0xaf.toByte(), 0x12, 0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte()) // 4-byte count
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(listOf(array))) }
    }

    @Test(timeout = 10_000) fun aSmallPlistThatExpandsExponentiallyIsRejected() {
        val levels = 30
        // Object i is an array of two references to object i + 1: 2^30 leaves from about 100 bytes.
        val objects = (0 until levels).map { byteArrayOf(0xa2.toByte(), (it + 1).toByte(), (it + 1).toByte()) } + falseObject
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(objects)) }
    }

    @Test(timeout = 20_000) fun aLargeBlobThatManyReferencesPointAtIsRejected() {
        // One 200 KB data object, and an array of 20,000 references to it. Every read of the blob copies it:
        // 4 GB from a plist of 220 KB.
        val blob = byteArrayOf(0x4f, 0x12) + intBytes(200_000) + ByteArray(200_000)
        val array = byteArrayOf(0xaf.toByte(), 0x12) + intBytes(20_000) + ByteArray(20_000) { 1 }
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(listOf(array, blob), offsetSize = 4)) }
    }

    @Test fun aWideIntegerThatTheDataCouldHoldIsRejected() {
        val wide = byteArrayOf(0x1f) + ByteArray(32_768) // an integer of 32,768 bytes, read once per reference
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(listOf(wide), offsetSize = 4)) }
    }

    @Test fun aWideUidIsRejected() {
        val wide = byteArrayOf(0x8f.toByte()) + ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(listOf(wide))) }
    }

    @Test(timeout = 30_000) fun aPlistThatSharesContainersIntoMillionsOfObjectsIsRejected() {
        // 250,000 references to one array of 8 references to one empty array: 2.25 million objects from 250 KB.
        val outer = byteArrayOf(0xaf.toByte(), 0x12) + intBytes(250_000) + ByteArray(250_000) { 1 }
        val middle = byteArrayOf(0xa8.toByte()) + ByteArray(8) { 2 }
        val inner = byteArrayOf(0xa0.toByte())
        val bytes = plist(listOf(outer, middle, inner), offsetSize = 4)
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
    }

    @Test(timeout = 60_000) fun anArrayThatContainsItselfDoesNotAllocateItsFullCountAtEveryLevel() {
        // 16 million references to object 0, the array itself. Each of the 33 levels before the depth limit would
        // allocate room for 16 million references: 2 GB from a 16 MB plist.
        val count = 16_000_000
        val array = byteArrayOf(0xaf.toByte(), 0x12) + intBytes(count) + ByteArray(count)
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(listOf(array), offsetSize = 4)) }
    }

    @Test fun moreThanAHundredThousandObjectReadsAreRejected() {
        // 250,000 references to one empty array: 250,001 object reads from 250 KB.
        val outer = byteArrayOf(0xaf.toByte(), 0x12) + intBytes(250_000) + ByteArray(250_000) { 1 }
        val inner = byteArrayOf(0xa0.toByte())
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(listOf(outer, inner), offsetSize = 4)) }
    }

    @Test fun aWriterThatSharesTheKeysOfManyDictionariesStillDecodes() {
        // 100 dictionaries with the same 5 twenty-character keys: the writer stores each key once.
        val dictionaries = 100
        val keyCount = 5
        val array = byteArrayOf(0xaf.toByte(), 0x12) + intBytes(dictionaries) + ByteArray(dictionaries) { (it + 1).toByte() }
        val dictionary = byteArrayOf((0xd0 or keyCount).toByte()) +
            ByteArray(keyCount) { (dictionaries + 1 + it).toByte() } + ByteArray(keyCount) { (dictionaries + 1 + keyCount).toByte() }
        val keys = (0 until keyCount).map { index -> byteArrayOf(0x5f, 0x10, 20) + "key-number-$index-padxxx".take(20).toByteArray() }
        val value = byteArrayOf(0x10, 7)
        val objects = listOf(array) + List(dictionaries) { dictionary } + keys + value
        val decoded = BplistCodec.decode(plist(objects, offsetSize = 2)) as List<*>
        assertEquals(dictionaries, decoded.size)
        assertEquals(keyCount, (decoded[0] as Map<*, *>).size)
    }

    @Test fun aLargePictureInsideADictionaryStillDecodes() {
        val picture = byteArrayOf(0x4f, 0x12) + intBytes(500_000) + ByteArray(500_000) { 7 }
        val dictionary = byteArrayOf(0xd1.toByte(), 1, 2)
        val key = byteArrayOf(0x51, 'p'.code.toByte())
        val decoded = BplistCodec.decode(plist(listOf(dictionary, key, picture), offsetSize = 4)) as Map<*, *>
        assertEquals(500_000, (decoded["p"] as ByteArray).size)
    }

    @Test fun aDictionaryKeyThatIsNotAStringIsRejected() {
        val dictionary = byteArrayOf(0xd1.toByte(), 1, 2) // the key is object 1, an empty array
        val emptyArray = byteArrayOf(0xa0.toByte())
        assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.decode(plist(listOf(dictionary, emptyArray, falseObject)))
        }
    }

    @Test fun anIntegerThatRunsPastTheDataIsRejectedWithAnIllegalArgument() {
        val tooWide = byteArrayOf(0x1f) // an integer of 32,768 bytes
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(listOf(tooWide))) }
    }

    @Test fun aPlistWithRepeatedReferencesToOneStringStillDecodes() {
        // The writer stores equal strings once: both keys' values point at the same string object.
        val dictionary = byteArrayOf(0xd2.toByte(), 1, 2, 3, 3) // keys 1 and 2, both values object 3
        val first = byteArrayOf(0x51, 'a'.code.toByte())
        val second = byteArrayOf(0x51, 'b'.code.toByte())
        val shared = byteArrayOf(0x52, 'o'.code.toByte(), 'k'.code.toByte())
        val decoded = BplistCodec.decode(plist(listOf(dictionary, first, second, shared))) as Map<*, *>
        assertEquals(mapOf("a" to "ok", "b" to "ok"), decoded)
    }

    @Test fun anEncodedNestedPlistRoundTrips() {
        val value = mapOf(
            "streams" to listOf(mapOf("type" to 110L, "audioType" to "default", "data" to byteArrayOf(1, 2, 3))),
            "name" to "DiPlay",
            "flag" to true,
        )
        val decoded = BplistCodec.decode(BplistCodec.encode(value)) as Map<*, *>
        assertEquals("DiPlay", decoded["name"])
        assertEquals(true, decoded["flag"])
        val stream = (decoded["streams"] as List<*>).single() as Map<*, *>
        assertEquals(110L, stream["type"])
        assertTrue((stream["data"] as ByteArray).contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test fun twentyNestedArraysStillDecode() {
        var nested: Any? = true
        repeat(20) { nested = listOf(nested) }
        var decoded: Any? = BplistCodec.decode(BplistCodec.encode(nested))
        repeat(20) { decoded = (decoded as List<*>).single() }
        assertEquals(true, decoded)
    }
}
