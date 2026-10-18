package com.shilapi.xcertplay.orchestration

import java.security.SecureRandom
import java.util.Random

/**
 * Invents hotspot credentials for the seamless-connection mode: a DiPlay-XXXX name and a strong
 * password. The caller persists them so the car and the iPhone see the same values on every start.
 */
object ManualHotspotProvisioning {
    private const val SSID_PREFIX = "DiPlay-"
    private const val SSID_SUFFIX_LENGTH = 4
    private const val SSID_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val PASSPHRASE_LENGTH = 16
    private const val PASSPHRASE_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"

    data class Generated(val ssid: String, val passphrase: String)

    fun generate(random: Random = SecureRandom()): Generated = Generated(
        ssid = SSID_PREFIX + random.string(SSID_ALPHABET, SSID_SUFFIX_LENGTH),
        passphrase = random.string(PASSPHRASE_ALPHABET, PASSPHRASE_LENGTH),
    )

    private fun Random.string(alphabet: String, length: Int): String =
        (1..length).map { alphabet[nextInt(alphabet.length)] }.joinToString("")
}
