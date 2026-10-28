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
              {"name": "DiPlay-cn-v0.2.15-cn.1.apk", "browser_download_url": "${GITHUB}v0.2.15-cn.1/DiPlay-cn-v0.2.15-cn.1.apk"},
              {"name": "DiPlay-cn-v0.2.15-cn.1.apk.sha256", "browser_download_url": "${GITHUB}v0.2.15-cn.1/DiPlay-cn-v0.2.15-cn.1.apk.sha256"}
            ]
          },
          {
            "tag_name": "v0.2.14-cn.2",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "DiPlay-cn-v0.2.14-cn.2.apk", "browser_download_url": "${GITHUB}v0.2.14-cn.2/DiPlay-cn-v0.2.14-cn.2.apk"},
              {"name": "DiPlay-cn-v0.2.14-cn.2.apk.sha256", "browser_download_url": "${GITHUB}v0.2.14-cn.2/DiPlay-cn-v0.2.14-cn.2.apk.sha256"}
            ]
          }
        ]
    """.trimIndent()

    @Test
    fun skipsDraftsAndReturnsTheFirstPublishedRelease() {
        val release = UpdateCatalog.parse(releaseJson)
        assertEquals("v0.2.14-cn.2", release?.tagName)
        assertEquals("DiPlay-cn-v0.2.14-cn.2.apk", release?.apkName)
        assertEquals("${GITHUB}v0.2.14-cn.2/DiPlay-cn-v0.2.14-cn.2.apk", release?.apkUrl)
        assertEquals("${GITHUB}v0.2.14-cn.2/DiPlay-cn-v0.2.14-cn.2.apk.sha256", release?.checksumsUrl)
    }

    @Test
    fun returnsNullWithoutAnApkAsset() {
        val json = """
            [
              {"tag_name": "v0.2.14-cn.2", "draft": false, "assets": [
                {"name": "source.zip", "browser_download_url": "${GITHUB}v0.2.14-cn.2/source.zip"}
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
                {"name": "DiPlay-cn-v0.2.14-cn.2.apk", "browser_download_url": "${GITHUB}v0.2.14-cn.2/DiPlay-cn-v0.2.14-cn.2.apk"}
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
            {"name":"DiPlay-cn-v0.2.17-cn.3.apk","browser_download_url":"${GITEE}v0.2.17-cn.3/DiPlay-cn-v0.2.17-cn.3.apk"},
            {"name":"DiPlay-cn-v0.2.17-cn.3.apk.sha256","browser_download_url":"${GITEE}v0.2.17-cn.3/DiPlay-cn-v0.2.17-cn.3.apk.sha256"}
        ]}"""
        assertEquals("v0.2.17-cn.3", UpdateCatalog.parse(json)?.tagName)
        assertEquals("${GITEE}v0.2.17-cn.3/DiPlay-cn-v0.2.17-cn.3.apk.sha256", UpdateCatalog.parse(json)?.checksumsUrl)
    }

    @Test
    fun acceptsHistoricalFourComponentCnTags() {
        val tag = "v0.2.8.5-cn.1"
        val json = """{"tag_name":"$tag","assets":[
            {"name":"DiPlay-cn-$tag.apk","browser_download_url":"$GITHUB$tag/DiPlay-cn-$tag.apk"},
            {"name":"DiPlay-cn-$tag.apk.sha256","browser_download_url":"$GITHUB$tag/DiPlay-cn-$tag.apk.sha256"}
        ]}"""
        assertEquals(tag, UpdateCatalog.parse(json)?.tagName)
    }

    @Test
    fun skipsIncompleteReleaseAndNeverSelectsOfficialApk() {
        val json = """[
            {"tag_name":"v0.2.18-cn.1","assets":[
                {"name":"DiPlay-cn-v0.2.18-cn.1.apk","browser_download_url":"${GITHUB}v0.2.18-cn.1/new.apk"}]},
            {"tag_name":"v0.2.17-cn.3","assets":[
                {"name":"DiPlay-0.2.17.apk","browser_download_url":"${GITHUB}v0.2.17-cn.3/official.apk"},
                {"name":"DiPlay-cn-v0.2.17-cn.3.apk","browser_download_url":"${GITHUB}v0.2.17-cn.3/cn.apk"},
                {"name":"DiPlay-cn-v0.2.17-cn.3.apk.sha256","browser_download_url":"${GITHUB}v0.2.17-cn.3/cn.sha256"}]}
        ]"""
        assertEquals("${GITHUB}v0.2.17-cn.3/cn.apk", UpdateCatalog.parse(json)?.apkUrl)
    }

    private fun releaseWith(
        tag: String = "v0.2.22-cn.1",
        apkName: String = "DiPlay-cn-v0.2.22-cn.1.apk",
        apkUrl: String,
        checksumsUrl: String = "${GITHUB}v0.2.22-cn.1/DiPlay-cn-v0.2.22-cn.1.apk.sha256",
    ) = """
        [{"tag_name": ${org.json.JSONObject.quote(tag)}, "draft": false, "assets": [
          {"name": ${org.json.JSONObject.quote(apkName)}, "browser_download_url": ${org.json.JSONObject.quote(apkUrl)}},
          {"name": "DiPlay-cn-v0.2.22-cn.1.apk.sha256", "browser_download_url": ${org.json.JSONObject.quote(checksumsUrl)}}
        ]}]
    """.trimIndent()

    @Test
    fun acceptsTheAssetsOfARealRelease() {
        val release = UpdateCatalog.parse(releaseWith(apkUrl = "${GITHUB}v0.2.22-cn.1/DiPlay-cn-v0.2.22-cn.1.apk"))
        assertEquals("DiPlay-cn-v0.2.22-cn.1.apk", release?.apkName)
    }

    @Test
    fun acceptsTheGiteeMirrorHost() {
        val release = UpdateCatalog.parse(releaseWith(
            apkUrl = "${GITEE}v0.2.22-cn.1/DiPlay-cn-v0.2.22-cn.1.apk",
            checksumsUrl = "${GITEE}v0.2.22-cn.1/DiPlay-cn-v0.2.22-cn.1.apk.sha256",
        ))
        assertEquals("${GITEE}v0.2.22-cn.1/DiPlay-cn-v0.2.22-cn.1.apk", release?.apkUrl)
    }

    @Test
    fun refusesAnApkNameThatLeavesTheDownloadFolder() {
        for (name in listOf("../../evil.apk", "..\\evil.apk", "/data/evil.apk", "sub/evil.apk", ".apk", "evil.apk\u0000.txt.apk")) {
            assertNull(name, UpdateCatalog.parse(releaseWith(apkName = name, apkUrl = "${GITHUB}v0.2.22-cn.1/x.apk")))
        }
    }

    @Test
    fun refusesDownloadUrlsThatAreNotHttpsOnAKnownHost() {
        for (url in listOf(
            "http://github.com/serein-morii/DiPlay-CN/releases/download/v0.2.22-cn.1/DiPlay-cn-v0.2.22-cn.1.apk",
            "https://evil.example/DiPlay-cn-v0.2.22-cn.1.apk",
            "https://github.com.evil.example/DiPlay-cn-v0.2.22-cn.1.apk",
            "https://gitee.com.evil.example/DiPlay-cn-v0.2.22-cn.1.apk",
            "https://github.com@evil.example/DiPlay-cn-v0.2.22-cn.1.apk",
            "https://evil.example/github.com/DiPlay-cn-v0.2.22-cn.1.apk",
            "https://github.com:8443/serein-morii/DiPlay-CN/DiPlay-cn-v0.2.22-cn.1.apk",
            "file:///sdcard/DiPlay-cn-v0.2.22-cn.1.apk",
            "ftp://github.com/DiPlay-cn-v0.2.22-cn.1.apk",
        )) {
            assertNull(url, UpdateCatalog.parse(releaseWith(apkUrl = url)))
            assertNull(url, UpdateCatalog.parse(releaseWith(apkUrl = "${GITHUB}v0.2.22-cn.1/x.apk", checksumsUrl = url)))
        }
    }

    private companion object {
        const val GITHUB = "https://github.com/serein-morii/DiPlay-CN/releases/download/"
        const val GITEE = "https://gitee.com/oneeyear/DiPlay-CN/releases/download/"
    }
}
