package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection

internal object UpdateReleaseLookup {
    fun latest(
        installedVersion: String,
        userAgent: String,
        channel: Int = 1,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): UpdateRelease? {
        var lastFailure: Exception? = null
        var checkedSuccessfully = false
        for (source in UpdateChannels.sources(channel)) {
            try {
                val json = UpdateClient.fetchText(source.releasesUrl, "application/json", userAgent, openConnection)
                checkedSuccessfully = true
                val release = UpdateCatalog.parse(json)
                if (release != null && UpdateVersion.isNewer(release.tagName, installedVersion)) {
                    return release.copy(
                        apkUrl = source.assetUrl(release.apkUrl),
                        checksumsUrl = source.assetUrl(release.checksumsUrl),
                    )
                }
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        if (!checkedSuccessfully) throw lastFailure ?: IllegalStateException("No update source available")
        return null
    }
}
