package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ManualHotspotProvisioningTest {
    private val ssidAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val passphraseAlphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"

    @Test
    fun ssidFollowsTheDiplayXXXXShape() {
        val generated = ManualHotspotProvisioning.generate(Random(1))
        assertTrue(generated.ssid.startsWith("DiPlay-"))
        assertEquals("DiPlay-XXXX".length, generated.ssid.length)
        assertTrue(generated.ssid.removePrefix("DiPlay-").all { it in ssidAlphabet })
    }

    @Test
    fun passphraseIsStrongAndWithinTheValidationBounds() {
        val generated = ManualHotspotProvisioning.generate(Random(2))
        assertEquals(16, generated.passphrase.length)
        assertTrue(generated.passphrase.all { it in passphraseAlphabet })
        assertNull(ManualHotspotValidation.error(generated.ssid, generated.passphrase))
    }

    @Test
    fun differentSeedsGiveDifferentCredentials() {
        val first = ManualHotspotProvisioning.generate(Random(3))
        val second = ManualHotspotProvisioning.generate(Random(4))
        assertNotEquals(first.ssid, second.ssid)
        assertNotEquals(first.passphrase, second.passphrase)
    }

    @Test
    fun generateIsDeterministicForOneSeed() {
        assertEquals(ManualHotspotProvisioning.generate(Random(5)), ManualHotspotProvisioning.generate(Random(5)))
    }
}
