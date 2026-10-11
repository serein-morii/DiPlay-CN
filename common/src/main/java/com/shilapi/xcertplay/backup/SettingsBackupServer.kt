package com.shilapi.xcertplay.backup

import com.shilapi.xcertplay.airplay.RtspMessage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

data class SettingsBackupEndpoint(val port: Int, val token: String) {
    fun url(address: String) = "http://$address:$port/b/$token/"
}

/** One-request-per-connection HTTP transport that serves the settings backup page. */
class SettingsBackupServer(
    private val page: ByteArray,
    private val export: () -> ByteArray,
    private val import: (ByteArray) -> SettingsImportOutcome,
) {
    private var server: ServerSocket? = null
    private var thread: Thread? = null
    private var token: String = ""
    @Volatile private var closed = true
    var onImported: ((SettingsImportOutcome) -> Unit)? = null
    /** Called on the server thread when nobody used the page for [idleTimeoutMillis]. */
    var onIdle: (() -> Unit)? = null
    var idleTimeoutMillis = IDLE_TIMEOUT_MILLIS

    fun start(): SettingsBackupEndpoint? {
        val bound = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                // Anyone on the car's network can reach it, so it does not wait for them forever.
                soTimeout = idleTimeoutMillis
                bind(InetSocketAddress(0), BACKLOG)
            }
        }.getOrNull() ?: return null
        val random = ByteArray(8)
        SecureRandom().nextBytes(random)
        token = random.joinToString("") { "%02x".format(it) }
        server = bound
        closed = false
        thread = Thread({ loop() }, "settings-backup").apply { isDaemon = true; start() }
        return SettingsBackupEndpoint(bound.localPort, token)
    }

    fun stop() {
        closed = true
        runCatching { server?.close() }
        server = null
        thread = null
    }

    private fun loop() {
        while (!closed) {
            val socket = try {
                server?.accept()
            } catch (_: SocketTimeoutException) {
                if (!closed) {
                    stop()
                    onIdle?.invoke()
                }
                null
            } catch (_: Exception) {
                null
            } ?: break
            handle(socket)
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            runCatching {
                it.soTimeout = 10_000
                it.tcpNoDelay = true
                val request = readRequest(it.getInputStream()) ?: return
                val (status, headers, body) = respond(request)
                it.getOutputStream().apply {
                    write(
                        RtspMessage.buildResponse(
                            request,
                            RtspMessage.Response(
                                protocol = "HTTP/1.1",
                                status = status,
                                headers = headers,
                                body = body,
                            ),
                        ),
                    )
                    flush()
                }
            }
        }
    }

    private fun readRequest(input: InputStream): RtspMessage.Request? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (buffer.size() <= MAX_REQUEST) {
            val read = try { input.read(chunk) } catch (_: SocketTimeoutException) { break } ?: break
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            val parsed = RtspMessage.parseMessages(buffer.toByteArray())
            if (parsed.messages.isNotEmpty()) return parsed.messages.first()
        }
        return null
    }

    private fun respond(request: RtspMessage.Request): Triple<Int, Map<String, String>, ByteArray> {
        val path = request.path.substringBefore('?')
        val prefix = "/b/$token"
        val authorized = path == prefix || path.startsWith("$prefix/")
        if (!authorized) return notFound()
        val route = path.removePrefix("$prefix/").trimEnd('/')
        return when {
            route.isEmpty() && request.method == "GET" ->
                Triple(200, mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store"), page)
            route == "export" && request.method == "GET" -> Triple(
                200,
                mapOf(
                    "Content-Type" to "application/json",
                    "Content-Disposition" to "attachment; filename=\"$EXPORT_FILE\"",
                    "Cache-Control" to "no-store",
                ),
                export(),
            )
            route == "import" && request.method == "POST" -> {
                val outcome = import(request.body)
                onImported?.invoke(outcome)
                Triple(
                    200,
                    mapOf("Content-Type" to "application/json", "Cache-Control" to "no-store"),
                    "{\"applied\":${outcome.applied},\"skipped\":${outcome.skipped},\"invalid\":${outcome.invalid}}"
                        .toByteArray(Charsets.UTF_8),
                )
            }
            else -> notFound()
        }
    }

    private fun notFound() = Triple(404, mapOf("Content-Type" to "text/plain", "Cache-Control" to "no-store"), "Not found".toByteArray(Charsets.UTF_8))

    companion object {
        private const val BACKLOG = 4
        const val IDLE_TIMEOUT_MILLIS = 10 * 60_000
        private const val MAX_REQUEST = 2 shl 20
        const val EXPORT_FILE = "diplay-settings.json"

        fun addressCandidates(preferredPrefix: String? = null): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { iface -> iface.inetAddresses.toList().asSequence().map { iface to it } }
                .filter { (_, address) ->
                    address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
                }
                .map { (iface, address) -> rank(iface.name, preferredPrefix) to (address as Inet4Address).hostAddress }
                .filter { it.second != null }
                .sortedBy { it.first }
                .map { it.second!! }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())

        internal fun rank(name: String, preferredPrefix: String?): Int = when {
            preferredPrefix != null && name.startsWith(preferredPrefix) -> 0
            name.startsWith("ap") || name.startsWith("swlan") -> 1
            name.startsWith("p2p") -> 2
            name.startsWith("wlan") -> 3
            else -> 4
        }

        fun tokenMatches(expected: String, provided: String): Boolean =
            MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))
    }
}
