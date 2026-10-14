package com.tessera.otp.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.tessera.otp.data.prefs.AppSettings
import com.tessera.otp.data.prefs.SINGLETON_PROVIDER_ORDER
import com.tessera.otp.data.prefs.SettingsRepository
import com.tessera.otp.data.prefs.SyncInterval
import com.tessera.otp.data.prefs.SyncProviderType
import com.tessera.otp.data.prefs.WebDavServer
import com.tessera.otp.data.prefs.displayLabel
import com.tessera.otp.data.prefs.webdavBindingKey
import com.tessera.otp.data.repo.AccountRepository
import com.tessera.otp.sync.GoogleDriveAuth
import com.tessera.otp.sync.OneDriveAuth
import com.tessera.otp.sync.SyncCodec
import com.tessera.otp.sync.SyncEngine
import com.tessera.otp.sync.SyncOutcome
import com.tessera.otp.sync.SyncPassphraseStore
import com.tessera.otp.sync.SyncProviderFactory
import com.tessera.otp.sync.SyncTarget
import com.tessera.otp.sync.WebDavProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class SyncUiState(
    val passphraseSet: Boolean = false,
    val passphraseInput: String = "",
    /** 已配置的 WebDAV 服务器列表（可多个，各自启用与文件名）。 */
    val webdavServers: List<WebDavServer> = emptyList(),
    val githubOwnerRepo: String = "",
    val githubPath: String = "tessera.vault",
    val githubToken: String = "",
    val gistToken: String = "",
    val gistId: String = "",
    val giteeOwnerRepo: String = "",
    val giteePath: String = "tessera.vault",
    val giteeToken: String = "",
    val oneDriveConnected: Boolean = false,
    /** 已绑定的后端（顺序即界面顺序）：只有绑定成功的才显示在页面上。 */
    val bindings: List<SyncBinding> = emptyList(),
    /** 已配置（有可用凭据）的后端实例：可在「云端数据」里删除其远端备份。 */
    val configuredTargets: List<CloudTarget> = emptyList(),
    val interval: SyncInterval = SyncInterval.OFF,
    /** 同步总开关：关闭后自动同步（冷启动 / 账号变更 / 后台周期）全部停用，手动同步不受影响。 */
    val syncEnabled: Boolean = true,
    val lastStatus: String = "尚未同步",
    val lastSyncAt: Long = 0L,
    val googleAccount: String = "",
    val running: Boolean = false,
    val message: String? = null
)

/**
 * 云同步页上的一行「已绑定后端」。
 *
 * [key] 同时是停用集合与云端删除定位用的键（WebDAV 为 `WEBDAV:<id>`，其余为枚举名）。
 */
data class SyncBinding(
    val key: String,
    val label: String,
    /** 副标题：账号 / 地址 / 文件名 / 仓库路径，用于区分同类多个实例。 */
    val detail: String,
    val type: SyncProviderType,
    val webdavServerId: String? = null,
    /** 是否参与同步；关闭只是不同步，配置仍然保留。 */
    val active: Boolean = true,
    /** 可编辑（WebDAV 与三个凭据型后端）；OAuth 型只能重新登录。 */
    val editable: Boolean = false
)

/** 「云端数据」里可删除的一个远端后端实例（WebDAV 每个服务器各一条）。 */
data class CloudTarget(
    val key: String,
    val label: String,
    val type: SyncProviderType,
    val webdavServerId: String? = null
)

/**
 * 同步页 ViewModel：手动同步、凭据填写、口令设置与自动同步周期。
 *
 * 所有写操作都会立即持久化（用户不必点"保存"），手动同步则走 [runManualSync]。
 */
class SyncViewModel(
    private val appContext: android.content.Context,
    private val repository: AccountRepository,
    private val settings: SettingsRepository,
    private val passphraseStore: SyncPassphraseStore,
    private val scheduleWorker: (SyncInterval) -> Unit
) : ViewModel() {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val current = settings.settings.first()
            val oneDrive = OneDriveAuth.isConnected(appContext)
            _state.value = _state.value.copy(
                passphraseSet = !passphraseStore.passphrase.isNullOrBlank(),
                webdavServers = current.webdavServers,
                githubOwnerRepo = current.githubOwnerRepo,
                githubPath = current.githubPath,
                githubToken = current.githubToken,
                gistToken = current.gistToken,
                gistId = current.gistId,
                giteeOwnerRepo = current.giteeOwnerRepo,
                giteePath = current.giteePath,
                giteeToken = current.giteeToken,
                oneDriveConnected = oneDrive,
                bindings = bindingsOf(current, oneDrive),
                interval = runCatching { SyncInterval.valueOf(current.syncIntervalName) }.getOrDefault(SyncInterval.OFF),
                syncEnabled = current.syncEnabled,
                lastStatus = current.lastSyncStatus,
                lastSyncAt = current.lastSyncAt,
                googleAccount = current.googleAccount,
                configuredTargets = configuredTargets(current, oneDrive)
            )
        }
    }

    /**
     * 已绑定的后端列表——**绑定（凭据填齐）才会出现在页面上**，未绑定的一律不显示。
     * 「添加同步后端」里能被再次添加的也只有未绑定的单实例后端（WebDAV 可加多台）。
     */
    private fun bindingsOf(s: AppSettings, oneDriveConnected: Boolean): List<SyncBinding> = buildList {
        val disabled = s.disabledProviders
        s.webdavServers.forEach { server ->
            val key = webdavBindingKey(server.id)
            add(
                SyncBinding(
                    key = key,
                    label = server.displayLabel,
                    detail = "WebDAV · " + server.url.ifBlank { "（未填写地址）" } +
                        " · 文件名 " + server.fileName.ifBlank { "tessera.vault" },
                    type = SyncProviderType.WEBDAV,
                    webdavServerId = server.id,
                    active = key !in disabled,
                    editable = true
                )
            )
        }
        SINGLETON_PROVIDER_ORDER.forEach { type ->
            if (!SyncProviderFactory.isBound(s, type, oneDriveConnected)) return@forEach
            add(
                SyncBinding(
                    key = type.name,
                    label = type.label,
                    detail = bindingDetail(s, type),
                    type = type,
                    active = type.name !in disabled,
                    editable = type == SyncProviderType.GITHUB || type == SyncProviderType.GIST ||
                        type == SyncProviderType.GITEE
                )
            )
        }
    }

    private fun bindingDetail(s: AppSettings, type: SyncProviderType): String = when (type) {
        SyncProviderType.GITHUB -> s.githubOwnerRepo + " · " + s.githubPath
        SyncProviderType.GITEE -> s.giteeOwnerRepo + " · " + s.giteePath
        SyncProviderType.GIST -> if (s.gistId.isBlank()) "首次同步会自动创建私有 Gist" else "Gist ID " + s.gistId
        SyncProviderType.GOOGLE_DRIVE -> s.googleAccount
        SyncProviderType.ONEDRIVE -> "已连接微软账号"
        else -> ""
    }

    /**
     * 判定哪些后端「已配置」——只有配好凭据的后端才可能云端有数据、才需要在「云端数据」里列出。
     * WebDAV 每个服务器各一条；Gist 额外要求已有 gistId（没有 Gist 就没有远端数据可删）。
     */
    private fun configuredTargets(s: AppSettings, oneDriveConnected: Boolean): List<CloudTarget> = buildList {
        s.webdavServers.forEach {
            add(CloudTarget(webdavBindingKey(it.id), "WebDAV（" + it.displayLabel + "）", SyncProviderType.WEBDAV, it.id))
        }
        if (s.gistToken.isNotBlank() && s.gistId.isNotBlank()) add(CloudTarget("GIST", "GitHub Gist", SyncProviderType.GIST))
        if (SyncProviderFactory.isBound(s, SyncProviderType.GITHUB, oneDriveConnected)) {
            add(CloudTarget("GITHUB", "GitHub 仓库", SyncProviderType.GITHUB))
        }
        if (SyncProviderFactory.isBound(s, SyncProviderType.GITEE, oneDriveConnected)) {
            add(CloudTarget("GITEE", "Gitee", SyncProviderType.GITEE))
        }
        if (SyncProviderFactory.isBound(s, SyncProviderType.GOOGLE_DRIVE, oneDriveConnected)) {
            add(CloudTarget("GOOGLE_DRIVE", "Google Drive", SyncProviderType.GOOGLE_DRIVE))
        }
        if (SyncProviderFactory.isBound(s, SyncProviderType.ONEDRIVE, oneDriveConnected)) {
            add(CloudTarget("ONEDRIVE", "OneDrive", SyncProviderType.ONEDRIVE))
        }
    }

    /**
     * 删除某个后端在云端保存的备份文件（只删远端，本机账号/分类一律不动）。
     *
     * 删除动作不走同步引擎：直接构造该后端并调用 delete()。Gist 是整份删除，
     * 删成功后清掉本地记录的 gistId，否则下次同步会 PATCH 到一个已不存在的 Gist。
     */
    fun deleteCloud(target: CloudTarget) {
        viewModelScope.launch {
            val current = settings.settings.first()
            val provider = try {
                SyncProviderFactory.createFor(appContext, current, SyncTarget(target.type, target.webdavServerId))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(message = target.label + "：" + (t.message ?: "配置不完整，无法删除"))
                return@launch
            }
            val ok = try {
                // provider 只保证 execute() 在 IO 上，读响应体需要调用方在 IO 线程
                withContext(Dispatchers.IO) { provider.delete() }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(message = "删除失败：" + (t.message ?: t::class.java.simpleName))
                return@launch
            }
            if (ok && target.type == SyncProviderType.GIST) settings.setGistId("")
            refreshBindings()
            _state.value = _state.value.copy(
                message = if (ok) "已删除「" + target.label + "」云端的数据（本机数据未受影响）"
                else "删除失败：" + target.label
            )
        }
    }

    /** 开关某个「已绑定后端」是否参与同步；配置保持不变。 */
    fun setBindingActive(key: String, active: Boolean) {
        val binding = _state.value.bindings.firstOrNull { it.key == key } ?: return
        val next = _state.value.bindings.map { if (it.key == key) it.copy(active = active) else it }
        _state.value = _state.value.copy(
            bindings = next,
            message = if (active) "「" + binding.label + "」已参与同步" else "「" + binding.label + "」已暂停同步（配置保留）"
        )
        viewModelScope.launch {
            val current = settings.settings.first()
            val disabled = current.disabledProviders.toMutableSet()
            if (active) disabled -= key else disabled += key
            settings.setDisabledProviders(disabled)
        }
    }

    /**
     * 移除一个已绑定后端的本地配置（不动云端文件）。
     *
     * WebDAV 只从服务器列表里摘掉；OAuth 型清本地标记；凭据型清掉对应令牌。
     */
    fun removeBinding(binding: SyncBinding) {
        viewModelScope.launch {
            val current = settings.settings.first()
            when (binding.type) {
                SyncProviderType.WEBDAV -> {
                    val id = binding.webdavServerId.orEmpty()
                    settings.setWebDavServers(current.webdavServers.filterNot { it.id == id })
                    settings.setDisabledProviders(current.disabledProviders - binding.key)
                }
                SyncProviderType.GITHUB -> {
                    settings.setGitHub("", "tessera.vault")
                    settings.setGitHubToken("")
                }
                SyncProviderType.GITEE -> settings.setGitee("", "tessera.vault", "")
                SyncProviderType.GIST -> {
                    settings.setGistToken("")
                    settings.setGistId("")
                }
                SyncProviderType.GOOGLE_DRIVE -> settings.setGoogleAccount("")
                SyncProviderType.ONEDRIVE -> OneDriveAuth.signOut(appContext)
                SyncProviderType.NONE -> Unit
            }
            if (binding.key in current.disabledProviders && binding.type != SyncProviderType.WEBDAV) {
                settings.setDisabledProviders(current.disabledProviders - binding.key)
            }
            refreshBindings()
            _state.value = _state.value.copy(
                message = "已移除「" + binding.label + "」（云端备份未删除，如需清除请在「云端数据」里删除）"
            )
        }
    }

    fun updatePassphraseInput(value: String) { _state.value = _state.value.copy(passphraseInput = value) }

    /** GoogleSignIn 成功后保存账号名（GoogleAccountCredential 需要它来签发 appdata 令牌）。 */
    fun setGoogleAccount(email: String) {
        _state.value = _state.value.copy(googleAccount = email, message = "Google 账号已连接：" + email)
        viewModelScope.launch {
            settings.setGoogleAccount(email)
            enableBinding(SyncProviderType.GOOGLE_DRIVE)
            refreshBindings()
        }
    }

    /** 设置同步口令（用于加密云端同步包）。 */
    fun savePassphrase() {
        val value = _state.value.passphraseInput
        if (value.length < 8) {
            _state.value = _state.value.copy(message = "同步口令至少 8 位")
            return
        }
        passphraseStore.passphrase = value
        _state.value = _state.value.copy(passphraseSet = true, passphraseInput = "", message = "同步口令已保存（请自行牢记，丢失后无法解密云端数据）")
    }

    /**
     * 新增/编辑一个 WebDAV 服务器并**实际测一次连通性**（对话框按钮是「保存并连接」）。
     *
     * 用 pull() 探测：云端还没有备份时会收到 404，那也算连通——只有网络、认证或 TLS
     * 失败才算失败。这样用户填错地址/密码时立刻能看到原因，不必等下一次同步。
     */
    fun saveWebDavServer(server: WebDavServer) {
        viewModelScope.launch {
            val current = settings.settings.first()
            val target = if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server
            val next = current.webdavServers.toMutableList()
            val index = next.indexOfFirst { it.id == target.id }
            if (index >= 0) next[index] = target else next += target
            settings.setWebDavServers(next)
            // 刚添加/编辑过的服务器默认参与同步
            settings.setDisabledProviders(current.disabledProviders - webdavBindingKey(target.id))
            refreshBindings()
            if (target.url.isBlank()) {
                _state.value = _state.value.copy(message = "已保存「" + target.displayLabel + "」，但地址为空，暂不参与同步")
                return@launch
            }
            _state.value = _state.value.copy(message = "正在测试 WebDAV 连接…")
            val result = try {
                // 必须在 IO 线程调用：provider 里只有 execute() 自己切了 IO，读响应体仍在调用方线程
                withContext(Dispatchers.IO) {
                    WebDavProvider(
                        baseUrl = target.url,
                        user = target.user,
                        password = target.password,
                        fileName = target.fileName.ifBlank { "tessera.vault" }
                    ).pull()
                }
                "「" + target.displayLabel + "」连接成功"
            } catch (t: Throwable) {
                "「" + target.displayLabel + "」连接失败：" + (t.message ?: t::class.java.simpleName)
            }
            _state.value = _state.value.copy(message = result)
        }
    }

    fun setGitHub(ownerRepo: String, token: String, path: String) {
        _state.value = _state.value.copy(githubOwnerRepo = ownerRepo, githubToken = token, githubPath = path)
        viewModelScope.launch {
            settings.setGitHub(ownerRepo, path)
            settings.setGitHubToken(token)
            enableBinding(SyncProviderType.GITHUB)
            refreshBindings()
        }
    }

    /** 登录成功后由宿主 Activity 通知刷新状态。 */
    fun onOneDriveSignedIn() {
        viewModelScope.launch {
            enableBinding(SyncProviderType.ONEDRIVE)
            refreshBindings()
            _state.value = _state.value.copy(message = "OneDrive 已连接")
        }
    }

    fun setGitee(ownerRepo: String, path: String, token: String) {
        _state.value = _state.value.copy(giteeOwnerRepo = ownerRepo, giteePath = path, giteeToken = token)
        viewModelScope.launch {
            settings.setGitee(ownerRepo, path, token)
            enableBinding(SyncProviderType.GITEE)
            refreshBindings()
        }
    }

    fun setGist(token: String) {
        _state.value = _state.value.copy(gistToken = token)
        viewModelScope.launch {
            settings.setGistToken(token)
            enableBinding(SyncProviderType.GIST)
            refreshBindings()
        }
    }

    /** 绑定成功的后端默认参与同步：把它从停用集合里移除。 */
    private suspend fun enableBinding(type: SyncProviderType) {
        val current = settings.settings.first()
        if (type.name in current.disabledProviders) {
            settings.setDisabledProviders(current.disabledProviders - type.name)
        }
    }

    /** 重新读取设置，刷新「已绑定后端」「云端数据」与各表单的当前值。 */
    private suspend fun refreshBindings() {
        val s = settings.settings.first()
        val oneDrive = OneDriveAuth.isConnected(appContext)
        _state.value = _state.value.copy(
            oneDriveConnected = oneDrive,
            webdavServers = s.webdavServers,
            githubOwnerRepo = s.githubOwnerRepo,
            githubPath = s.githubPath,
            githubToken = s.githubToken,
            gistToken = s.gistToken,
            gistId = s.gistId,
            giteeOwnerRepo = s.giteeOwnerRepo,
            giteePath = s.giteePath,
            giteeToken = s.giteeToken,
            googleAccount = s.googleAccount,
            bindings = bindingsOf(s, oneDrive),
            configuredTargets = configuredTargets(s, oneDrive)
        )
    }

    fun setInterval(interval: SyncInterval) {
        _state.value = _state.value.copy(interval = interval)
        viewModelScope.launch { settings.setSyncInterval(interval) }
        scheduleWorker(interval)
    }

    /**
     * 同步总开关：关闭后自动同步（冷启动静默同步、账号变更自动同步、后台周期任务）全部停用，
     * 只保留用户主动触发的同步（下拉刷新 / 「立即同步」）。
     */
    fun setSyncEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(
            syncEnabled = enabled,
            message = if (enabled) "已开启自动同步" else "已关闭自动同步：只有下拉刷新与「立即同步」会同步"
        )
        viewModelScope.launch { settings.setSyncEnabled(enabled) }
    }

    /** 手动同步：拉取 -> 合并 -> 推送，并记录状态。 */
    fun runManualSync() {
        if (_state.value.running || autoSyncBusy) return
        _state.value = _state.value.copy(running = true, message = null)
        viewModelScope.launch {
            val current = settings.settings.first()
            val outcome: SyncOutcome = try {
                if (passphraseStore.passphrase.isNullOrBlank()) {
                    SyncOutcome(false, message = "请先设置同步口令")
                } else {
                    val targets = SyncProviderFactory.targets(current, OneDriveAuth.isConnected(appContext))
                    if (targets.isEmpty()) {
                        _state.value = _state.value.copy(running = false, message = "还没有已绑定的同步后端，请先「添加同步后端」")
                        return@launch
                    }
                    // 逐个体检：某个后端配置不全不应拖垮其它后端
                    val (providers, setupErrors) = SyncProviderFactory.createAll(appContext, current, targets) { id ->
                        viewModelScope.launch { settings.setGistId(id) }
                    }
                    if (providers.isEmpty()) {
                        SyncOutcome(false, message = "所有已配置的同步后端都不可用：" + setupErrors.joinToString("、"))
                    } else {
                        val base = SyncEngine(repository, passphraseStore).syncAll(providers)
                        if (setupErrors.isEmpty()) base
                        else base.copy(success = false, message = base.message + "；失败：" + setupErrors.joinToString("、"))
                    }
                }
            } catch (t: Throwable) {
                SyncOutcome(false, message = t.message ?: "同步失败")
            }

            // 只有成功才刷新"上次成功"，否则失败也会显示一个成功时间
            val stamp = if (outcome.success) outcome.lastSyncAt else current.lastSyncAt
            settings.setSyncStatus(outcome.message, outcome.pushed, outcome.pulled, stamp)
            _state.value = _state.value.copy(
                running = false,
                lastStatus = outcome.message,
                lastSyncAt = stamp,
                message = outcome.message
            )
        }
    }

    private var autoSyncRunning = false
    private var autoSyncPending = false

    /** 自动同步进行中标志：手动同步据此让路，避免两次 replaceAll 并发改写同一批行。 */
    private var autoSyncBusy = false

    /**
     * 账号数据每次变更（新增/编辑/删除/导入等）后静默同步一次。
     *
     * - 与手动同步共用同一把「running」闸门：若已有同步在跑，先等它结束再补跑一次，
     *   保证最后一次改动最终会被推送上去。
     * - 不写 [SyncUiState.message]，因此不会在界面上冒出「提醒」；只更新「上次成功」与状态文案。
     * - 未设置同步口令或未选择后端时直接跳过，不打扰用户。
     */
    fun requestAutoSync() {
        viewModelScope.launch {
            if (autoSyncRunning) {
                autoSyncPending = true
                return@launch
            }
            autoSyncRunning = true
            autoSyncBusy = true
            try {
                do {
                    autoSyncPending = false
                    if (!awaitSyncIdle()) return@launch
                    runAutoSync()
                } while (autoSyncPending)
            } finally {
                autoSyncBusy = false
                autoSyncRunning = false
            }
        }
    }

    /** 等待正在进行的同步结束（最长 30 秒）；返回是否已空闲。 */
    private suspend fun awaitSyncIdle(): Boolean {
        val deadline = System.currentTimeMillis() + 30_000
        while (_state.value.running && System.currentTimeMillis() < deadline) delay(150)
        return !_state.value.running
    }

    /** 执行一次静默同步：不设置 message、不闪「同步中」文案，只落库与记录时间戳。 */
    private suspend fun runAutoSync() {
        val current = settings.settings.first()
        if (!current.syncEnabled) return
        if (passphraseStore.passphrase.isNullOrBlank()) return
        val targets = SyncProviderFactory.targets(current, OneDriveAuth.isConnected(appContext))
        if (targets.isEmpty()) return
        val (providers, _) = SyncProviderFactory.createAll(appContext, current, targets) { id ->
            viewModelScope.launch { settings.setGistId(id) }
        }
        if (providers.isEmpty()) return
        val outcome: SyncOutcome = try {
            SyncEngine(repository, passphraseStore).syncAll(providers)
        } catch (t: Throwable) {
            SyncOutcome(false, message = t.message ?: "同步失败")
        }
        val stamp = if (outcome.success) outcome.lastSyncAt else current.lastSyncAt
        settings.setSyncStatus(outcome.message, outcome.pushed, outcome.pulled, stamp)
        _state.value = _state.value.copy(lastStatus = outcome.message, lastSyncAt = stamp)
    }
}
