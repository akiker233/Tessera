package com.tessera.otp.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查更新的纯逻辑：版本比较、Release 解析与 ABI 选包。 */
class UpdateCheckerTest {

    private fun releaseJson(
        tag: String,
        assets: List<Pair<String, String>> = emptyList(),
        notes: String = "本次更新内容",
        htmlUrl: String = "https://github.com/akiker233/Tessera/releases/tag/$tag"
    ): String {
        val assetJson = assets.joinToString(",") { (name, url) ->
            """{"name":"$name","browser_download_url":"$url"}"""
        }
        return """
            {
              "tag_name":"$tag",
              "html_url":"$htmlUrl",
              "body":"$notes",
              "assets":[$assetJson]
            }
        """.trimIndent()
    }

    @Test
    fun `高版本号识别为可更新`() {
        val json = releaseJson(
            "v1.1.1",
            listOf(
                "Tessera-v1.1.1-arm64-v8a.apk" to "https://example.com/arm64.apk",
                "Tessera-v1.1.1-universal.apk" to "https://example.com/universal.apk"
            )
        )
        val info = parseLatestRelease(json, "1.1.0", listOf("arm64-v8a"))
        assertTrue(info != null)
        assertEquals("1.1.1", info!!.latestVersion)
        assertEquals("https://example.com/arm64.apk", info.downloadUrl)
        assertEquals("本次更新内容", info.notes)
    }

    @Test
    fun `同版本或旧版本不提示更新`() {
        assertNull(parseLatestRelease(releaseJson("v1.1.0"), "1.1.0", listOf("arm64-v8a")))
        assertNull(parseLatestRelease(releaseJson("v1.0.9"), "1.1.0", listOf("arm64-v8a")))
    }

    @Test
    fun `按设备 ABI 顺序选包`() {
        val assets = listOf(
            ReleaseAsset("Tessera-v2.0.0-x86_64.apk", "https://example.com/x86_64.apk"),
            ReleaseAsset("Tessera-v2.0.0-arm64-v8a.apk", "https://example.com/arm64.apk"),
            ReleaseAsset("Tessera-v2.0.0-universal.apk", "https://example.com/universal.apk")
        )
        assertEquals("https://example.com/arm64.apk", pickAsset(assets, listOf("arm64-v8a"))?.downloadUrl)
        assertEquals("https://example.com/x86_64.apk", pickAsset(assets, listOf("x86_64", "arm64-v8a"))?.downloadUrl)
    }

    @Test
    fun `没有匹配 ABI 时回退 universal`() {
        val assets = listOf(
            ReleaseAsset("Tessera-v2.0.0-x86.apk", "https://example.com/x86.apk"),
            ReleaseAsset("Tessera-v2.0.0-universal.apk", "https://example.com/universal.apk")
        )
        assertEquals("https://example.com/universal.apk", pickAsset(assets, listOf("arm64-v8a"))?.downloadUrl)
    }

    @Test
    fun `既无匹配 ABI 也无 universal 时不选包`() {
        val assets = listOf(ReleaseAsset("Tessera-v2.0.0-x86.apk", "https://example.com/x86.apk"))
        assertNull(pickAsset(assets, listOf("arm64-v8a")))
    }

    @Test
    fun `版本号比较容忍前缀与后缀`() {
        assertEquals(0, compareVersions("v1.1.0", "1.1.0"))
        assertEquals(0, compareVersions("1.1", "1.1.0"))
        assertTrue(compareVersions("1.10.0", "1.9.9") > 0)
        assertTrue(compareVersions("2.0.0-beta", "1.99.99") > 0)
        assertTrue(compareVersions("1.1.0", "1.1.1") < 0)
    }

    @Test
    fun `无法识别的 tag 报错而不是误判`() {
        val failed = runCatching {
            parseLatestRelease(releaseJson("nightly"), "1.1.0", listOf("arm64-v8a"))
        }.isFailure
        assertTrue("非数字 tag 应抛 UpdateException", failed)
    }

    @Test
    fun `缺少版本号字段时报错`() {
        val failed = runCatching {
            parseLatestRelease("""{"html_url":"https://example.com"}""", "1.1.0", listOf("arm64-v8a"))
        }.isFailure
        assertTrue(failed)
    }
}
