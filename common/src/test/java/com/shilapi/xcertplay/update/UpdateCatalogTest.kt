package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UpdateCatalogTest {
    private val releaseJson = """
        [
          {
            "tag_name": "v0.2.15-cn.1",
            "draft": true,
            "assets": [
              {"name": "DiPlay-cn-v0.2.15-cn.1.apk", "browser_download_url": "https://example.com/DiPlay-cn-v0.2.15-cn.1.apk"},
              {"name": "DiPlay-cn-v0.2.15-cn.1.apk.sha256", "browser_download_url": "https://example.com/DiPlay-cn-v0.2.15-cn.1.apk.sha256"}
            ]
          },
          {
            "tag_name": "v0.2.14-cn.2",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "DiPlay-cn-v0.2.14-cn.2.apk", "browser_download_url": "https://example.com/DiPlay-cn-v0.2.14-cn.2.apk"},
              {"name": "DiPlay-cn-v0.2.14-cn.2.apk.sha256", "browser_download_url": "https://example.com/DiPlay-cn-v0.2.14-cn.2.apk.sha256"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun skipsDraftsAndReturnsTheFirstPublishedRelease() {
        val release = UpdateCatalog.parse(releaseJson)
        assertEquals("v0.2.14-cn.2", release?.tagName)
        assertEquals("DiPlay-cn-v0.2.14-cn.2.apk", release?.apkName)
        assertEquals("https://example.com/DiPlay-cn-v0.2.14-cn.2.apk", release?.apkUrl)
        assertEquals("https://example.com/DiPlay-cn-v0.2.14-cn.2.apk.sha256", release?.checksumsUrl)
    }

    @Test
    fun returnsNullWithoutAnApkAsset() {
        val json = """
            [
              {"tag_name": "v0.2.14-cn.2", "draft": false, "assets": [
                {"name": "source.zip", "browser_download_url": "https://example.com/source.zip"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullWithoutChecksums() {
        val json = """
            [
              {"tag_name": "v0.2.14-cn.2", "draft": false, "assets": [
                {"name": "DiPlay-cn-v0.2.14-cn.2.apk", "browser_download_url": "https://example.com/DiPlay-cn-v0.2.14-cn.2.apk"}
              ]}
            ]
        """.trimIndent()
        assertNull(UpdateCatalog.parse(json))
    }

    @Test
    fun returnsNullForAnEmptyReleaseList() {
        assertNull(UpdateCatalog.parse("[]"))
    }

    @Test
    fun parsesGiteeLatestReleaseObject() {
        val json = """{"tag_name":"v0.2.17-cn.3","assets":[
            {"name":"DiPlay-cn-v0.2.17-cn.3.apk","browser_download_url":"https://example.com/app.apk"},
            {"name":"DiPlay-cn-v0.2.17-cn.3.apk.sha256","browser_download_url":"https://example.com/hash"}
        ]}"""
        assertEquals("v0.2.17-cn.3", UpdateCatalog.parse(json)?.tagName)
        assertEquals("https://example.com/hash", UpdateCatalog.parse(json)?.checksumsUrl)
    }

    @Test
    fun acceptsHistoricalFourComponentCnTags() {
        val tag = "v0.2.8.5-cn.1"
        val json = """{"tag_name":"$tag","assets":[
            {"name":"DiPlay-cn-$tag.apk","browser_download_url":"https://example.com/app.apk"},
            {"name":"DiPlay-cn-$tag.apk.sha256","browser_download_url":"https://example.com/hash"}
        ]}"""
        assertEquals(tag, UpdateCatalog.parse(json)?.tagName)
    }

    @Test
    fun skipsIncompleteReleaseAndNeverSelectsOfficialApk() {
        val json = """[
            {"tag_name":"v0.2.18-cn.1","assets":[
                {"name":"DiPlay-cn-v0.2.18-cn.1.apk","browser_download_url":"https://example.com/new.apk"}]},
            {"tag_name":"v0.2.17-cn.3","assets":[
                {"name":"DiPlay-0.2.17.apk","browser_download_url":"https://example.com/official.apk"},
                {"name":"DiPlay-cn-v0.2.17-cn.3.apk","browser_download_url":"https://example.com/cn.apk"},
                {"name":"DiPlay-cn-v0.2.17-cn.3.apk.sha256","browser_download_url":"https://example.com/cn.sha256"}]}
        ]"""
        assertEquals("https://example.com/cn.apk", UpdateCatalog.parse(json)?.apkUrl)
    }
}
