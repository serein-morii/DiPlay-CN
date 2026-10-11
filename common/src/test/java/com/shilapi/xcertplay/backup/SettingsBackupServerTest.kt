package com.shilapi.xcertplay.backup

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsBackupServerTest {
    private lateinit var server: SettingsBackupServer
    private lateinit var endpoint: SettingsBackupEndpoint
    private val imported = AtomicReference<SettingsImportOutcome?>()

    @Before
    fun startServer() {
        server = SettingsBackupServer(
            page = "<html>backup-page</html>".toByteArray(),
            export = { "{\"exported\":true}".toByteArray() },
            import = { document ->
                val outcome = SettingsImportOutcome(4, 1, invalid = false)
                imported.set(outcome)
                outcome
            },
        )
        val started = server.start()
        assertNotNull(started)
        endpoint = started!!
    }

    @After
    fun stopServer() {
        server.stop()
    }

    private fun get(path: String, method: String = "GET", body: ByteArray? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${endpoint.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.getOutputStream().use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status < 400) connection.inputStream else connection.errorStream
        val text = stream?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
        connection.disconnect()
        return status to text
    }

    @Test
    fun stopsByItselfWhenNobodyUsesThePage() {
        val idle = java.util.concurrent.CountDownLatch(1)
        val quiet = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
            import = { SettingsImportOutcome(0, 0, invalid = false) })
        quiet.idleTimeoutMillis = 200
        quiet.onIdle = { idle.countDown() }
        val started = quiet.start()!!
        try {
            assertTrue(idle.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runCatching { Socket("127.0.0.1", started.port).close() }.isFailure)
        } finally {
            quiet.stop()
        }
    }

    @Test
    fun servesThePageOnlyUnderTheTokenPath() {
        val (status, body) = get("/b/${endpoint.token}/")
        assertEquals(200, status)
        assertTrue(body.contains("backup-page"))
        assertEquals(404, get("/").first)
        assertEquals(404, get("/b/wrong-token/").first)
        assertEquals(404, get("/export").first)
    }

    @Test
    fun exportReturnsTheDocumentAsAnAttachment() {
        val (status, body) = get("/b/${endpoint.token}/export")
        assertEquals(200, status)
        assertEquals("{\"exported\":true}", body)
    }

    @Test
    fun importAppliesTheDocumentAndReportsTheOutcome() {
        val document = "{\"format\":\"diplay-settings\",\"version\":1,\"settings\":{}}"
        val (status, body) = get("/b/${endpoint.token}/import", method = "POST", body = document.toByteArray())
        assertEquals(200, status)
        assertTrue(body.contains("\"applied\":4"))
        assertTrue(body.contains("\"skipped\":1"))
        assertTrue(body.contains("\"invalid\":false"))
        assertEquals(4, imported.get()?.applied)
    }

    @Test
    fun unknownRoutesAreNotFound() {
        assertEquals(404, get("/b/${endpoint.token}/settings").first)
        assertEquals(404, get("/b/${endpoint.token}/export", method = "POST", body = ByteArray(0)).first)
    }

    @Test
    fun stopClosesTheListener() {
        server.stop()
        val failure = runCatching {
            Socket("127.0.0.1", endpoint.port).use { it.getOutputStream().write(1) }
        }.exceptionOrNull()
        assertNotNull(failure)
    }

    @Test
    fun rankPrefersHotspotThenP2pThenStationInterfaces() {
        assertEquals(1, SettingsBackupServer.rank("ap0", null))
        assertEquals(1, SettingsBackupServer.rank("swlan0", null))
        assertEquals(2, SettingsBackupServer.rank("p2p0", null))
        assertEquals(3, SettingsBackupServer.rank("wlan0", null))
        assertEquals(3, SettingsBackupServer.rank("wlan1", null))
        assertEquals(4, SettingsBackupServer.rank("rmnet_data0", null))
        assertEquals(4, SettingsBackupServer.rank("lo", null))
    }

    @Test
    fun rankPutsThePreferredModeInterfaceFirst() {
        assertEquals(0, SettingsBackupServer.rank("p2p0", "p2p"))
        assertEquals(0, SettingsBackupServer.rank("ap0", "ap"))
        assertEquals(0, SettingsBackupServer.rank("wlan0", "wlan"))
        assertEquals(2, SettingsBackupServer.rank("p2p0", "ap"))
        assertEquals(3, SettingsBackupServer.rank("wlan1", "p2p"))
        assertEquals(4, SettingsBackupServer.rank("rmnet_data0", "p2p"))
    }
}
