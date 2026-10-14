package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelsTest {
    @Test fun giteeIsTheDefaultWithGithubFallback() {
        val sources = UpdateChannels.sources(1)
        assertEquals(UpdateClient.GITEE_RELEASES_URL, sources.first().releasesUrl)
        assertEquals(UpdateClient.GITHUB_RELEASES_URL, sources.last().releasesUrl)
    }

    @Test fun mirrorChoiceAppliesToBothMetadataAndAssets() {
        val mirror = UpdateChannels.sources(2).first()
        assertTrue(mirror.releasesUrl.startsWith("https://gh-proxy.com/https://api.github.com/"))
        assertEquals("https://gh-proxy.com/https://example.com/DiPlay-cn.apk",
            mirror.assetUrl("https://example.com/DiPlay-cn.apk"))
    }
}
