package com.shilapi.xcertplay.update

/** CN release sources. The first entry is the driver's choice; the others are fallbacks. */
internal object UpdateChannels {
    data class Source(val releasesUrl: String, val assetPrefix: String = "") {
        fun assetUrl(url: String): String = assetPrefix + url
    }

    private val gitee = Source(UpdateClient.GITEE_RELEASES_URL)
    private val github = Source(UpdateClient.GITHUB_RELEASES_URL)
    private val mirrorOne = Source("https://gh-proxy.com/${UpdateClient.GITHUB_RELEASES_URL}", "https://gh-proxy.com/")
    private val mirrorTwo = Source("https://ghproxy.net/${UpdateClient.GITHUB_RELEASES_URL}", "https://ghproxy.net/")

    fun sources(choice: Int): List<Source> = when (choice) {
        0 -> listOf(github, gitee)
        2 -> listOf(mirrorOne, gitee, github)
        3 -> listOf(mirrorTwo, gitee, github)
        else -> listOf(gitee, github)
    }
}
