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
import com.tessera.otp.data.prefs.gistDisplayLabel
import com.tessera.otp.data.prefs.giteeDisplayLabel
import com.tessera.otp.data.prefs.githubDisplayLabel
import com.tessera.otp.data.prefs.googleDriveAccountLabel
import com.tessera.otp.data.prefs.googleDriveBindingKey
import com.tessera.otp.data.prefs.oneDriveAccountLabel
import com.tessera.otp.data.prefs.oneDriveBindingKey
import com.tessera.otp.data.prefs.s3BindingKey
import com.tessera.otp.data.prefs.webdavBindingKey
import com.tessera.otp.di.AppContainer
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * 一个参与同步的后端实例。
 *
 * 单实例后端（GitHub / Gist / Gitee）每种只有一条、不带 [instanceId]；
 * 多实例后端（WebDAV / S3 / Google Drive / OneDrive）用 [instanceId] 区分具体服务器或账号。
 */
data class SyncTarget(
    val type: SyncProviderType,
    val instanceId: String? = null
)

/** 根据当前设置构造同步后端；配置不完整时抛 SyncException（由 UI 显示原因）。 */
object SyncProviderFactory {

    /** 这个后端实例在「停用集合」里的键：多实例带 `<类型>:<实例 id>`，其余每种一个枚举名。 */
    fun bindingKey(type: SyncProviderType, instanceId: String? = null): String = when (type) {
        SyncProviderType.WEBDAV -> webdavBindingKey(instanceId.orEmpty())
        SyncProviderType.S3 -> s3BindingKey(instanceId.orEmpty())
        SyncProviderType.GOOGLE_DRIVE -> googleDriveBindingKey(instanceId.orEmpty())
        SyncProviderType.ONEDRIVE -> oneDriveBindingKey(instanceId.orEmpty())
        else -> type.name
    }

    fun bindingKey(target: SyncTarget): String = bindingKey(target.type, target.instanceId)

    /** 单实例后端的凭据是否填齐（多实例的凭据就是它们各自的条目，不在这里判）。 */
    fun hasCredentials(settings: AppSettings, type: SyncProviderType): Boolean = when (type) {
        SyncProviderType.GIST -> settings.gistToken.isNotBlank()
        SyncProviderType.GITHUB -> settings.githubToken.isNotBlank() && hasOwnerRepo(settings.githubOwnerRepo)
        SyncProviderType.GITEE -> settings.giteeToken.isNotBlank() && hasOwnerRepo(settings.giteeOwnerRepo)
        else -> false
    }

    /**
     * 已绑定的后端实例（凭据齐全的那些，**不看**停用开关）。
     *
     * OneDrive 的令牌存在它自己的 SharedPreferences 里（应用可枚举已登录账号），
     * 故账号列表由调用方传入。
     */
    fun boundTargets(settings: AppSettings, oneDriveAccountIds: List<String>): List<SyncTarget> {
        val result = mutableListOf<SyncTarget>()
        settings.webdavServers.forEach { result += SyncTarget(SyncProviderType.WEBDAV, it.id) }
        settings.s3Accounts.forEach { result += SyncTarget(SyncProviderType.S3, it.id) }
        settings.googleAccounts.forEach { result += SyncTarget(SyncProviderType.GOOGLE_DRIVE, it) }
        oneDriveAccountIds.forEach { result += SyncTarget(SyncProviderType.ONEDRIVE, it) }
        SINGLETON_PROVIDER_ORDER.forEach { if (hasCredentials(settings, it)) result += SyncTarget(it) }
        return result
    }

    /**
     * 汇总参与同步的后端实例：**已绑定且未被停用**。
     *
     * 未绑定的后端不会进来，否则同步时会冒出一串「请填写令牌」的配置错误。
     */
    fun targets(settings: AppSettings, oneDriveAccountIds: List<String>): List<SyncTarget> {
        val disabled = settings.disabledProviders
        return boundTargets(settings, oneDriveAccountIds).filter { bindingKey(it) !in disabled }
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
                val server = settings.webdavServers.firstOrNull { it.id == target.instanceId }
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
            SyncProviderType.S3 -> {
                val account = settings.s3Accounts.firstOrNull { it.id == target.instanceId }
                    ?: throw SyncException("该 S3 配置已被移除")
                if (account.endpoint.isBlank() || account.bucket.isBlank()) {
                    throw SyncException("请先填写 S3 的 Endpoint 与 Bucket")
                }
                if (account.accessKey.isBlank() || account.secretKey.isBlank()) {
                    throw SyncException("请先填写 S3 的 Access Key 与 Secret Key")
                }
                S3Provider(
                    endpoint = account.endpoint,
                    region = account.region,
                    bucket = account.bucket,
                    accessKey = account.accessKey,
                    secretKey = account.secretKey,
                    sessionToken = account.sessionToken,
                    prefix = account.prefix,
                    fileName = account.fileName.ifBlank { "tessera.vault" },
                    forcePathStyle = account.forcePathStyle,
                    displayName = "S3（" + account.displayLabel + "）"
                )
            }
            SyncProviderType.GIST -> {
                if (settings.gistToken.isBlank()) throw SyncException("请先填写 GitHub 令牌")
                GistProvider(
                    token = settings.gistToken,
                    gistId = settings.gistId.ifBlank { null },
                    onGistCreated = onGistCreated,
                    displayName = settings.gistDisplayLabel
                )
            }
            SyncProviderType.GITEE -> {
                val parts = settings.giteeOwnerRepo.split("/")
                if (settings.giteeToken.isBlank() || parts.size != 2) {
                    throw SyncException("请填写 Gitee 令牌与 owner/repo")
                }
                GiteeProvider(
                    settings.giteeToken, parts[0], parts[1], settings.giteePath,
                    displayName = settings.giteeDisplayLabel
                )
            }
            SyncProviderType.GITHUB -> {
                val parts = settings.githubOwnerRepo.split("/")
                if (settings.githubToken.isBlank() || parts.size != 2) {
                    throw SyncException("请填写 GitHub 令牌与 owner/repo")
                }
                GitHubRepoProvider(
                    settings.githubToken, parts[0], parts[1], settings.githubPath,
                    displayName = settings.githubDisplayLabel
                )
            }
            SyncProviderType.GOOGLE_DRIVE -> {
                val account = target.instanceId.orEmpty()
                GoogleDriveProvider(
                    context = context,
                    tokenProvider = { GoogleDriveAuth.accessToken(context, account) },
                    displayName = "Google Drive（" + settings.googleDriveAccountLabel(account) + "）"
                )
            }
            SyncProviderType.ONEDRIVE -> {
                val account = target.instanceId.orEmpty()
                // 令牌由 OneDriveAuth 管理（自动续期）；未登录时给出明确提示
                val token = OneDriveAuth.accessToken(context, account)
                    ?: throw SyncException("尚未连接 OneDrive（" + settings.oneDriveAccountLabel(account) + "）：请先在「云同步」里登录微软账号")
                OneDriveProvider(token, displayName = "OneDrive（" + settings.oneDriveAccountLabel(account) + "）")
            }
            SyncProviderType.NONE -> throw SyncException("尚未选择同步方式")
        }

    /** 出错时用于展示的后端名（多实例带实例名，便于区分同一后端的多个配置）。 */
    private fun SyncTarget.label(settings: AppSettings): String = when (type) {
        SyncProviderType.WEBDAV ->
            "WebDAV（" + (settings.webdavServers.firstOrNull { it.id == instanceId }?.displayLabel ?: "已移除") + "）"
        SyncProviderType.S3 ->
            "S3（" + (settings.s3Accounts.firstOrNull { it.id == instanceId }?.displayLabel ?: "已移除") + "）"
        SyncProviderType.GOOGLE_DRIVE ->
            "Google Drive（" + settings.googleDriveAccountLabel(instanceId.orEmpty()) + "）"
        SyncProviderType.ONEDRIVE ->
            "OneDrive（" + settings.oneDriveAccountLabel(instanceId.orEmpty()) + "）"
        SyncProviderType.GITHUB -> settings.githubDisplayLabel
        SyncProviderType.GIST -> settings.gistDisplayLabel
        SyncProviderType.GITEE -> settings.giteeDisplayLabel
        SyncProviderType.NONE -> type.label
    }
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
                SyncProviderFactory.targets(settings, OneDriveAuth.accountIds(applicationContext))
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
