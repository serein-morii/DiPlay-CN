package com.shilapi.xcertplay.airplay

import java.net.ProtocolException

/**
 * RTSP/HTTP-style request framing for the CarPlay control channel.
 *
 * The control connection on TCP :7000 speaks a text request line plus headers plus an optional
 * body, using the same framing RTSP and HTTP share. Encryption, once pair-verify completes, wraps
 * this framing in a separate layer.
 */
object RtspMessage {
    data class Request(
        val method: String,
        val path: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    data class Response(
        val protocol: String? = null,
        val status: Int? = null,
        val statusText: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    data class Parsed(val messages: List<Request>, val rest: ByteArray)

    /** Real requests carry headers under 1 KiB; the cap leaves ample room. */
    private const val MAX_HEADER_BYTES = 16 * 1024

    /** Above the largest legitimate body, a corner-mask PNG plus its plist wrapper (about 576 KiB). */
    private const val MAX_BODY_BYTES = 1024 * 1024

    private val headerEnd = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
    private val statusText = mapOf(
        200 to "OK",
        400 to "Bad Request",
        404 to "Not Found",
        500 to "Internal Server Error",
    )

    /**
     * Parses as many complete messages as are buffered, retaining any partial remainder.
     *
     * @throws ProtocolException when a header or body exceeds its cap, so a peer cannot make the caller buffer without limit.
     */
    fun parseMessages(buffer: ByteArray): Parsed {
        val messages = ArrayList<Request>()
        var offset = 0
        while (offset < buffer.size) {
            val headerEndIndex = indexOf(buffer, headerEnd, offset)
            if (headerEndIndex == null || headerEndIndex - offset > MAX_HEADER_BYTES) {
                if (buffer.size - offset > MAX_HEADER_BYTES) throw ProtocolException("RTSP header over $MAX_HEADER_BYTES bytes")
                break
            }
            val headerText = String(buffer, offset, headerEndIndex - offset, Charsets.US_ASCII)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) {
                offset = headerEndIndex + headerEnd.size
                continue
            }
            val requestLine = lines.first().split(" ", limit = 3)
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val separator = line.indexOf(':')
                if (separator == -1) continue
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
            val bodyStart = headerEndIndex + headerEnd.size
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (contentLength > MAX_BODY_BYTES) throw ProtocolException("RTSP body of $contentLength bytes over $MAX_BODY_BYTES")
            val bodyEnd = bodyStart + contentLength
            if (bodyEnd > buffer.size) break
            messages.add(
                Request(
                    method = requestLine[0],
                    path = requestLine.getOrElse(1) { "" },
                    protocol = requestLine.getOrElse(2) { "RTSP/1.0" },
                    headers = headers,
                    body = buffer.copyOfRange(bodyStart, bodyEnd),
                ),
            )
            offset = bodyEnd
        }
        return Parsed(messages, buffer.copyOfRange(offset, buffer.size))
    }

    /** Builds a response, echoing the request protocol and CSeq. */
    fun buildResponse(request: Request, response: Response): ByteArray {
        val protocol = response.protocol ?: request.protocol
        val status = response.status ?: 200
        val statusLine = response.statusText ?: statusText[status] ?: "OK"
        val body = response.body
        val headers = LinkedHashMap<String, String>(response.headers)
        request.headers["cseq"]?.let { headers["CSeq"] = it }
        headers["Content-Length"] = body.size.toString()

        val head = buildString {
            append(protocol).append(' ').append(status).append(' ').append(statusLine).append("\r\n")
            for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
            append("\r\n")
        }
        return head.toByteArray(Charsets.US_ASCII) + body
    }

    private fun indexOf(source: ByteArray, target: ByteArray, start: Int): Int? {
        if (target.isEmpty()) return start
        val first = target[0]
        for (index in start..source.size - target.size) {
            if (source[index] == first && matches(source, index, target)) return index
        }
        return null
    }

    private fun matches(source: ByteArray, offset: Int, target: ByteArray): Boolean {
        for (index in target.indices) {
            if (source[offset + index] != target[index]) return false
        }
        return true
    }
}
