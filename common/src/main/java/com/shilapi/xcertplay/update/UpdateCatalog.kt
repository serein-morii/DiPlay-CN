package com.shilapi.xcertplay.update

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
                if (name == expectedApkName && url.isNotEmpty()) {
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
