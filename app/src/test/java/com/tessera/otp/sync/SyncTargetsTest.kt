package com.tessera.otp.sync

import com.tessera.otp.data.prefs.AppSettings
import com.tessera.otp.data.prefs.LEGACY_ACCOUNT_ID
import com.tessera.otp.data.prefs.SINGLETON_PROVIDER_ORDER
import com.tessera.otp.data.prefs.SyncProviderType
import com.tessera.otp.data.prefs.WebDavServer
import com.tessera.otp.data.prefs.accountDisplayName
import com.tessera.otp.data.prefs.deriveDisabledProviders
import com.tessera.otp.data.prefs.displayLabel
import com.tessera.otp.data.prefs.googleDriveBindingKey
import com.tessera.otp.data.prefs.oneDriveBindingKey
import com.tessera.otp.data.prefs.webdavBindingKey
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多实例后端（WebDAV / Google Drive / OneDrive）：参与同步的目标汇总、绑定判定与展示名。 */
class SyncTargetsTest {

    private fun webdav(id: String, url: String = "https://dav.example.com/a/b/") =
        WebDavServer(id = id, url = url)

    @Test
    fun `已绑定的多个 webdav 服务器全部参与同步`() {
        val settings = AppSettings(webdavServers = listOf(webdav("a"), webdav("b")))
        val targets = SyncProviderFactory.targets(settings, emptyList())
        assertEquals(2, targets.size)
        assertTrue(targets.all { it.type == SyncProviderType.WEBDAV })
        assertEquals(setOf("a", "b"), targets.mapNotNull { it.instanceId }.toSet())
    }

    @Test
    fun `被停用的 webdav 服务器不参与同步`() {
        val settings = AppSettings(
            webdavServers = listOf(webdav("a"), webdav("b")),
            disabledProviders = setOf(webdavBindingKey("b"))
        )
        val targets = SyncProviderFactory.targets(settings, emptyList())
        assertEquals(listOf("a"), targets.mapNotNull { it.instanceId })
    }

    @Test
    fun `未绑定的单实例后端不会凭空进入目标`() {
        // 只有「已绑定」（凭据填齐）的后端才参与同步，否则同步时会冒出一串"请填写令牌"
        val settings = AppSettings(
            webdavServers = listOf(webdav("a")),
            githubToken = "t",
            githubOwnerRepo = "me/repo"
        )
        val targets = SyncProviderFactory.targets(settings, emptyList())
        assertEquals(listOf(SyncProviderType.WEBDAV, SyncProviderType.GITHUB), targets.map { it.type })
    }

    @Test
    fun `未选择任何方式时没有目标`() {
        assertTrue(SyncProviderFactory.targets(AppSettings(), emptyList()).isEmpty())
    }

    @Test
    fun `已绑定但被停用的后端不参与同步`() {
        val settings = AppSettings(
            githubToken = "t",
            githubOwnerRepo = "me/repo",
            disabledProviders = setOf(SyncProviderType.GITHUB.name)
        )
        assertTrue(SyncProviderFactory.targets(settings, emptyList()).isEmpty())
    }

    @Test
    fun `Google Drive 的多个账号各自成为一条目标`() {
        val settings = AppSettings(googleAccounts = listOf("a@example.com", "b@example.com"))
        val targets = SyncProviderFactory.targets(settings, emptyList())
        assertEquals(
            listOf("a@example.com", "b@example.com"),
            targets.filter { it.type == SyncProviderType.GOOGLE_DRIVE }.mapNotNull { it.instanceId }
        )
    }

    @Test
    fun `OneDrive 的多个已登录账号各自成为一条目标`() {
        val targets = SyncProviderFactory.targets(
            AppSettings(),
            listOf("a@outlook.com", "b@outlook.com")
        )
        assertEquals(
            listOf("a@outlook.com", "b@outlook.com"),
            targets.filter { it.type == SyncProviderType.ONEDRIVE }.mapNotNull { it.instanceId }
        )
        // 没有登录账号时不会凭空产生目标
        assertTrue(SyncProviderFactory.targets(AppSettings(), emptyList()).isEmpty())
    }

    @Test
    fun `停用其中一个账号不影响同一后端的其它账号`() {
        val settings = AppSettings(
            googleAccounts = listOf("a@example.com", "b@example.com"),
            disabledProviders = setOf(googleDriveBindingKey("b@example.com"))
        )
        val targets = SyncProviderFactory.targets(settings, emptyList())
        assertEquals(listOf("a@example.com"), targets.mapNotNull { it.instanceId })
    }

    @Test
    fun `绑定键：单实例用枚举名，多实例用前缀加实例 id`() {
        assertEquals("GITHUB", SyncProviderFactory.bindingKey(SyncProviderType.GITHUB))
        assertEquals("WEBDAV:a", SyncProviderFactory.bindingKey(SyncProviderType.WEBDAV, "a"))
        assertEquals("GOOGLE_DRIVE:a@b.c", SyncProviderFactory.bindingKey(SyncProviderType.GOOGLE_DRIVE, "a@b.c"))
        assertEquals("ONEDRIVE:a@b.c", SyncProviderFactory.bindingKey(SyncProviderType.ONEDRIVE, "a@b.c"))
    }

    @Test
    fun `单实例后端的绑定判定要求凭据齐全`() {
        assertFalse(SyncProviderFactory.hasCredentials(AppSettings(), SyncProviderType.GITHUB))
        // 有令牌但缺 owner/repo 不算绑定
        assertFalse(SyncProviderFactory.hasCredentials(AppSettings(githubToken = "t"), SyncProviderType.GITHUB))
        assertTrue(
            SyncProviderFactory.hasCredentials(
                AppSettings(githubToken = "t", githubOwnerRepo = "me/repo"),
                SyncProviderType.GITHUB
            )
        )
        assertTrue(SyncProviderFactory.hasCredentials(AppSettings(gistToken = "t"), SyncProviderType.GIST))
        assertFalse(SyncProviderFactory.hasCredentials(AppSettings(), SyncProviderType.GITEE))
        // 多实例后端不在这里判（凭据就是各自的条目本身）
        assertFalse(SyncProviderFactory.hasCredentials(AppSettings(), SyncProviderType.WEBDAV))
    }

    @Test
    fun `boundTargets 汇总所有已绑定的实例`() {
        val settings = AppSettings(
            webdavServers = listOf(webdav("a")),
            googleAccounts = listOf("a@example.com"),
            gistToken = "t",
            githubToken = "t",
            githubOwnerRepo = "me/repo"
        )
        val bound = SyncProviderFactory.boundTargets(settings, listOf("o@outlook.com"))
        assertEquals(
            listOf(
                SyncProviderType.WEBDAV,
                SyncProviderType.GOOGLE_DRIVE,
                SyncProviderType.ONEDRIVE,
                SyncProviderType.GITHUB,
                SyncProviderType.GIST
            ),
            bound.map { it.type }
        )
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
    fun `旧版单账号迁移出来的记录展示为默认账号`() {
        assertEquals("默认账号", accountDisplayName(LEGACY_ACCOUNT_ID))
        assertEquals("a@example.com", accountDisplayName("a@example.com"))
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
