package com.tessera.otp.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** 服务器列表等结构化配置在 DataStore 里以 JSON 字符串存放。 */
private val configJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 自动锁定延迟。 */
enum class AutoLockDelay(val millis: Long, val label: String) {
    IMMEDIATE(0L, "立即"),
    SEC_15(15_000L, "15 秒"),
    SEC_30(30_000L, "30 秒"),
    MIN_1(60_000L, "1 分钟"),
    MIN_5(300_000L, "5 分钟"),
    NEVER(-1L, "从不")
}

/** 自动同步周期。 */
enum class SyncInterval(val minutes: Long, val label: String) {
    OFF(0L, "关闭"),
    MIN_15(15L, "15 分钟"),
    HOUR_1(60L, "1 小时"),
    HOUR_6(360L, "6 小时"),
    DAY_1(1440L, "1 天")
}

/** 同步后端类型。 */
/** 主题模式（自动/浅色/深色）。 */
enum class ThemeMode(val label: String) { AUTO("自动"), LIGHT("浅色"), DARK("深色") }

/** 列表布局密度（大/默认/紧凑）。 */
enum class ListDensity(val label: String, val rowPadding: Int, val codeSize: Int, val iconSize: Int) {
    LARGE("大", 20, 34, 46),
    DEFAULT("默认", 14, 28, 38),
    COMPACT("紧凑", 8, 22, 30)
}

enum class SyncProviderType(val label: String) {
    NONE("未配置"),
    WEBDAV("WebDAV"),
    GIST("GitHub Gist"),
    GITHUB("GitHub 仓库"),
    GITEE("Gitee"),
    GOOGLE_DRIVE("Google Drive"),
    ONEDRIVE("OneDrive")
}

/**
 * 一个 WebDAV 服务器配置。可配置多个，各自独立参与同步。
 *
 * [id] 新建时生成、之后稳定不变（参与同步的开关与云端删除都按它定位）；
 * [fileName] 决定云端文件名，可在同一目录里放多份互不干扰的备份。
 *
 * 是否参与同步统一由 [AppSettings.disabledProviders] 里的 `WEBDAV:<id>` 决定，
 * 配置本身不再带 enabled 标志。
 */
@Serializable
data class WebDavServer(
    val id: String,
    val label: String = "",
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val fileName: String = "tessera.vault"
)

/** 展示名：优先用备注名，否则从地址派生（主机 + 完整路径）。 */
val WebDavServer.displayLabel: String
    get() {
        if (label.isNotBlank()) return label.trim()
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) return "WebDAV"
        val afterScheme = trimmed.substringAfter("://")
        val host = afterScheme.substringBefore("/")
        val path = afterScheme.substringAfter("/", "").trim('/')
        return if (path.isBlank()) host else host + "/" + path
    }

data class AppSettings(
    val codesVisible: Boolean = true,
    /** 剩余秒数 <= 该阈值时显示下一个验证码。 */
    val nextTokenThresholdSeconds: Int = 5,
    val allowScreenshots: Boolean = false,
    val autoLockDelayMillis: Long = AutoLockDelay.SEC_30.millis,
    val autoLockDelayName: String = AutoLockDelay.SEC_30.name,
    val autoLockEnabled: Boolean = true,
    val biometricEnabled: Boolean = true,
    val trashRetentionDays: Int = 30,
    val clearClipboardSeconds: Int = 30,
    val dynamicColor: Boolean = true,
    val themeMode: String = ThemeMode.AUTO.name,
    val listDensity: String = ListDensity.DEFAULT.name,
    val syncIntervalMinutes: Long = SyncInterval.OFF.minutes,
    val syncIntervalName: String = SyncInterval.OFF.name,
    /**
     * 不参与同步的「已绑定后端」键集合。
     *
     * 键的取值：单实例后端用 `SyncProviderType.name`，WebDAV 用 `WEBDAV:<服务器 id>`。
     * 空集合 = 所有已绑定的后端都参与同步（新绑定的后端默认参与）。
     */
    val disabledProviders: Set<String> = emptySet(),
    /** 同步总开关：关闭后不再自动同步（冷启动、账号变更、后台周期），手动同步仍可用。 */
    val syncEnabled: Boolean = true,
    val lastSyncAt: Long = 0L,
    val lastSyncStatus: String = "尚未同步",
    val lastSyncPushed: Int = 0,
    val lastSyncPulled: Int = 0,
    /** 已配置的 WebDAV 服务器列表（可多个，各自启用）。 */
    val webdavServers: List<WebDavServer> = emptyList(),
    val githubOwnerRepo: String = "",
    val githubPath: String = "tessera.vault",
    val gistId: String = "",
    val githubToken: String = "",
    val giteeOwnerRepo: String = "",
    val giteePath: String = "tessera.vault",
    val giteeToken: String = "",
    val googleAccount: String = "",
    val gistToken: String = ""
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val CODES_VISIBLE = booleanPreferencesKey("codes_visible")
        val NEXT_TOKEN_THRESHOLD = intPreferencesKey("next_token_threshold")
        val ALLOW_SCREENSHOTS = booleanPreferencesKey("allow_screenshots")
        val AUTO_LOCK_DELAY = longPreferencesKey("auto_lock_delay")
        val AUTO_LOCK_DELAY_NAME = stringPreferencesKey("auto_lock_delay_name")
        val AUTO_LOCK_ENABLED = booleanPreferencesKey("auto_lock_enabled")
        val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
        val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
        val CLEAR_CLIPBOARD_SECONDS = intPreferencesKey("clear_clipboard_seconds")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LIST_DENSITY = stringPreferencesKey("list_density")
        val SYNC_INTERVAL_MINUTES = longPreferencesKey("sync_interval_minutes")
        val SYNC_INTERVAL_NAME = stringPreferencesKey("sync_interval_name")
        /** 旧版「当前选中的后端」；仅用于把老配置派生迁移成 [Keys.DISABLED_PROVIDERS]。 */
        val LEGACY_SYNC_PROVIDER = stringPreferencesKey("sync_provider")
        /** 旧版「同时同步」勾选集合；用途同上，不再写入。 */
        val LEGACY_ENABLED_PROVIDERS = stringSetPreferencesKey("enabled_providers")
        val DISABLED_PROVIDERS = stringSetPreferencesKey("disabled_providers")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_SYNC_STATUS = stringPreferencesKey("last_sync_status")
        val LAST_SYNC_PUSHED = intPreferencesKey("last_sync_pushed")
        val LAST_SYNC_PULLED = intPreferencesKey("last_sync_pulled")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_SERVERS = stringPreferencesKey("webdav_servers")
        val GITHUB_OWNER_REPO = stringPreferencesKey("github_owner_repo")
        val GITHUB_PATH = stringPreferencesKey("github_path")
        val GIST_ID = stringPreferencesKey("gist_id")
        val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val GITEE_OWNER_REPO = stringPreferencesKey("gitee_owner_repo")
        val GITEE_PATH = stringPreferencesKey("gitee_path")
        val GITEE_TOKEN = stringPreferencesKey("gitee_token")
        val GOOGLE_ACCOUNT = stringPreferencesKey("google_account")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val GIST_TOKEN = stringPreferencesKey("gist_token")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { p ->
        AppSettings(
            codesVisible = p[Keys.CODES_VISIBLE] ?: true,
            nextTokenThresholdSeconds = p[Keys.NEXT_TOKEN_THRESHOLD] ?: 5,
            allowScreenshots = p[Keys.ALLOW_SCREENSHOTS] ?: false,
            autoLockDelayMillis = p[Keys.AUTO_LOCK_DELAY] ?: AutoLockDelay.SEC_30.millis,
            autoLockDelayName = p[Keys.AUTO_LOCK_DELAY_NAME] ?: AutoLockDelay.SEC_30.name,
            autoLockEnabled = p[Keys.AUTO_LOCK_ENABLED] ?: true,
            biometricEnabled = p[Keys.BIOMETRIC_ENABLED] ?: true,
            trashRetentionDays = p[Keys.TRASH_RETENTION_DAYS] ?: 30,
            clearClipboardSeconds = p[Keys.CLEAR_CLIPBOARD_SECONDS] ?: 30,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: true,
            themeMode = p[Keys.THEME_MODE] ?: ThemeMode.AUTO.name,
            listDensity = p[Keys.LIST_DENSITY] ?: ListDensity.DEFAULT.name,
            syncIntervalMinutes = p[Keys.SYNC_INTERVAL_MINUTES] ?: SyncInterval.OFF.minutes,
            syncIntervalName = p[Keys.SYNC_INTERVAL_NAME] ?: SyncInterval.OFF.name,
            disabledProviders = readDisabledProviders(p),
            syncEnabled = p[Keys.SYNC_ENABLED] ?: true,
            lastSyncAt = p[Keys.LAST_SYNC_AT] ?: 0L,
            lastSyncStatus = p[Keys.LAST_SYNC_STATUS] ?: "尚未同步",
            lastSyncPushed = p[Keys.LAST_SYNC_PUSHED] ?: 0,
            lastSyncPulled = p[Keys.LAST_SYNC_PULLED] ?: 0,
            webdavServers = readWebDavServers(p),
            githubOwnerRepo = p[Keys.GITHUB_OWNER_REPO] ?: "",
            githubPath = p[Keys.GITHUB_PATH] ?: "tessera.vault",
            gistId = p[Keys.GIST_ID] ?: "",
            githubToken = p[Keys.GITHUB_TOKEN] ?: "",
            giteeOwnerRepo = p[Keys.GITEE_OWNER_REPO] ?: "",
            giteePath = p[Keys.GITEE_PATH] ?: "tessera.vault",
            giteeToken = p[Keys.GITEE_TOKEN] ?: "",
            googleAccount = p[Keys.GOOGLE_ACCOUNT] ?: "",
            gistToken = p[Keys.GIST_TOKEN] ?: ""
        )
    }

    suspend fun setCodesVisible(value: Boolean) = edit { it[Keys.CODES_VISIBLE] = value }
    suspend fun setNextTokenThreshold(seconds: Int) = edit { it[Keys.NEXT_TOKEN_THRESHOLD] = seconds }
    suspend fun setAllowScreenshots(value: Boolean) = edit { it[Keys.ALLOW_SCREENSHOTS] = value }
    suspend fun setAutoLockDelay(delay: AutoLockDelay) = edit {
        it[Keys.AUTO_LOCK_DELAY] = delay.millis
        it[Keys.AUTO_LOCK_DELAY_NAME] = delay.name
    }
    suspend fun setAutoLockEnabled(value: Boolean) = edit { it[Keys.AUTO_LOCK_ENABLED] = value }
    suspend fun setBiometricEnabled(value: Boolean) = edit { it[Keys.BIOMETRIC_ENABLED] = value }
    suspend fun setTrashRetentionDays(days: Int) = edit { it[Keys.TRASH_RETENTION_DAYS] = days }
    suspend fun setClearClipboardSeconds(seconds: Int) = edit { it[Keys.CLEAR_CLIPBOARD_SECONDS] = seconds }
    suspend fun setDynamicColor(value: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = value }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setListDensity(density: ListDensity) = edit { it[Keys.LIST_DENSITY] = density.name }
    suspend fun setSyncInterval(interval: SyncInterval) = edit {
        it[Keys.SYNC_INTERVAL_MINUTES] = interval.minutes
        it[Keys.SYNC_INTERVAL_NAME] = interval.name
    }
    /** 记录不参与同步的后端键；一旦写入即成为唯一事实来源，不再回退到旧字段派生。 */
    suspend fun setDisabledProviders(names: Set<String>) = edit { it[Keys.DISABLED_PROVIDERS] = names }
    suspend fun setSyncEnabled(value: Boolean) = edit { it[Keys.SYNC_ENABLED] = value }
    /** 保存整个 WebDAV 服务器列表；首次写入时清理旧版单条配置的键，避免迁移后又被读出来。 */
    suspend fun setWebDavServers(servers: List<WebDavServer>) = edit {
        it[Keys.WEBDAV_SERVERS] = configJson.encodeToString(servers)
        it.remove(Keys.WEBDAV_URL)
        it.remove(Keys.WEBDAV_USER)
        it.remove(Keys.WEBDAV_PASSWORD)
    }
    suspend fun setGitHub(ownerRepo: String, path: String) = edit {
        it[Keys.GITHUB_OWNER_REPO] = ownerRepo
        it[Keys.GITHUB_PATH] = path
    }
    suspend fun setGistId(id: String) = edit { it[Keys.GIST_ID] = id }
    suspend fun setGitHubToken(token: String) = edit { it[Keys.GITHUB_TOKEN] = token }
    suspend fun setGitee(ownerRepo: String, path: String, token: String) = edit {
        it[Keys.GITEE_OWNER_REPO] = ownerRepo
        it[Keys.GITEE_PATH] = path
        it[Keys.GITEE_TOKEN] = token
    }
    suspend fun setGoogleAccount(account: String) = edit { it[Keys.GOOGLE_ACCOUNT] = account }
    suspend fun setGistToken(token: String) = edit { it[Keys.GIST_TOKEN] = token }
    suspend fun setSyncStatus(status: String, pushed: Int, pulled: Int, at: Long = System.currentTimeMillis()) = edit {
        it[Keys.LAST_SYNC_STATUS] = status
        it[Keys.LAST_SYNC_PUSHED] = pushed
        it[Keys.LAST_SYNC_PULLED] = pulled
        it[Keys.LAST_SYNC_AT] = at
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    /**
     * 读取 WebDAV 服务器列表。
     *
     * 首次读取（新键不存在）时把旧版的单条配置迁移成列表中的第一个服务器；
     * 「当时是否参与同步」由 [readDisabledProviders] 一并迁移进停用集合。
     */
    private fun readWebDavServers(p: Preferences): List<WebDavServer> {
        p[Keys.WEBDAV_SERVERS]?.let { raw ->
            return runCatching { configJson.decodeFromString<List<WebDavServer>>(raw) }.getOrDefault(emptyList())
        }
        val legacyUrl = p[Keys.WEBDAV_URL] ?: ""
        if (legacyUrl.isBlank()) return emptyList()
        return listOf(
            WebDavServer(
                id = LEGACY_WEBDAV_ID,
                url = legacyUrl,
                user = p[Keys.WEBDAV_USER] ?: "",
                password = p[Keys.WEBDAV_PASSWORD] ?: ""
            )
        )
    }

    /**
     * 读取不参与同步的后端键集合。
     *
     * 新键存在时以它为准；否则（首次升级到新版）由旧字段一次性派生：
     * 参与过同步的后端 =「当前选中的后端」∪「同时同步」勾选集合，其余单实例后端记为停用。
     * 全新安装（两个旧字段都不存在）不派生，保持"绑定即参与同步"。
     */
    private fun readDisabledProviders(p: Preferences): Set<String> {
        p[Keys.DISABLED_PROVIDERS]?.let { return it }
        val legacyEnabled = p[Keys.LEGACY_ENABLED_PROVIDERS]
        val legacyProvider = p[Keys.LEGACY_SYNC_PROVIDER]
        if (legacyEnabled == null && legacyProvider == null) return emptySet()
        val disabled = deriveDisabledProviders(legacyEnabled ?: emptySet(), legacyProvider ?: "").toMutableSet()
        // 旧版单条 WebDAV 配置当时未被选中 → 迁移出的服务器默认不参与同步
        if ((p[Keys.WEBDAV_URL] ?: "").isNotBlank() && legacyProvider != SyncProviderType.WEBDAV.name) {
            disabled += webdavBindingKey(LEGACY_WEBDAV_ID)
        }
        return disabled
    }

    private companion object {
        /** 旧版单条 WebDAV 配置迁移后的固定 id。 */
        const val LEGACY_WEBDAV_ID = "legacy"
    }
}

/** 单实例后端（WebDAV 之外）的展示顺序，也是「添加同步后端」列表的顺序。 */
val SINGLETON_PROVIDER_ORDER = listOf(
    SyncProviderType.GOOGLE_DRIVE,
    SyncProviderType.ONEDRIVE,
    SyncProviderType.GITHUB,
    SyncProviderType.GIST,
    SyncProviderType.GITEE
)

/** WebDAV 是多实例后端，每个服务器的停用键都带这个前缀。 */
const val WEBDAV_BINDING_PREFIX = "WEBDAV:"

fun webdavBindingKey(serverId: String): String = WEBDAV_BINDING_PREFIX + serverId

/**
 * 旧版配置的一次性派生：参与同步的后端 =「当前选中」∪「同时同步」勾选，
 * 其余单实例后端都记为停用（保持升级前后同步范围一致）。
 */
internal fun deriveDisabledProviders(legacyEnabled: Set<String>, legacyProvider: String): Set<String> {
    val active = legacyEnabled + legacyProvider
    return SINGLETON_PROVIDER_ORDER.filterNot { it.name in active }.mapTo(mutableSetOf()) { it.name }
}
