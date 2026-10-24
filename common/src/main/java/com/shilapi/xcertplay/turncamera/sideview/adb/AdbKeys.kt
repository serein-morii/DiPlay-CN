package com.shilapi.xcertplay.turncamera.sideview.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** DiPlay's own ADB key: adbd remembers it after the driver approves it once. */
object AdbKeys {
    private const val DIR = "adb"
    private const val PRIVATE = "adbkey.private"
    private const val PUBLIC = "adbkey.public"
    private const val KEY_BITS = 2048
    private const val NAME = " diplay@headunit"

    // ADB signs the token as if it were a SHA-1 digest: the host prepends SHA-1's DigestInfo header.
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /** Loads the key from app-private storage, or makes one on first use. */
    @Synchronized
    fun load(context: Context): KeyPair {
        val dir = File(context.noBackupFilesDir, DIR)
        val privateFile = File(dir, PRIVATE)
        val publicFile = File(dir, PUBLIC)
        runCatching {
            val factory = KeyFactory.getInstance("RSA")
            return KeyPair(
                factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
                factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())),
            )
        }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        dir.mkdirs()
        privateFile.writeBytes(pair.private.encoded)
        publicFile.writeBytes(pair.public.encoded)
        return pair
    }

    fun sign(token: ByteArray, key: PrivateKey): ByteArray =
        Signature.getInstance("NONEwithRSA").run {
            initSign(key)
            update(SHA1_DIGEST_INFO + token)
            sign()
        }

    /**
     * The public key as adbd stores it (AOSP android_pubkey): modulus size in words, n0inv = -1/n mod
     * 2^32, the modulus and R^2 mod n (R = 2^2048) as little-endian numbers, then the exponent;
     * Base64, a name and a NUL.
     */
    fun publicKeyMessage(key: PublicKey): ByteArray {
        val rsa = key as RSAPublicKey
        val n = rsa.modulus
        val words = KEY_BITS / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(2 * KEY_BITS).mod(n)
        val blob = ByteBuffer.allocate(8 + 2 * KEY_BITS / 8 + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(words)
            putInt(n0inv.toInt())
            put(littleEndian(n, KEY_BITS / 8))
            put(littleEndian(rr, KEY_BITS / 8))
            putInt(rsa.publicExponent.toInt())
        }.array()
        return (Base64.encodeToString(blob, Base64.NO_WRAP) + NAME + "\u0000").toByteArray(Charsets.UTF_8)
    }

    private fun littleEndian(value: BigInteger, size: Int): ByteArray {
        val bigEndian = value.toByteArray().takeLast(size) // drops BigInteger's sign byte
        return ByteArray(size) { index -> bigEndian.getOrElse(bigEndian.size - 1 - index) { 0 } }
    }
}
