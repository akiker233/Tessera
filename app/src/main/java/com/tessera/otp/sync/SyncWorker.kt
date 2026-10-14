package com.tessera.otp.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tessera.otp.TesseraApp
import com.tessera.otp.data.prefs.AppSettings
import com.tessera.otp.data.prefs.SINGLETON_PROVIDER_ORDER
import com.tessera.otp.data.prefs.SyncInterval
import com.tessera.otp.data.prefs.SyncProviderType
import com.tessera.otp.data.prefs.displayLabel
import com.tessera.otp.data.prefs.webdavBindingKey
import com.tessera.otp.di.AppContainer
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** 一个参与同步的后端实例。WebDAV 可有多个，用 [webdavServerId] 区分具体服务器。 */
data class SyncTarget(
    val type: SyncProviderType,
    val webdavServerId: String? = null
)

/** 根据当前设置构造同步后端；配置不完整时抛 SyncException（由 UI 显示原因）。 */
object SyncProviderFactory {

    /** 这个后端实例在「停用集合」里的键：WebDAV 每台服务器一条，其余每种一条。 */
    fun bindingKey(type: SyncProviderType, webdavServerId: String? = null): String =
        if (type == SyncProviderType.WEBDAV) webdavBindingKey(webdavServerId.orEmpty()) else type.name

    /**
     * 是否「已绑定」——凭据是否填齐。只有绑定的后端才会出现在云同步页、才会被同步。
     *
     * OneDrive 的连接状态存在它自己的 SharedPreferences 里，故由调用方传入。
     */
    fun isBound(settings: AppSettings, type: SyncProviderType, oneDriveConnected: Boolean): Boolean =
        when (type) {
            SyncProviderType.WEBDAV -> settings.webdavServers.isNotEmpty()
            SyncProviderType.GIST -> settings.gistToken.isNotBlank()
            SyncProviderType.GITHUB -> settings.githubToken.isNotBlank() && hasOwnerRepo(settings.githubOwnerRepo)
            SyncProviderType.GITEE -> settings.giteeToken.isNotBlank() && hasOwnerRepo(settings.giteeOwnerRepo)
            SyncProviderType.GOOGLE_DRIVE -> settings.googleAccount.isNotBlank()
            SyncProviderType.ONEDRIVE -> oneDriveConnected
            SyncProviderType.NONE -> false
        }

    /**
     * 汇总参与同步的后端实例：**已绑定且未被停用**。
     *
     * WebDAV 每台服务器各一条（键 `WEBDAV:<id>`），其余后端每种一条（键 = 枚举名）。
     * 未绑定的后端不会进来，否则同步时会冒出一串「请填写令牌」的配置错误。
     */
    fun targets(settings: AppSettings, oneDriveConnected: Boolean): List<SyncTarget> {
        val disabled = settings.disabledProviders
        val result = mutableListOf<SyncTarget>()
        settings.webdavServers.forEach { server ->
            if (bindingKey(SyncProviderType.WEBDAV, server.id) !in disabled) {
                result += SyncTarget(SyncProviderType.WEBDAV, server.id)
            }
        }
        SINGLETON_PROVIDER_ORDER.forEach { type ->
            if (type.name !in disabled && isBound(settings, type, oneDriveConnected)) result += SyncTarget(type)
        }
        return result
    }

    /**
     * 批量构造后端：返回（可用后端，配置错误说明）。
     *
     * 关键：单个后端"配置不全"（如未填令牌）**不应拖垮其它后端**——
     * 否则勾了两个后端、其中一个没配好，另一个本来能同步的也会整个失败。
     */
    suspend fun createAll(
        context: Context,
        settings: AppSettings,
        targets: List<SyncTarget>,
        onGistCreated: (String) -> Unit = {}
    ): Pair<List<SyncProvider>, List<String>> {
        val providers = mutableListOf<SyncProvider>()
        val errors = mutableListOf<String>()
        for (target in targets) {
            try {
                providers += createFor(context, settings, target, onGistCreated)
            } catch (t: Throwable) {
                errors += target.label(settings) + "：" + (t.message ?: "配置不完整")
            }
        }
        return providers to errors
    }

    /** 按指定后端构造（多后端同时同步时逐个调用）。 */
    suspend fun createFor(
        context: Context,
        settings: AppSettings,
        target: SyncTarget,
        onGistCreated: (String) -> Unit = {}
    ): SyncProvider =
        when (target.type) {
            SyncProviderType.WEBDAV -> {
                val server = settings.webdavServers.firstOrNull { it.id == target.webdavServerId }
                    ?: throw SyncException("该 WebDAV 服务器已被移除")
                if (server.url.isBlank()) throw SyncException("请先填写 WebDAV 地址")
                WebDavProvider(
                    baseUrl = server.url,
                    user = server.user,
                    password = server.password,
                    fileName = server.fileName.ifBlank { "tessera.vault" },
                    displayName = "WebDAV（" + server.displayLabel + "）"
                )
            }
            SyncProviderType.GIST -> {
                if (settings.gistToken.isBlank()) throw SyncException("请先填写 GitHub 令牌")
                GistProvider(
                    token = settings.gistToken,
                    gistId = settings.gistId.ifBlank { null },
                    onGistCreated = onGistCreated
                )
            }
            SyncProviderType.GITEE -> {
                val parts = settings.giteeOwnerRepo.split("/")
                if (settings.giteeToken.isBlank() || parts.size != 2) {
                    throw SyncException("请填写 Gitee 令牌与 owner/repo")
                }
                GiteeProvider(settings.giteeToken, parts[0], parts[1], settings.giteePath)
            }
            SyncProviderType.GITHUB -> {
                val parts = settings.githubOwnerRepo.split("/")
                if (settings.githubToken.isBlank() || parts.size != 2) {
                    throw SyncException("请填写 GitHub 令牌与 owner/repo")
                }
                GitHubRepoProvider(settings.githubToken, parts[0], parts[1], settings.githubPath)
            }
            SyncProviderType.GOOGLE_DRIVE -> GoogleDriveProvider(context, tokenProvider = { GoogleDriveAuth.accessToken(context) })
            SyncProviderType.ONEDRIVE -> {
                // 令牌由 OneDriveAuth 管理（自动续期）；未登录时给出明确提示
                val token = OneDriveAuth.accessToken(context)
                    ?: throw SyncException("尚未连接 OneDrive：请先在「云同步」里登录微软账号")
                OneDriveProvider(token)
            }
            SyncProviderType.NONE -> throw SyncException("尚未选择同步方式")
        }

    /** 出错时用于展示的后端名（WebDAV 带服务器备注，便于区分多个服务器）。 */
    private fun SyncTarget.label(settings: AppSettings): String =
        if (type == SyncProviderType.WEBDAV) {
            "WebDAV（" + (settings.webdavServers.firstOrNull { it.id == webdavServerId }?.displayLabel ?: "已移除") + "）"
        } else type.label
}

/** `owner/repo` 形式：恰好一个斜杠。 */
private fun hasOwnerRepo(value: String): Boolean = value.count { it == '/' } == 1

/** 周期自动同步（默认关闭；WorkManager 的最小周期是 15 分钟）。 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TesseraApp).container
        val settings = container.settings.settings.first()
        val store = container.syncPassphrase()
        // 同步总开关关闭时，后台周期任务直接跳过（保留周期设置，重新打开立即可用）
        if (!settings.syncEnabled) return Result.success()
        if (store.passphrase.isNullOrBlank()) return Result.success()

        return try {
            val (providers, setupErrors) = SyncProviderFactory.createAll(
                applicationContext,
                settings,
                SyncProviderFactory.targets(settings, OneDriveAuth.isConnected(applicationContext))
            ) { id ->
                kotlinx.coroutines.runBlocking { container.settings.setGistId(id) }
            }
            if (providers.isEmpty()) {
                container.settings.setSyncStatus(
                    "自动同步失败：所有已配置的同步后端都不可用（" + setupErrors.joinToString("、") + "）",
                    0, 0, settings.lastSyncAt
                )
                return Result.retry()
            }
            val repository = container.accountRepository()
            val engine = SyncEngine(repository, store)
            val base = engine.syncAll(providers)
            val outcome = if (setupErrors.isEmpty()) base
            else base.copy(success = false, message = base.message + "；失败：" + setupErrors.joinToString("、"))
            // 只有成功才刷新"上次成功"时间
            container.settings.setSyncStatus(
                outcome.message, outcome.pushed, outcome.pulled,
                if (outcome.success) outcome.lastSyncAt else settings.lastSyncAt
            )
            if (outcome.success) Result.success() else Result.retry()
        } catch (t: Throwable) {
            container.settings.setSyncStatus("自动同步失败：" + (t.message ?: "未知错误"), 0, 0)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "tessera-periodic-sync"

        /** 按设置注册或取消周期任务。 */
        fun schedule(context: Context, interval: SyncInterval) {
            val manager = WorkManager.getInstance(context)
            if (interval == SyncInterval.OFF) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SyncWorker>(interval.minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
