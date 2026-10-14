package com.tessera.otp.sync

import com.tessera.otp.data.prefs.AppSettings
import com.tessera.otp.data.prefs.SINGLETON_PROVIDER_ORDER
import com.tessera.otp.data.prefs.SyncProviderType
import com.tessera.otp.data.prefs.WebDavServer
import com.tessera.otp.data.prefs.deriveDisabledProviders
import com.tessera.otp.data.prefs.displayLabel
import com.tessera.otp.data.prefs.webdavBindingKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多 WebDAV 服务器：参与同步的目标汇总、绑定判定与序列化/展示名。 */
class SyncTargetsTest {

    private fun webdav(id: String, url: String = "https://dav.example.com/a/b/") =
        WebDavServer(id = id, url = url)

    @Test
    fun `已绑定的多个 webdav 服务器全部参与同步`() {
        val settings = AppSettings(webdavServers = listOf(webdav("a"), webdav("b")))
        val targets = SyncProviderFactory.targets(settings, oneDriveConnected = false)
        assertEquals(2, targets.size)
        assertTrue(targets.all { it.type == SyncProviderType.WEBDAV })
        assertEquals(setOf("a", "b"), targets.mapNotNull { it.webdavServerId }.toSet())
    }

    @Test
    fun `被停用的 webdav 服务器不参与同步`() {
        val settings = AppSettings(
            webdavServers = listOf(webdav("a"), webdav("b")),
            disabledProviders = setOf(webdavBindingKey("b"))
        )
        val targets = SyncProviderFactory.targets(settings, oneDriveConnected = false)
        assertEquals(listOf("a"), targets.mapNotNull { it.webdavServerId })
    }

    @Test
    fun `未绑定的单实例后端不会凭空进入目标`() {
        // 只有「已绑定」（凭据填齐）的后端才参与同步，否则同步时会冒出一串"请填写令牌"
        val settings = AppSettings(
            webdavServers = listOf(webdav("a")),
            githubToken = "t",
            githubOwnerRepo = "me/repo"
        )
        val targets = SyncProviderFactory.targets(settings, oneDriveConnected = false)
        assertEquals(listOf(SyncProviderType.WEBDAV, SyncProviderType.GITHUB), targets.map { it.type })
    }

    @Test
    fun `未选择任何方式时没有目标`() {
        assertTrue(SyncProviderFactory.targets(AppSettings(), oneDriveConnected = false).isEmpty())
    }

    @Test
    fun `已绑定但被停用的后端不参与同步`() {
        val settings = AppSettings(
            githubToken = "t",
            githubOwnerRepo = "me/repo",
            disabledProviders = setOf(SyncProviderType.GITHUB.name)
        )
        assertTrue(SyncProviderFactory.targets(settings, oneDriveConnected = false).isEmpty())
    }

    @Test
    fun `OneDrive 的连接状态由外部传入`() {
        assertEquals(
            listOf(SyncProviderType.ONEDRIVE),
            SyncProviderFactory.targets(AppSettings(), oneDriveConnected = true).map { it.type }
        )
        assertTrue(SyncProviderFactory.targets(AppSettings(), oneDriveConnected = false).isEmpty())
    }

    @Test
    fun `绑定判定的键：单实例用枚举名，webdav 用前缀加服务器 id`() {
        assertEquals("GITHUB", SyncProviderFactory.bindingKey(SyncProviderType.GITHUB))
        assertEquals("WEBDAV:a", SyncProviderFactory.bindingKey(SyncProviderType.WEBDAV, "a"))
    }

    @Test
    fun `绑定判定要求凭据齐全`() {
        assertFalse(SyncProviderFactory.isBound(AppSettings(), SyncProviderType.GITHUB, false))
        // 有令牌但缺 owner/repo 不算绑定
        assertFalse(
            SyncProviderFactory.isBound(AppSettings(githubToken = "t"), SyncProviderType.GITHUB, false)
        )
        assertTrue(
            SyncProviderFactory.isBound(
                AppSettings(githubToken = "t", githubOwnerRepo = "me/repo"),
                SyncProviderType.GITHUB, false
            )
        )
        assertTrue(
            SyncProviderFactory.isBound(AppSettings(gistToken = "t"), SyncProviderType.GIST, false)
        )
        assertTrue(
            SyncProviderFactory.isBound(AppSettings(googleAccount = "a@b.c"), SyncProviderType.GOOGLE_DRIVE, false)
        )
        assertTrue(SyncProviderFactory.isBound(AppSettings(), SyncProviderType.ONEDRIVE, true))
        assertTrue(
            SyncProviderFactory.isBound(
                AppSettings(webdavServers = listOf(webdav("a"))), SyncProviderType.WEBDAV, false
            )
        )
        assertFalse(SyncProviderFactory.isBound(AppSettings(), SyncProviderType.WEBDAV, false))
    }

    @Test
    fun `旧配置派生：只有当时参与过同步的后端保持启用`() {
        // 旧版只选了 GitHub 仓库 → 其余单实例后端都记为停用
        val disabled = deriveDisabledProviders(
            legacyEnabled = setOf(SyncProviderType.GITEE.name),
            legacyProvider = SyncProviderType.GITHUB.name
        )
        assertEquals(
            SINGLETON_PROVIDER_ORDER.filterNot {
                it == SyncProviderType.GITHUB || it == SyncProviderType.GITEE
            }.map { it.name }.toSet(),
            disabled
        )
    }

    @Test
    fun `旧配置完全启用时不产生停用项`() {
        val all = SINGLETON_PROVIDER_ORDER.map { it.name }.toSet()
        assertTrue(deriveDisabledProviders(all, SyncProviderType.NONE.name).isEmpty())
    }

    @Test
    fun `备注名优先展示，否则从地址派生`() {
        assertEquals("家里 NAS", WebDavServer(id = "x", label = "家里 NAS", url = "https://a.com/b").displayLabel)
        assertEquals(
            "dav.example.com/files/me",
            WebDavServer(id = "x", url = "https://dav.example.com/files/me/").displayLabel
        )
    }

    @Test
    fun `服务器列表可以 JSON 往返`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val servers = listOf(
            WebDavServer(id = "a", label = "n1", url = "https://s1/", user = "u", password = "p", fileName = "one.vault"),
            WebDavServer(id = "b", url = "https://s2/")
        )
        val encoded = json.encodeToString(servers)
        assertEquals(servers, json.decodeFromString<List<WebDavServer>>(encoded))
    }
}
