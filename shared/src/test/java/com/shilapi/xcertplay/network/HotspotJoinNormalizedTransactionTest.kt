package com.shilapi.xcertplay.network

import android.net.wifi.SoftApConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [AtomicFileRenameTestShadow::class])
class HotspotJoinNormalizedTransactionTest {
    class NormalizingWifiService(var config: SoftApConfiguration) {
        var writes = 0
        var accepted = true
        var afterWrite: (() -> Unit)? = null
        fun getSoftApConfiguration() = config
        fun getWifiApEnabledState() = 11
        fun setSoftApConfiguration(value: SoftApConfiguration, caller: String): Boolean {
            check(caller == "com.android.shell")
            // Android 13 WifiApConfigStore normalizes this flag on every setter call.
            val copy = builder(value)
            copy.javaClass.getMethod("setUserConfiguration", Boolean::class.javaPrimitiveType).invoke(copy, true)
            config = copy.build()
            writes++
            afterWrite?.invoke()
            return accepted
        }
    }

    private class Fixture(directory: File, userConfigured: Boolean) {
        val original = config(userConfigured)
        val service = NormalizingWifiService(original)
        val port = HotspotJoinPlatform(service, 33, "synthetic-firmware") {
            HotspotJoinCapability.Snapshot(true, setOf(36, 44))
        }
        val journal = HotspotJoinJournal(directory, port.firmware(), port.codec)
        var cancelled = false
        fun transaction() = HotspotJoinTransaction(port, journal) { cancelled }
    }

    @Test fun checkRefusesNonUserConfigurationsBeforePreparingOrWriting() = fixture(false) { f ->
        assertEquals(HotspotJoinTransaction.Status.UNSUPPORTED, f.transaction().prepare().status)
        assertNull(f.journal.load())
        assertEquals(0, f.service.writes)
        assertEquals(f.original, f.port.read())
    }

    @Test fun applyRechecksTheGateForAnOlderPreparedNonUserSnapshot() = fixture(false) { f ->
        // Model a snapshot prepared by the earlier implementation before the gate existed.
        val copy = builder(f.original)
        copy.javaClass.getMethod("setUserConfiguration", Boolean::class.javaPrimitiveType).invoke(copy, true)
        val targetCopy = builder(f.port.repaired(copy.build()) as SoftApConfiguration)
        targetCopy.javaClass.getMethod("setUserConfiguration", Boolean::class.javaPrimitiveType).invoke(targetCopy, false)
        val record = HotspotJoinTransaction.Record<Any>("12345678-1234-1234-1234-123456789abc", f.port.firmware(), f.original,
            targetCopy.build(), HotspotJoinTransaction.Stage.PREPARED)
        f.journal.save(record)
        assertEquals(HotspotJoinTransaction.Status.UNSUPPORTED, f.transaction().apply(record.token).status)
        assertEquals(0, f.service.writes)
        assertEquals(f.original, f.port.read())
        assertEquals(HotspotJoinTransaction.Stage.PREPARED, f.journal.load()!!.stage)
    }

    @Test fun userConfigurationAppliesAndRestoresTheCompleteParcelAfterRecreation() = fixture(true) { f ->
        val token = f.transaction().prepare().token!!
        assertEquals(HotspotJoinTransaction.Status.APPLIED, f.transaction().apply(token).status)
        assertNotEquals(f.original, f.port.read())
        assertEquals(f.original, f.journal.load()!!.original)
        assertEquals(HotspotJoinTransaction.Status.RESTORED, f.transaction().restore(token).status)
        assertEquals(f.original, f.port.read())
        assertEquals(2, f.service.writes)
        assertEquals(HotspotJoinTransaction.Stage.RESTORED, f.journal.load()!!.stage)
    }

    @Test fun rejectedAndCancelledWritesRestoreTheFullOriginalWithTheNormalizingSetter() {
        for (cancel in listOf(false, true)) fixture(true) { f ->
            val token = f.transaction().prepare().token!!
            if (cancel) f.service.afterWrite = { f.cancelled = true } else f.service.accepted = false
            assertEquals(HotspotJoinTransaction.Status.FAILED_ROLLED_BACK, f.transaction().apply(token).status)
            assertEquals(f.original, f.port.read())
            assertEquals(2, f.service.writes)
            assertEquals(HotspotJoinTransaction.Stage.RESTORED, f.journal.load()!!.stage)
        }
    }

    @Test fun unexpectedNormalizationStillRetainsTheOriginalAndRefusesDrift() = fixture(true) { f ->
        val token = f.transaction().prepare().token!!
        f.service.afterWrite = {
            val copy = builder(f.service.config)
            copy.javaClass.getMethod("setHiddenSsid", Boolean::class.javaPrimitiveType).invoke(copy, false)
            f.service.config = copy.build()
        }
        assertEquals(HotspotJoinTransaction.Status.RECOVERY_REQUIRED, f.transaction().apply(token).status)
        assertEquals(HotspotJoinTransaction.Status.DRIFT, f.transaction().restore(token).status)
        assertEquals(1, f.service.writes)
        assertEquals(f.original, f.journal.load()!!.original)
        assertEquals(HotspotJoinTransaction.Stage.PENDING, f.journal.load()!!.stage)
    }

    private fun fixture(userConfigured: Boolean, block: (Fixture) -> Unit) {
        val directory = Files.createTempDirectory("hotspot-normalization-test").toFile()
        try { block(Fixture(directory, userConfigured)) } finally { directory.deleteRecursively() }
    }

    companion object {
        private fun builder(config: SoftApConfiguration): SoftApConfiguration.Builder =
            SoftApConfiguration.Builder::class.java.getConstructor(SoftApConfiguration::class.java).newInstance(config)
        private fun config(userConfigured: Boolean): SoftApConfiguration {
            val builder = SoftApConfiguration.Builder()
                .setPassphrase("synthetic-password", SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
            builder.javaClass.getMethod("setSsid", String::class.java).invoke(builder, "synthetic-network")
            builder.javaClass.getMethod("setBand", Int::class.javaPrimitiveType).invoke(builder, 2)
            builder.javaClass.getMethod("setHiddenSsid", Boolean::class.javaPrimitiveType).invoke(builder, true)
            builder.javaClass.getMethod("setMaxNumberOfClients", Int::class.javaPrimitiveType).invoke(builder, 3)
            builder.javaClass.getMethod("setAutoShutdownEnabled", Boolean::class.javaPrimitiveType).invoke(builder, false)
            builder.javaClass.getMethod("setUserConfiguration", Boolean::class.javaPrimitiveType).invoke(builder, userConfigured)
            val type = Class.forName("android.net.wifi.ScanResult\$InformationElement")
            val unrelated = type.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                ByteArray::class.java).newInstance(221, 0, byteArrayOf(0, 0x50, 0xf2.toByte(), 2, 1, 1))
            builder.javaClass.getMethod("setVendorElements", List::class.java).invoke(builder, listOf(unrelated))
            return builder.build()
        }
    }
}
