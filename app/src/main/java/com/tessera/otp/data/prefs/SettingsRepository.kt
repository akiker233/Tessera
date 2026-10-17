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
    S3("S3 对象存储"),
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

/**
 * 一条 S3 兼容对象存储配置（AWS S3 / Cloudflare R2 / MinIO / 阿里云 OSS / 腾讯云 COS …）。
 *
 * [endpoint] 只填到主机（如 `https://s3.us-east-1.amazonaws.com`、`http://192.168.1.5:9000`），
 * 桶名单独填；MinIO 一类不支持虚拟主机风格的必须打开 [forcePathStyle]。
 * [prefix] 是桶内的目录前缀，[fileName] 决定对象名，同一桶里可放多份互不干扰的备份。
 */
@Serializable
data class S3Account(
    val id: String,
    val label: String = "",
    val endpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val sessionToken: String = "",
    val prefix: String = "",
    val fileName: String = "tessera.vault",
    val forcePathStyle: Boolean = false
)

/** 展示名：优先用备注名，否则「桶名 · 主机」。 */
val S3Account.displayLabel: String
    get() {
        if (label.isNotBlank()) return label.trim()
        val raw = endpoint.trim().trimEnd('/')
        val host = raw.substringAfter("://", raw).substringBefore("/")
        return when {
            bucket.isBlank() && host.isBlank() -> "S3"
            bucket.isBlank() -> host
            host.isBlank() -> bucket
            else -> bucket + " · " + host
        }
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
    /** 首页已收起的分组 key（分类 id，或「未分类」的固定 key）：重启后保持原状。 */
    val collapsedGroups: Set<String> = emptySet(),
    /**
     * 首页「未分类」分组的位置锚点：null = 从未拖过（固定最后）、"" = 排最前，
     * 其余值 = 某个分类 id，表示紧跟在该分类之后。空分类也参与定位。
     */
    val ungroupedGroupAnchor: String? = null,
    /**
     * 不参与同步的「已绑定后端」键集合。
     *
     * 键的取值：单实例后端用 `SyncProviderType.name`；多实例后端用 `<类型>:<实例 id>`
     * ——WebDAV 是服务器 id（[webdavBindingKey]），Google Drive / OneDrive 是账号邮箱
     * （[googleDriveBindingKey]、[oneDriveBindingKey]）。
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
    /** 已配置的 S3 兼容对象存储列表（可多个，各自启用）。 */
    val s3Accounts: List<S3Account> = emptyList(),
    /**
     * 已绑定的 Google Drive 账号（邮箱）。可多个，各自独立同步一份备份。
     *
     * 令牌由 Play Services 托管、应用侧无法枚举，因此这里必须自己记录绑定了哪些账号。
     */
    val googleAccounts: List<String> = emptyList(),
    /** Google Drive 每个账号的备注名（账号 id → 备注），用于区分同类多个账号。 */
    val googleAccountLabels: Map<String, String> = emptyMap(),
    /** OneDrive 每个账号的备注名（账号 id → 备注）；账号清单由 `OneDriveAuth` 侧维护。 */
    val oneDriveAccountLabels: Map<String, String> = emptyMap(),
    val githubOwnerRepo: String = "",
    val githubPath: String = "tessera.vault",
    val githubLabel: String = "",
    val gistId: String = "",
    val githubToken: String = "",
    val giteeOwnerRepo: String = "",
    val giteePath: String = "tessera.vault",
    val giteeLabel: String = "",
    val giteeToken: String = "",
    val gistToken: String = "",
    val gistLabel: String = ""
)

/** 单实例后端的展示名：备注名优先，否则用类型名（有备注时写成「GitHub 仓库（家里）」）。 */
private fun singletonDisplayLabel(label: String, type: SyncProviderType): String =
    if (label.isBlank()) type.label else type.label + "（" + label.trim() + "）"

val AppSettings.githubDisplayLabel: String
    get() = singletonDisplayLabel(githubLabel, SyncProviderType.GITHUB)

val AppSettings.gistDisplayLabel: String
    get() = singletonDisplayLabel(gistLabel, SyncProviderType.GIST)

val AppSettings.giteeDisplayLabel: String
    get() = singletonDisplayLabel(giteeLabel, SyncProviderType.GITEE)

/** Google Drive 单个账号的展示名：有备注用备注，否则回落账号邮箱。 */
fun AppSettings.googleDriveAccountLabel(accountId: String): String =
    googleAccountLabels[accountId]?.trim()?.takeIf { it.isNotEmpty() } ?: accountDisplayName(accountId)

/** OneDrive 单个账号的展示名：有备注用备注，否则回落账号名。 */
fun AppSettings.oneDriveAccountLabel(accountId: String): String =
    oneDriveAccountLabels[accountId]?.trim()?.takeIf { it.isNotEmpty() } ?: accountDisplayName(accountId)

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
        val COLLAPSED_GROUPS = stringSetPreferencesKey("collapsed_groups")
        val UNGROUPED_GROUP_ANCHOR = stringPreferencesKey("ungrouped_group_anchor")
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
        val S3_ACCOUNTS = stringPreferencesKey("s3_accounts")
        val GITHUB_OWNER_REPO = stringPreferencesKey("github_owner_repo")
        val GITHUB_PATH = stringPreferencesKey("github_path")
        val GITHUB_LABEL = stringPreferencesKey("github_label")
        val GIST_ID = stringPreferencesKey("gist_id")
        val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        val GITEE_OWNER_REPO = stringPreferencesKey("gitee_owner_repo")
        val GITEE_PATH = stringPreferencesKey("gitee_path")
        val GITEE_LABEL = stringPreferencesKey("gitee_label")
        val GITEE_TOKEN = stringPreferencesKey("gitee_token")
        /** 旧版「Google 是否已授权」标记（内容是常量文案，不含邮箱）；仅用于迁移出账号列表。 */
        val LEGACY_GOOGLE_ACCOUNT = stringPreferencesKey("google_account")
        val GOOGLE_ACCOUNTS = stringPreferencesKey("google_accounts")
        /** 各后端备注名：Google Drive / OneDrive 按账号 id 存成 JSON 映射，其余是单条字符串。 */
        val GOOGLE_ACCOUNT_LABELS = stringPreferencesKey("google_account_labels")
        val ONEDRIVE_ACCOUNT_LABELS = stringPreferencesKey("onedrive_account_labels")
        val SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val GIST_TOKEN = stringPreferencesKey("gist_token")
        val GIST_LABEL = stringPreferencesKey("gist_label")
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
            collapsedGroups = p[Keys.COLLAPSED_GROUPS].orEmpty(),
            ungroupedGroupAnchor = p[Keys.UNGROUPED_GROUP_ANCHOR],
            disabledProviders = readDisabledProviders(p),
            syncEnabled = p[Keys.SYNC_ENABLED] ?: true,
            lastSyncAt = p[Keys.LAST_SYNC_AT] ?: 0L,
            lastSyncStatus = p[Keys.LAST_SYNC_STATUS] ?: "尚未同步",
            lastSyncPushed = p[Keys.LAST_SYNC_PUSHED] ?: 0,
            lastSyncPulled = p[Keys.LAST_SYNC_PULLED] ?: 0,
            webdavServers = readWebDavServers(p),
            s3Accounts = readS3Accounts(p),
            googleAccounts = readGoogleAccounts(p),
            googleAccountLabels = readLabelMap(p[Keys.GOOGLE_ACCOUNT_LABELS]),
            oneDriveAccountLabels = readLabelMap(p[Keys.ONEDRIVE_ACCOUNT_LABELS]),
            githubOwnerRepo = p[Keys.GITHUB_OWNER_REPO] ?: "",
            githubPath = p[Keys.GITHUB_PATH] ?: "tessera.vault",
            githubLabel = p[Keys.GITHUB_LABEL] ?: "",
            gistId = p[Keys.GIST_ID] ?: "",
            githubToken = p[Keys.GITHUB_TOKEN] ?: "",
            giteeOwnerRepo = p[Keys.GITEE_OWNER_REPO] ?: "",
            giteePath = p[Keys.GITEE_PATH] ?: "tessera.vault",
            giteeLabel = p[Keys.GITEE_LABEL] ?: "",
            giteeToken = p[Keys.GITEE_TOKEN] ?: "",
            gistToken = p[Keys.GIST_TOKEN] ?: "",
            gistLabel = p[Keys.GIST_LABEL] ?: ""
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
    /** 保存首页各分组的展开/收起状态（整集合覆盖写入）。 */
    suspend fun setCollapsedGroups(keys: Set<String>) = edit { it[Keys.COLLAPSED_GROUPS] = keys }
    /** 记录首页「未分类」分组的锚点（空串 = 最前，其余为紧邻其前的分类 id）。 */
    suspend fun setUngroupedGroupAnchor(anchor: String) = edit { it[Keys.UNGROUPED_GROUP_ANCHOR] = anchor }
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
    /** 保存整个 S3 存储列表（可多条，各自独立同步）。 */
    suspend fun setS3Accounts(accounts: List<S3Account>) = edit {
        it[Keys.S3_ACCOUNTS] = configJson.encodeToString(accounts)
    }
    suspend fun setGitHub(ownerRepo: String, path: String) = edit {
        it[Keys.GITHUB_OWNER_REPO] = ownerRepo
        it[Keys.GITHUB_PATH] = path
    }
    /** 单实例后端的备注名（空串 = 用类型名展示）。 */
    suspend fun setGitHubLabel(label: String) = edit { it[Keys.GITHUB_LABEL] = label }
    suspend fun setGiteeLabel(label: String) = edit { it[Keys.GITEE_LABEL] = label }
    suspend fun setGistLabel(label: String) = edit { it[Keys.GIST_LABEL] = label }
    /** 保存整个账号备注映射；调用方负责在增删账号时同步增删对应键。 */
    suspend fun setGoogleAccountLabels(labels: Map<String, String>) = edit {
        it[Keys.GOOGLE_ACCOUNT_LABELS] = configJson.encodeToString(labels)
    }
    suspend fun setOneDriveAccountLabels(labels: Map<String, String>) = edit {
        it[Keys.ONEDRIVE_ACCOUNT_LABELS] = configJson.encodeToString(labels)
    }
    suspend fun setGistId(id: String) = edit { it[Keys.GIST_ID] = id }
    suspend fun setGitHubToken(token: String) = edit { it[Keys.GITHUB_TOKEN] = token }
    suspend fun setGitee(ownerRepo: String, path: String, token: String) = edit {
        it[Keys.GITEE_OWNER_REPO] = ownerRepo
        it[Keys.GITEE_PATH] = path
        it[Keys.GITEE_TOKEN] = token
    }
    /** 保存整个 Google 账号列表；首次写入时清掉旧的「已授权」标记，避免迁移后又被读出来。 */
    suspend fun setGoogleAccounts(accounts: List<String>) = edit {
        it[Keys.GOOGLE_ACCOUNTS] = configJson.encodeToString(accounts)
        it.remove(Keys.LEGACY_GOOGLE_ACCOUNT)
    }
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

    /** 读取 S3 存储列表（纯新增的键，没有旧版配置需要迁移）。 */
    private fun readS3Accounts(p: Preferences): List<S3Account> {
        val raw = p[Keys.S3_ACCOUNTS] ?: return emptyList()
        return runCatching { configJson.decodeFromString<List<S3Account>>(raw) }.getOrDefault(emptyList())
    }

    /** 读取「账号 id → 备注名」映射（纯新增的键）。 */
    private fun readLabelMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { configJson.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    /**
     * 读取已绑定的 Google 账号列表。
     *
     * 首次读取（新键不存在）时把旧版的「已授权」标记迁移成一条默认账号记录：
     * 旧版只存了"授权过没有"，没记邮箱，所以用 [LEGACY_ACCOUNT_ID] 占位。
     */
    private fun readGoogleAccounts(p: Preferences): List<String> {
        p[Keys.GOOGLE_ACCOUNTS]?.let { raw ->
            return runCatching { configJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }
        return if ((p[Keys.LEGACY_GOOGLE_ACCOUNT] ?: "").isNotBlank()) listOf(LEGACY_ACCOUNT_ID) else emptyList()
    }

    /**
     * 读取不参与同步的后端键集合。
     *
     * 新键存在时以它为准；否则（首次升级到新版）由旧字段一次性派生：
     * 参与过同步的后端 =「当前选中的后端」∪「同时同步」勾选集合，其余单实例后端记为停用，
     * 多实例后端则只对迁移出来的那一条默认记录生效（旧版每种后端最多一个）。
     * 全新安装（两个旧字段都不存在）不派生，保持"绑定即参与同步"。
     */
    private fun readDisabledProviders(p: Preferences): Set<String> {
        p[Keys.DISABLED_PROVIDERS]?.let { return it }
        val legacyEnabled = p[Keys.LEGACY_ENABLED_PROVIDERS]
        val legacyProvider = p[Keys.LEGACY_SYNC_PROVIDER]
        if (legacyEnabled == null && legacyProvider == null) return emptySet()
        val active = legacyEnabled.orEmpty() + legacyProvider.orEmpty()
        val disabled = deriveDisabledProviders(legacyEnabled.orEmpty(), legacyProvider.orEmpty()).toMutableSet()
        // 旧版单条 WebDAV 配置当时未被选中 → 迁移出的服务器默认不参与同步
        if ((p[Keys.WEBDAV_URL] ?: "").isNotBlank() && SyncProviderType.WEBDAV.name !in active) {
            disabled += webdavBindingKey(LEGACY_WEBDAV_ID)
        }
        // Google / OneDrive 同理：旧版没启用过就不让迁移出来的默认账号参与同步
        if (SyncProviderType.GOOGLE_DRIVE.name !in active) disabled += googleDriveBindingKey(LEGACY_ACCOUNT_ID)
        if (SyncProviderType.ONEDRIVE.name !in active) disabled += oneDriveBindingKey(LEGACY_ACCOUNT_ID)
        return disabled
    }

    private companion object {
        /** 旧版单条 WebDAV 配置迁移后的固定 id。 */
        const val LEGACY_WEBDAV_ID = "legacy"
    }
}

/** 旧版单账号（Google Drive / OneDrive）迁移出的账号 id。 */
const val LEGACY_ACCOUNT_ID = "legacy"

/** 账号 id 的展示名：旧版迁移出来的记录没有邮箱，统一叫「默认账号」。 */
fun accountDisplayName(id: String): String = if (id == LEGACY_ACCOUNT_ID) "默认账号" else id

/**
 * 多实例后端（每种可绑定多个服务器 / 账号），顺序即界面「添加同步后端」里的展示顺序。
 *
 * 它们的停用键是 `<类型>:<实例 id>`；单实例后端则直接用类型名。
 */
val MULTI_INSTANCE_PROVIDER_ORDER = listOf(
    SyncProviderType.WEBDAV,
    SyncProviderType.S3,
    SyncProviderType.GOOGLE_DRIVE,
    SyncProviderType.ONEDRIVE
)

/** 单实例后端（每种只能绑一个）。 */
val SINGLETON_PROVIDER_ORDER = listOf(
    SyncProviderType.GITHUB,
    SyncProviderType.GIST,
    SyncProviderType.GITEE
)

/** 「添加同步后端」里可选服务的顺序（多实例在前）。 */
val ADD_PROVIDER_ORDER = MULTI_INSTANCE_PROVIDER_ORDER + SINGLETON_PROVIDER_ORDER

/** WebDAV 每个服务器一条绑定，键带这个前缀。 */
const val WEBDAV_BINDING_PREFIX = "WEBDAV:"

/** S3 每条存储配置一条绑定，键带这个前缀。 */
const val S3_BINDING_PREFIX = "S3:"

/** Google Drive 每个账号一条绑定，键带这个前缀。 */
const val GOOGLE_DRIVE_BINDING_PREFIX = "GOOGLE_DRIVE:"

/** OneDrive 每个账号一条绑定，键带这个前缀。 */
const val ONEDRIVE_BINDING_PREFIX = "ONEDRIVE:"

fun webdavBindingKey(serverId: String): String = WEBDAV_BINDING_PREFIX + serverId

fun s3BindingKey(accountId: String): String = S3_BINDING_PREFIX + accountId

fun googleDriveBindingKey(accountId: String): String = GOOGLE_DRIVE_BINDING_PREFIX + accountId

fun oneDriveBindingKey(accountId: String): String = ONEDRIVE_BINDING_PREFIX + accountId

/**
 * 旧版配置的一次性派生：参与同步的后端 =「当前选中」∪「同时同步」勾选，
 * 其余单实例后端都记为停用（保持升级前后同步范围一致）。
 */
internal fun deriveDisabledProviders(legacyEnabled: Set<String>, legacyProvider: String): Set<String> {
    val active = legacyEnabled + legacyProvider
    return SINGLETON_PROVIDER_ORDER.filterNot { it.name in active }.mapTo(mutableSetOf()) { it.name }
}
