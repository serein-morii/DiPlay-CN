package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.compat.Base64Compat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages

/** Blocking HTTP implementation of the MFI certificate and challenge-signing operations. */
class RemoteMfiAuthenticationClient(
    serverAddress: String,
    token: String? = null,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maximumAttempts: Int = DEFAULT_MAXIMUM_ATTEMPTS,
) : MfiAuthenticator {
    private data class CertificateInfo(
        val type: MfiCertificateType,
        val protocolMajor: Int,
        val wireCertificate: ByteArray,
        val iap2Certificate: ByteArray,
        val baaCertificates: BaaCertificatePair?,
    )

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
    )

    private val baseAddress = serverAddress.trim().trimEnd('/')
    private val bearerToken = token?.takeIf { it.isNotEmpty() }
    private val operationLock = Any()
    private var certificateInfo: CertificateInfo? = null

    init {
        require(baseAddress.isNotEmpty()) { "Remote MFI server address must not be blank" }
        require(baseAddress.startsWith("http://") || baseAddress.startsWith("https://")) {
            "Remote MFI server address must use http:// or https://"
        }
        require(baseAddress.startsWith("https://") || isPrivateNetworkHost(hostOf(baseAddress))) {
            "Remote MFI server address must use https:// unless the host is on a private network"
        }
        require(connectTimeoutMillis > 0) { "connectTimeoutMillis must be positive" }
        require(readTimeoutMillis > 0) { "readTimeoutMillis must be positive" }
        require(maximumAttempts > 0) { "maximumAttempts must be positive" }
    }

    /** Resets the remote authentication session and invalidates the cached certificate response. */
    fun reset(): Unit = synchronized(operationLock) {
        certificateInfo = null
        request(
            method = "POST",
            path = RESET_PATH,
            requestBody = "{}",
            retry = false,
        )
        Unit
    }

    override fun protocolMajor(): Int = synchronized(operationLock) {
        loadCertificateInfo().protocolMajor
    }

    override val certificateType: MfiCertificateType
        get() = synchronized(operationLock) {
            loadCertificateInfo().type
        }

    override fun readCertificate(maximumOutputLength: Int): ByteArray = synchronized(operationLock) {
        require(maximumOutputLength in 1..MAXIMUM_RESPONSE_BYTES) {
            "maximumOutputLength must be in 1..$MAXIMUM_RESPONSE_BYTES"
        }
        val certificate = loadCertificateInfo().iap2Certificate
        if (certificate.size !in 1..maximumOutputLength) {
            throw MfiInvalidDataException(
                "Remote certificate length ${certificate.size} is outside 1..$maximumOutputLength",
            )
        }
        certificate.copyOf()
    }

    override fun baaCertificates(): BaaCertificatePair = synchronized(operationLock) {
        loadCertificateInfo().baaCertificates
            ?: throw MfiInvalidDataException("Remote MFI service is not using BAA certificates")
    }

    override fun signChallenge(challenge: ByteArray): ByteArray = synchronized(operationLock) {
        if (challenge.size !in MINIMUM_CHALLENGE_BYTES..MAXIMUM_CHALLENGE_BYTES) {
            throw MfiInvalidDataException(
                "challenge must be $MINIMUM_CHALLENGE_BYTES..$MAXIMUM_CHALLENGE_BYTES bytes",
            )
        }
        val requestId = UUID.randomUUID().toString()
        val encodedChallenge = Base64Compat.encode(challenge.copyOf())
        val response = request(
            method = "POST",
            path = SIGN_PATH,
            requestBody = "{\"challenge\":\"$encodedChallenge\",\"requestId\":\"$requestId\"}",
            retry = true,
        )
        val signature = decodeBase64(RemoteMfiJson.string(response, "signature"), "signature")
        if (signature.isEmpty() || signature.size > MAXIMUM_RESPONSE_BYTES) {
            throw MfiInvalidDataException("Invalid remote signature length ${signature.size}")
        }
        signature
    }

    private fun loadCertificateInfo(): CertificateInfo {
        certificateInfo?.let { return it }
        val response = request(
            method = "GET",
            path = CERTIFICATE_PATH,
            requestBody = null,
            retry = true,
        )
        val protocolMajor = RemoteMfiJson.integer(response, "protocolMajor")
        if (protocolMajor !in 0..0xff) {
            throw MfiInvalidDataException("Invalid remote MFI protocol major $protocolMajor")
        }
        val typeText = RemoteMfiJson.optionalString(response, "type") ?: "mfi"
        val type = when (typeText.lowercase()) {
            "mfi" -> MfiCertificateType.MFI
            "baa" -> MfiCertificateType.BAA
            else -> throw MfiInvalidDataException("Unsupported remote MFI certificate type '$typeText'")
        }
        val certificate = decodeBase64(
            RemoteMfiJson.string(response, "certificate"),
            "certificate",
        )
        if (certificate.isEmpty() || certificate.size > MAXIMUM_RESPONSE_BYTES) {
            throw MfiInvalidDataException("Invalid remote certificate length ${certificate.size}")
        }
        val expectedDigest = decodeSha256(RemoteMfiJson.string(response, "certificateSha256"))
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(certificate)
        if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
            throw MfiInvalidDataException("Remote certificate SHA-256 does not match certificateSha256")
        }
        val baaCertificates = if (type == MfiCertificateType.BAA) {
            parseBaaCertificatePackage(certificate)
        } else {
            null
        }
        val iap2Certificate = if (baaCertificates != null) {
            Iap2AuthenticationMessages.baaCertificatePackage(
                baaCertificates.leaf,
                baaCertificates.intermediate,
            ).payload
        } else {
            certificate.copyOf()
        }
        if (iap2Certificate.size > MAXIMUM_RESPONSE_BYTES) {
            throw MfiInvalidDataException("Remote iAP2 certificate payload is too large")
        }
        return CertificateInfo(
            type = type,
            protocolMajor = protocolMajor,
            wireCertificate = certificate.copyOf(),
            iap2Certificate = iap2Certificate,
            baaCertificates = baaCertificates,
        ).also { certificateInfo = it }
    }

    private fun parseBaaCertificatePackage(encoded: ByteArray): BaaCertificatePair {
        if (encoded.size < BAA_PACKAGE_HEADER_BYTES) {
            throw MfiInvalidDataException("BAA certificate package is too short")
        }
        val leafLength = readU32(encoded, 0)
        val intermediateLength = readU32(encoded, 4)
        if (leafLength <= 0 || intermediateLength <= 0 ||
            BAA_PACKAGE_HEADER_BYTES + leafLength + intermediateLength != encoded.size) {
            throw MfiInvalidDataException("BAA certificate package has invalid lengths")
        }
        val leafEnd = BAA_PACKAGE_HEADER_BYTES + leafLength
        return BaaCertificatePair(
            leaf = encoded.copyOfRange(BAA_PACKAGE_HEADER_BYTES, leafEnd),
            intermediate = encoded.copyOfRange(leafEnd, encoded.size),
        )
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun request(
        method: String,
        path: String,
        requestBody: String?,
        retry: Boolean,
    ): String {
        var lastFailure: Throwable? = null
        val attempts = if (retry) maximumAttempts else 1
        repeat(attempts) { attempt ->
            try {
                val response = execute(method, path, requestBody)
                if (response.statusCode in 200..299) return response.body
                val detail = RemoteMfiJson.optionalString(response.body, "detail")
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.statusCode}"
                val failure = RemoteMfiHttpException(response.statusCode, detail)
                if (attempt + 1 >= attempts || !isRetryable(response.statusCode)) throw failure
                lastFailure = failure
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt + 1 >= attempts) {
                    throw RemoteMfiException("Remote MFI request failed: ${failure.message}", failure)
                }
            }
        }
        throw RemoteMfiException("Remote MFI request failed", lastFailure)
    }

    private fun execute(method: String, path: String, requestBody: String?): HttpResponse {
        val connection = URL(baseAddress + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", JSON_CONTENT_TYPE)
            bearerToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (requestBody != null) {
                val bodyBytes = requestBody.toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", JSON_CONTENT_TYPE)
                connection.setFixedLengthStreamingMode(bodyBytes.size)
                connection.outputStream.use { it.write(bodyBytes) }
            }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use(::readUtf8Limited).orEmpty()
            return HttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun readUtf8Limited(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAXIMUM_HTTP_BODY_BYTES) {
                throw IOException("Remote MFI response exceeds $MAXIMUM_HTTP_BODY_BYTES bytes")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun decodeBase64(encoded: String, field: String): ByteArray = try {
        Base64Compat.decode(encoded)
    } catch (failure: IllegalArgumentException) {
        throw MfiInvalidDataException("Remote $field is not valid base64", failure)
    }

    private fun decodeSha256(hex: String): ByteArray {
        if (hex.length != SHA256_HEX_LENGTH || hex.any { it.digitToIntOrNull(16) == null }) {
            throw MfiInvalidDataException("certificateSha256 must contain 64 hexadecimal characters")
        }
        return ByteArray(SHA256_BYTES) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun isRetryable(statusCode: Int): Boolean =
        statusCode == HttpURLConnection.HTTP_CLIENT_TIMEOUT ||
            statusCode == 429 ||
            statusCode in 500..599

    companion object {
        private val IPV4_LITERAL = Regex("(0|[1-9]\\d{0,2})(\\.(0|[1-9]\\d{0,2})){3}")

        private fun hostOf(address: String): String? =
            runCatching { URL(address).host }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }

        /**
         * Decides from the text alone and never resolves a name. A single-label or .local name is trusted by its
         * text, so a search domain or hosts entry can still point it at a public address.
         * A dotted quad with a leading zero is not a literal: libc may read 010 as octal.
         * A single label needs a letter and must not look like hex, so 134744072 and 0x08080808 are refused.
         */
        internal fun isPrivateNetworkHost(host: String?): Boolean {
            if (host == null) return false
            val literal = host.startsWith("[") || IPV4_LITERAL.matches(host)
            if (!literal) {
                val singleLabel = '.' !in host && host.any { it.isLetter() } && !host.startsWith("0x")
                return host == "localhost" || singleLabel || host.endsWith(".local")
            }
            val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return false
            val bytes = address.address
            val carrierGradeNat = bytes.size == 4 &&
                (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 0x40
            val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
            return address.isLoopbackAddress || address.isSiteLocalAddress ||
                address.isLinkLocalAddress || carrierGradeNat || uniqueLocalV6
        }

        private const val CERTIFICATE_PATH = "/mfi/certificate"
        private const val SIGN_PATH = "/mfi/sign"
        private const val RESET_PATH = "/mfi/reset"
        private const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 10_000
        private const val DEFAULT_MAXIMUM_ATTEMPTS = 2
        private const val MINIMUM_CHALLENGE_BYTES = 1
        private const val MAXIMUM_CHALLENGE_BYTES = 128
        private const val MAXIMUM_RESPONSE_BYTES = 65_525
        private const val MAXIMUM_HTTP_BODY_BYTES = 2 * 1024 * 1024
        private const val SHA256_BYTES = 32
        private const val SHA256_HEX_LENGTH = SHA256_BYTES * 2
        private const val BAA_PACKAGE_HEADER_BYTES = 8
    }
}

class RemoteMfiException(message: String, cause: Throwable? = null) : MfiException(message, cause)

class RemoteMfiHttpException(
    val statusCode: Int,
    val detail: String,
) : MfiException("Remote MFI request failed with HTTP $statusCode: $detail")

/** Minimal flat-object JSON reader for the remote MFI wire format. */
internal object RemoteMfiJson {
    fun string(json: String, name: String): String = optionalString(json, name)
        ?: throw MfiInvalidDataException("Remote MFI response is missing '$name'")

    fun optionalString(json: String, name: String): String? {
        val match = stringPattern(name).find(json) ?: return null
        return decodeJsonString(match.groupValues[1])
    }

    fun integer(json: String, name: String): Int {
        val match = integerPattern(name).find(json)
            ?: throw MfiInvalidDataException("Remote MFI response is missing '$name'")
        return match.groupValues[1].toIntOrNull()
            ?: throw MfiInvalidDataException("Remote MFI '$name' is not a valid integer")
    }

    private fun stringPattern(name: String): Regex =
        Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")

    private fun integerPattern(name: String): Regex =
        Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(-?\\d+)")

    private fun decodeJsonString(encoded: String): String {
        val result = StringBuilder(encoded.length)
        var index = 0
        while (index < encoded.length) {
            val character = encoded[index++]
            if (character != '\\') {
                result.append(character)
                continue
            }
            if (index >= encoded.length) throw MfiInvalidDataException("Invalid JSON string escape")
            when (val escaped = encoded[index++]) {
                '\"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (index + 4 > encoded.length) {
                        throw MfiInvalidDataException("Invalid JSON unicode escape")
                    }
                    val value = encoded.substring(index, index + 4).toIntOrNull(16)
                        ?: throw MfiInvalidDataException("Invalid JSON unicode escape")
                    result.append(value.toChar())
                    index += 4
                }
                else -> throw MfiInvalidDataException("Unsupported JSON string escape \\$escaped")
            }
        }
        return result.toString()
    }
}
