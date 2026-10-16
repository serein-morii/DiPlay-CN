package com.shilapi.xcertplay.backup

import com.shilapi.xcertplay.airplay.RtspMessage
import org.junit.Assert.*
import org.junit.Test

/** Pure request-handler tests: preview/confirmation must not need a network or Android. */
class SettingsBackupPreviewTest {
    @Test fun confirmationIsRequiredBoundToExactBytesAndConsumedOnce() {
        var writes = 0
        var notifications = 0
        val server = SettingsBackupServer(ByteArray(0), { ByteArray(0) },
            { writes++; SettingsImportOutcome(2, 1, false) },
            preview = { SettingsImportOutcome(2, 1, false) })
        server.onImported = { notifications++ }
        val document = "document"
        assertEquals(409, request(server, "import", document).first)
        val preview = request(server, "preview", document)
        assertEquals(200, preview.first)
        assertEquals(0, writes)
        assertEquals(0, notifications)
        val confirmation = Regex("\"confirmation\":\"([a-f0-9]+)\"").find(preview.second)!!.groupValues[1]
        assertEquals(409, request(server, "import", "other document", confirmation).first)
        assertEquals(0, writes)
        assertEquals(200, request(server, "import", document, confirmation).first)
        assertEquals(1, writes)
        assertEquals(1, notifications)
        assertEquals(409, request(server, "import", document, confirmation).first)
        assertEquals(1, writes)
    }

    @Test fun invalidOrExpiredPreviewCannotCommitAndWrongTokenCannotPreview() {
        var now = 0L
        var writes = 0
        val server = SettingsBackupServer(ByteArray(0), { ByteArray(0) },
            { writes++; SettingsImportOutcome(1, 0, false) },
            preview = { SettingsImportOutcome(1, 0, it.isEmpty()) }, monotonicClock = { now })
        val result = request(server, "preview", "valid")
        val confirmation = Regex("\"confirmation\":\"([a-f0-9]+)\"").find(result.second)!!.groupValues[1]
        now = java.util.concurrent.TimeUnit.MINUTES.toNanos(5)
        assertEquals(409, request(server, "import", "valid", confirmation).first)
        assertTrue(request(server, "preview", "").second.contains("\"invalid\":true"))
        assertEquals(409, request(server, "import", "valid", confirmation).first)
        assertEquals(404, request(server, "preview", "valid", token = "wrong").first)
        assertEquals(0, writes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun request(server: SettingsBackupServer, route: String, body: String,
                        confirmation: String? = null, token: String = "test"): Pair<Int, String> {
        server.javaClass.getDeclaredField("token").apply { isAccessible = true }.set(server, "test")
        val request = RtspMessage.Request("POST", "/b/$token/$route", "HTTP/1.1",
            if (confirmation == null) emptyMap() else mapOf("x-diplay-preview" to confirmation), body.toByteArray())
        val response = server.javaClass.getDeclaredMethod("respond", RtspMessage.Request::class.java)
            .apply { isAccessible = true }.invoke(server, request) as Triple<Int, Map<String, String>, ByteArray>
        return response.first to response.third.toString(Charsets.UTF_8)
    }
}
