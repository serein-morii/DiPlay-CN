package com.shilapi.xcertplay.backup

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            report = { "DiPlay diagnostic report\n診斷".toByteArray(Charsets.UTF_8) },
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

    // The port is not proof on its own: the accept() the loop thread waits in can hold it for one poll
    // after the server lets go, and on a busy machine another listener may take a freed port. So ask
    // what the driver asks: does the page still come back?
    private fun pageStillAnswered(port: Int, token: String): Boolean = runCatching {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 1_000
            socket.getOutputStream().apply {
                write("GET /b/$token/ HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()?.contains("backup-page") == true

    @Test
    fun stopsByItselfWhenNobodyUsesThePage() {
        val idle = java.util.concurrent.CountDownLatch(1)
        val quiet = SettingsBackupServer(page = "<html>backup-page</html>".toByteArray(), export = { ByteArray(0) },
            import = { SettingsImportOutcome(0, 0, invalid = false) })
        quiet.idleTimeoutMillis = 200
        quiet.onIdle = { idle.countDown() }
        val started = quiet.start()!!
        try {
            assertTrue(idle.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("the page still answered after the idle stop", !pageStillAnswered(started.port, started.token))
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
    fun reportReturnsTheTextOnlyUnderTheTokenPath() {
        val (status, body) = get("/b/${endpoint.token}/report")
        assertEquals(200, status)
        assertEquals("DiPlay diagnostic report\n診斷", body)
        assertEquals(404, get("/report").first)
        assertEquals(404, get("/b/wrong-token/report").first)
        assertEquals(404, get("/b/${endpoint.token}/report", method = "POST", body = ByteArray(0)).first)
    }

    @Test
    fun reportIsADownloadableTextFile() {
        val connection = URL("http://127.0.0.1:${endpoint.port}/b/${endpoint.token}/report").openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            assertEquals("text/plain; charset=utf-8", connection.getHeaderField("Content-Type"))
            assertTrue(connection.getHeaderField("Content-Disposition").contains(SettingsBackupServer.REPORT_FILE))
            assertEquals("no-store", connection.getHeaderField("Cache-Control"))
        } finally {
            connection.disconnect()
        }
    }

    // Session logs make a report of a megabyte or more; all of it has to arrive.
    @Test
    fun aLargeReportArrivesWhole() {
        val line = "12:00:00.000  wireless bring-up generation=1 starting 診斷\n".toByteArray(Charsets.UTF_8)
        val big = ByteArray(line.size * 60_000).also { all -> repeat(60_000) { line.copyInto(all, it * line.size) } }
        val large = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
            import = { SettingsImportOutcome(0, 0, invalid = false) }, report = { big })
        val started = large.start()!!
        try {
            val connection = URL("http://127.0.0.1:${started.port}/b/${started.token}/report").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            assertEquals(200, connection.responseCode)
            assertEquals(big.size.toString(), connection.getHeaderField("Content-Length"))
            assertTrue(big.contentEquals(connection.inputStream.readBytes()))
            connection.disconnect()
        } finally {
            large.stop()
        }
    }

    @Test
    fun aReportThatCannotBeBuiltIsAnErrorNotAnEmptyDownload() {
        val failing = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
            import = { SettingsImportOutcome(0, 0, invalid = false) }, report = { error("logs unreadable") })
        val started = failing.start()!!
        try {
            val connection = URL("http://127.0.0.1:${started.port}/b/${started.token}/report").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            assertEquals(500, connection.responseCode)
            connection.disconnect()
        } finally {
            failing.stop()
        }
    }

    // A page opened by a build that has no report to give does not grow a dead link's worth of route.
    @Test
    fun aServerWithoutAReportHasNoReportRoute() {
        val plain = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
            import = { SettingsImportOutcome(0, 0, invalid = false) })
        val started = plain.start()!!
        try {
            val connection = URL("http://127.0.0.1:${started.port}/b/${started.token}/report").openConnection() as HttpURLConnection
            assertEquals(404, connection.responseCode)
            connection.disconnect()
        } finally {
            plain.stop()
        }
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

    private fun withTypedCode(test: (SettingsBackupServer, SettingsBackupEndpoint, (String) -> Pair<Int, String>) -> Unit) {
        val typed = SettingsBackupServer(page = "<html>backup-page</html>".toByteArray(), export = { ByteArray(0) },
            import = { SettingsImportOutcome(0, 0, invalid = false) }, codePage = "<html>code-page</html>".toByteArray())
        typed.typedPort = 0
        val started = typed.start()!!
        fun request(path: String, method: String, body: ByteArray?): Pair<Int, String> {
            val connection = URL("http://127.0.0.1:${started.port}$path").openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.getOutputStream().use { it.write(body) }
            }
            val status = connection.responseCode
            val text = (if (status < 400) connection.inputStream else connection.errorStream)?.readBytes()?.toString(Charsets.UTF_8).orEmpty()
            connection.disconnect()
            return status to text
        }
        try {
            test(typed, started) { code -> request("/code", "POST", code.toByteArray()) }
            // Whatever was typed, the link from the QR code still opens the page.
            assertEquals(200, request("/b/${started.token}/", "GET", null).first)
        } finally {
            typed.stop()
        }
    }

    private fun wrong(code: String) = ((code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')

    // An Arabic head unit formats numbers with Arabic-Indic digits; a computer's keyboard types 0 to 9.
    @Test
    fun theCodeIsMadeOfTheDigitsAKeyboardTypesInEveryLocale() {
        val before = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "ar-SA", "fa-IR", "hi-IN", "th-TH-u-nu-thai")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                withTypedCode { _, endpoint, enter ->
                    assertTrue("$tag gave ${endpoint.code}", endpoint.code.length == 6 && endpoint.code.all { it in '0'..'9' })
                    assertEquals(200, enter(endpoint.code).first)
                }
            }
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun theTypedAddressOpensThePageThatAsksForTheCode() = withTypedCode { _, endpoint, _ ->
        val connection = URL("http://127.0.0.1:${endpoint.port}/").openConnection() as HttpURLConnection
        assertEquals(200, connection.responseCode)
        assertTrue(connection.inputStream.readBytes().toString(Charsets.UTF_8).contains("code-page"))
        connection.disconnect()
        assertEquals("192.0.2.7:${endpoint.port}", endpoint.typedAddress("192.0.2.7"))
        assertTrue(endpoint.code.matches(Regex("[0-9]{6}")))
    }

    @Test
    fun theRightCodeLeadsToThePage() = withTypedCode { typed, endpoint, enter ->
        assertEquals(200 to "{\"path\":\"/b/${endpoint.token}/\"}", enter(endpoint.code))
        typed.landing = "#report"
        // Typed the way the head unit shows it, in two groups.
        val spaced = endpoint.code.chunked(3).joinToString(" ")
        assertEquals(200 to "{\"path\":\"/b/${endpoint.token}/#report\"}", enter(spaced))
        assertTrue(typed.codeEntryOpen)
    }

    @Test
    fun aWrongCodeSaysHowManyTriesAreLeft() = withTypedCode { typed, endpoint, enter ->
        assertEquals(403 to "{\"left\":4}", enter(wrong(endpoint.code)))
        assertEquals(403 to "{\"left\":3}", enter("12"))
        assertEquals(403 to "{\"left\":2}", enter(""))
        assertTrue(typed.codeEntryOpen)
        // A right code after wrong ones is still accepted while tries remain.
        assertEquals(200, enter(endpoint.code).first)
    }

    // Six digits can be guessed; five tries in a million cannot, in practice.
    @Test
    fun afterFiveWrongCodesNoCodeIsAcceptedNotEvenTheRightOne() = withTypedCode { typed, endpoint, enter ->
        val closed = java.util.concurrent.atomic.AtomicInteger()
        typed.onCodeEntryClosed = { closed.incrementAndGet() }
        repeat(4) { assertTrue(enter(wrong(endpoint.code)).second.contains("left")) }
        assertEquals(403 to "{\"locked\":true}", enter(wrong(endpoint.code)))
        assertEquals(403 to "{\"locked\":true}", enter(endpoint.code))
        assertEquals(403 to "{\"locked\":true}", enter(wrong(endpoint.code)))
        assertEquals(1, closed.get())
        assertTrue(!typed.codeEntryOpen)
    }

    @Test
    fun aNewStartGivesTheCodeItsTriesBack() {
        val typed = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
            import = { SettingsImportOutcome(0, 0, invalid = false) }, codePage = ByteArray(0))
        typed.typedPort = 0
        try {
            typed.start()!!
            assertTrue(typed.codeEntryOpen)
            typed.stop()
            assertNotNull(typed.start())
            assertTrue(typed.codeEntryOpen)
        } finally {
            typed.stop()
        }
    }

    private fun typedServer() = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
        import = { SettingsImportOutcome(0, 0, invalid = false) }, codePage = ByteArray(0))

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    // The same address every time: a browser remembers it, and only the code has to be typed again.
    @Test
    fun theTypedAddressKeepsItsPortFromOneOpeningToTheNext() {
        val wanted = freePort()
        val typed = typedServer().apply { typedPort = wanted }
        try {
            assertEquals(wanted, typed.start()!!.port)
            Socket("127.0.0.1", wanted).close()
            typed.stop()
            assertEquals(wanted, typed.start()!!.port)
        } finally {
            typed.stop()
        }
    }

    @Test
    fun aBusyPortDoesNotKeepThePageFromOpening() {
        java.net.ServerSocket(0).use { taken ->
            val typed = typedServer().apply { typedPort = taken.localPort }
            try {
                val started = typed.start()
                assertNotNull(started)
                assertTrue(started!!.port != taken.localPort)
                val connection = URL("http://127.0.0.1:${started.port}/").openConnection() as HttpURLConnection
                assertEquals(200, connection.responseCode)
                connection.disconnect()
            } finally {
                typed.stop()
            }
        }
    }

    @Test
    fun theFixedPortIsOneABrowserAndAnAppMayUse() {
        assertTrue(SettingsBackupServer.TYPED_PORT in 1024..9999)
    }

    // Without the page there is nothing to type a code into, and the address gives nothing away.
    @Test
    fun aServerWithoutACodePageHasNoTypedEntry() {
        assertEquals(404, get("/").first)
        assertEquals(404, get("/code", method = "POST", body = "000000".toByteArray()).first)
        assertEquals("", endpoint.code)
        assertTrue(!server.codeEntryOpen)
    }

    // Some browsers try HTTPS first on a typed address and fall back to HTTP only when that fails.
    @Test
    fun aTlsHelloIsClosedAtOnce() {
        Socket("127.0.0.1", endpoint.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply { write(byteArrayOf(0x16, 0x03, 0x01, 0x02, 0x00, 0x01)); flush() }
            val started = System.nanoTime()
            val end = runCatching { socket.getInputStream().read() }
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue(end.getOrNull() == -1 || end.exceptionOrNull() is java.net.SocketException)
            assertTrue("closed after $millis ms", millis < 2_000)
        }
    }

    // Browsers open spare connections before they need them and may never use them.
    @Test
    fun aConnectionThatSendsNothingDoesNotHoldUpTheNextRequest() {
        Socket("127.0.0.1", endpoint.port).use {
            Thread.sleep(200)
            val started = System.nanoTime()
            val (status, body) = get("/b/${endpoint.token}/")
            val millis = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, status)
            assertTrue(body.contains("backup-page"))
            assertTrue("the page took $millis ms behind an idle connection", millis < 2_000)
        }
    }

    @Test
    fun severalIdleConnectionsDoNotHoldUpTheNextRequestEither() {
        val idle = List(6) { Socket("127.0.0.1", endpoint.port) }
        try {
            Thread.sleep(200)
            assertEquals(200, get("/b/${endpoint.token}/export").first)
        } finally {
            idle.forEach { it.close() }
        }
    }

    @Test
    fun stopAlsoClosesAConnectionThatIsStillWaiting() {
        Socket("127.0.0.1", endpoint.port).use { waiting ->
            Thread.sleep(200)
            waiting.soTimeout = 3_000
            server.stop()
            // End of stream, or a reset: either way the head unit let go of it at once.
            val read = runCatching { waiting.getInputStream().read() }
            assertTrue(read.isFailure && read.exceptionOrNull() !is java.net.SocketTimeoutException || read.getOrNull() == -1)
        }
    }

    // Connections end on their own threads while stop() walks the ones still open.
    @Test
    fun stopDoesNotFailWhileConnectionsAreEnding() {
        repeat(1000) {
            val busy = SettingsBackupServer(page = ByteArray(0), export = { ByteArray(0) },
                import = { SettingsImportOutcome(0, 0, invalid = false) })
            val started = busy.start()!!
            val sockets = List(3) { Socket("127.0.0.1", started.port) }
            sockets.forEach { it.close() }
            busy.stop()
        }
    }

    // Without an answer the browser shows a connection that opened and then gave nothing.
    @Test
    fun aFailureWhilePreparingTheAnswerIsReportedToTheBrowser() {
        val failing = SettingsBackupServer(page = ByteArray(0), export = { error("storage unavailable") },
            import = { SettingsImportOutcome(0, 0, invalid = false) })
        val started = failing.start()!!
        try {
            val connection = URL("http://127.0.0.1:${started.port}/b/${started.token}/export").openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            assertEquals(500, connection.responseCode)
            connection.disconnect()
            // The page still answers afterwards.
            val page = URL("http://127.0.0.1:${started.port}/b/${started.token}/").openConnection() as HttpURLConnection
            assertEquals(200, page.responseCode)
            page.disconnect()
        } finally {
            failing.stop()
        }
    }

    @Test
    fun unknownRoutesAreNotFound() {
        assertEquals(404, get("/b/${endpoint.token}/settings").first)
        assertEquals(404, get("/b/${endpoint.token}/export", method = "POST", body = ByteArray(0)).first)
    }

    @Test
    fun stopClosesTheListener() {
        server.stop()
        assertTrue("the page still answered after stop", !pageStillAnswered(endpoint.port, endpoint.token))
    }

    // The listener can outlive close() until the accept loop wakes; once stop() returns it is gone, so the
    // next start() can take the same typed port again.
    @Test
    fun stopReturnsOnlyOnceTheAcceptLoopHasEnded() {
        val accepting = SettingsBackupServer::class.java.getDeclaredField("thread").apply { isAccessible = true }
            .get(server) as Thread
        server.stop()
        assertFalse(accepting.isAlive)
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
