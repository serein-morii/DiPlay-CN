package com.shilapi.xcertplay.update

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"
    // CN releases are mirrored on Gitee; the acceleration mirrors prefix a github.com URL at download time.
    private val DOWNLOAD_HOSTS = setOf("github.com", "gitee.com")
    // The name becomes a file in the cache folder, so it can hold no path.
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")

    private fun isDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && host.lowercase() in DOWNLOAD_HOSTS &&
            uri.rawUserInfo == null && uri.port == -1
    }

    /** Returns the first published release that carries an APK and its checksums, or null. */
    internal fun parse(json: String): UpdateRelease? {
        val releases = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONArray().put(JSONObject(json))
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val tag = release.optString("tag_name")
            if (!Regex("^v\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?-cn\\.\\d+$").matches(tag)) continue
            val expectedApkName = "DiPlay-cn-$tag.apk"
            var apkName = ""
            var apkUrl = ""
            var legacyChecksumsUrl = ""
            val checksumsByName = mutableMapOf<String, String>()
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!isDownloadUrl(url)) continue
                if (name == expectedApkName && APK_NAME.matches(name)) {
                    apkName = name
                    apkUrl = url
                }
                if (name.equals(CHECKSUMS_FILE, ignoreCase = true)) legacyChecksumsUrl = url
                if (name.endsWith(".apk.sha256", ignoreCase = true)) checksumsByName[name] = url
            }
            val checksumsUrl = checksumsByName["$apkName.sha256"] ?: legacyChecksumsUrl
            if (apkUrl.isEmpty() || checksumsUrl.isEmpty()) continue
            return UpdateRelease(tag, apkName, apkUrl, checksumsUrl)
        }
        return null
    }
}
