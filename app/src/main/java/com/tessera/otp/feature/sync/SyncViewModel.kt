package com.tessera.otp.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.tessera.otp.data.prefs.AppSettings
import com.tessera.otp.data.prefs.LEGACY_ACCOUNT_ID
import com.tessera.otp.data.prefs.SINGLETON_PROVIDER_ORDER
import com.tessera.otp.data.prefs.SettingsRepository
import com.tessera.otp.data.prefs.SyncInterval
import com.tessera.otp.data.prefs.SyncProviderType
import com.tessera.otp.data.prefs.WebDavServer
import com.tessera.otp.data.prefs.accountDisplayName
import com.tessera.otp.data.prefs.displayLabel
import com.tessera.otp.data.prefs.googleDriveBindingKey
import com.tessera.otp.data.prefs.oneDriveBindingKey
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
    /** 是否存在已连接的 OneDrive 账号（界面用它决定"登录"还是"再添加账号"）。 */
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
    val running: Boolean = false,
    val message: String? = null
)

/**
 * 云同步页上的一行「已绑定后端」。
 *
 * [key] 同时是停用集合与云端删除定位用的键：多实例后端为 `<类型>:<实例 id>`，其余为枚举名。
 */
data class SyncBinding(
    val key: String,
    val label: String,
    /** 副标题：账号 / 地址 / 文件名 / 仓库路径，用于区分同类多个实例。 */
    val detail: String,
    val type: SyncProviderType,
    /** 多实例后端的实例 id：WebDAV 是服务器 id，Google Drive / OneDrive 是账号 id。 */
    val instanceId: String? = null,
    /** 是否参与同步；关闭只是不同步，配置仍然保留。 */
    val active: Boolean = true,
    /** 可编辑（WebDAV 与三个凭据型后端）；OAuth 型只能重新登录。 */
    val editable: Boolean = false
)

/** 「云端数据」里可删除的一个远端后端实例（多实例后端各自一条）。 */
data class CloudTarget(
    val key: String,
    val label: String,
    val type: SyncProviderType,
    val instanceId: String? = null
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
            val oneDrive = OneDriveAuth.accountIds(appContext)
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
                oneDriveConnected = oneDrive.isNotEmpty(),
                bindings = bindingsOf(current, oneDrive),
                interval = runCatching { SyncInterval.valueOf(current.syncIntervalName) }.getOrDefault(SyncInterval.OFF),
                syncEnabled = current.syncEnabled,
                lastStatus = current.lastSyncStatus,
                lastSyncAt = current.lastSyncAt,
                configuredTargets = configuredTargets(current, oneDrive)
            )
        }
    }

    /**
     * 已绑定的后端列表——**绑定（凭据填齐）才会出现在页面上**，未绑定的一律不显示。
     * 「添加同步后端」里能被再次添加的只有未绑定的单实例后端（WebDAV / 云盘可加多个）。
     */
    private fun bindingsOf(s: AppSettings, oneDriveAccountIds: List<String>): List<SyncBinding> = buildList {
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
                    instanceId = server.id,
                    active = key !in disabled,
                    editable = true
                )
            )
        }
        // Google Drive：一个账号一行，标题就是账号（与 WebDAV 用备注名做标题同一套口径）
        s.googleAccounts.forEach { account ->
            val key = googleDriveBindingKey(account)
            add(
                SyncBinding(
                    key = key,
                    label = accountDisplayName(account),
                    detail = "Google Drive · " + googleAccountHint(account),
                    type = SyncProviderType.GOOGLE_DRIVE,
                    instanceId = account,
                    active = key !in disabled
                )
            )
        }
        oneDriveAccountIds.forEach { account ->
            val key = oneDriveBindingKey(account)
            add(
                SyncBinding(
                    key = key,
                    label = accountDisplayName(account),
                    detail = "OneDrive · 已连接微软账号",
                    type = SyncProviderType.ONEDRIVE,
                    instanceId = account,
                    active = key !in disabled
                )
            )
        }
        SINGLETON_PROVIDER_ORDER.forEach { type ->
            if (!SyncProviderFactory.hasCredentials(s, type)) return@forEach
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

    /** Google 账号的副标题：迁移出来的旧记录没有邮箱，需要说清楚。 */
    private fun googleAccountHint(account: String): String =
        if (account == LEGACY_ACCOUNT_ID) "旧版单账号配置（未记录邮箱）" else "账号 " + account

    private fun bindingDetail(s: AppSettings, type: SyncProviderType): String = when (type) {
        SyncProviderType.GITHUB -> s.githubOwnerRepo + " · " + s.githubPath
        SyncProviderType.GITEE -> s.giteeOwnerRepo + " · " + s.giteePath
        SyncProviderType.GIST -> if (s.gistId.isBlank()) "首次同步会自动创建私有 Gist" else "Gist ID " + s.gistId
        else -> ""
    }

    /**
     * 判定哪些后端「已配置」——只有配好凭据的后端才可能云端有数据、才需要在「云端数据」里列出。
     * 多实例后端每个实例各一条；Gist 额外要求已有 gistId（没有 Gist 就没有远端数据可删）。
     */
    private fun configuredTargets(s: AppSettings, oneDriveAccountIds: List<String>): List<CloudTarget> = buildList {
        s.webdavServers.forEach {
            add(CloudTarget(webdavBindingKey(it.id), "WebDAV（" + it.displayLabel + "）", SyncProviderType.WEBDAV, it.id))
        }
        s.googleAccounts.forEach {
            add(
                CloudTarget(
                    googleDriveBindingKey(it),
                    "Google Drive（" + accountDisplayName(it) + "）",
                    SyncProviderType.GOOGLE_DRIVE,
                    it
                )
            )
        }
        oneDriveAccountIds.forEach {
            add(
                CloudTarget(
                    oneDriveBindingKey(it),
                    "OneDrive（" + accountDisplayName(it) + "）",
                    SyncProviderType.ONEDRIVE,
                    it
                )
            )
        }
        if (s.gistToken.isNotBlank() && s.gistId.isNotBlank()) add(CloudTarget("GIST", "GitHub Gist", SyncProviderType.GIST))
        if (SyncProviderFactory.hasCredentials(s, SyncProviderType.GITHUB)) {
            add(CloudTarget("GITHUB", "GitHub 仓库", SyncProviderType.GITHUB))
        }
        if (SyncProviderFactory.hasCredentials(s, SyncProviderType.GITEE)) {
            add(CloudTarget("GITEE", "Gitee", SyncProviderType.GITEE))
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
                SyncProviderFactory.createFor(appContext, current, SyncTarget(target.type, target.instanceId))
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
     * WebDAV 从服务器列表里摘掉；Google Drive 从账号列表里摘掉（Play Services 侧的授权不动）；
     * OneDrive 删掉该账号的本地令牌；凭据型清掉对应令牌。
     */
    fun removeBinding(binding: SyncBinding) {
        viewModelScope.launch {
            val current = settings.settings.first()
            when (binding.type) {
                SyncProviderType.WEBDAV ->
                    settings.setWebDavServers(current.webdavServers.filterNot { it.id == binding.instanceId })
                SyncProviderType.GOOGLE_DRIVE ->
                    settings.setGoogleAccounts(current.googleAccounts - binding.instanceId.orEmpty())
                SyncProviderType.ONEDRIVE ->
                    OneDriveAuth.signOut(appContext, binding.instanceId.orEmpty())
                SyncProviderType.GITHUB -> {
                    settings.setGitHub("", "tessera.vault")
                    settings.setGitHubToken("")
                }
                SyncProviderType.GITEE -> settings.setGitee("", "tessera.vault", "")
                SyncProviderType.GIST -> {
                    settings.setGistToken("")
                    settings.setGistId("")
                }
                SyncProviderType.NONE -> Unit
            }
            // 实例已经不存在了，它在停用集合里的残留键一并清掉
            if (binding.key in current.disabledProviders) {
                settings.setDisabledProviders(current.disabledProviders - binding.key)
            }
            refreshBindings()
            _state.value = _state.value.copy(
                message = "已移除「" + binding.label + "」（云端备份未删除，如需清除请在「云端数据」里删除）"
            )
        }
    }

    fun updatePassphraseInput(value: String) { _state.value = _state.value.copy(passphraseInput = value) }

    /**
     * Google 账号授权成功后由宿主 Activity 通知（传入系统账号选择器选中的邮箱）。
     * 记进账号列表，并默认参与同步；重复授权同一账号是幂等的。
     *
     * 旧版迁移出来的那条「默认账号」在此退役：它指向的是同一个云端文件，
     * 留着会让同一份备份被同步两遍（还可能因为 etag 变化报出假的冲突失败）。
     */
    fun onGoogleAccountAuthorized(email: String) {
        viewModelScope.launch {
            val current = settings.settings.first()
            if (email.isNotBlank()) {
                val next = (current.googleAccounts - LEGACY_ACCOUNT_ID).let {
                    if (email in it) it else it + email
                }
                if (next != current.googleAccounts) {
                    settings.setGoogleAccounts(next)
                    clearLegacyDisabledKey(SyncProviderType.GOOGLE_DRIVE)
                }
            }
            enableBinding(SyncProviderFactory.bindingKey(SyncProviderType.GOOGLE_DRIVE, email))
            refreshBindings()
            _state.value = _state.value.copy(message = "Google 账号已连接：" + accountDisplayName(email))
        }
    }

    /** 旧版迁移出来的「默认账号」退役后，清掉它在停用集合里的残留键（否则会留下无主配置）。 */
    private suspend fun clearLegacyDisabledKey(type: SyncProviderType) {
        val current = settings.settings.first()
        val key = SyncProviderFactory.bindingKey(type, LEGACY_ACCOUNT_ID)
        if (key in current.disabledProviders) {
            settings.setDisabledProviders(current.disabledProviders - key)
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
            enableBinding(SyncProviderType.GITHUB.name)
            refreshBindings()
        }
    }

    /** 登录成功后由宿主 Activity 通知刷新状态；[accountId] 是刚登录账号的 id（邮箱），可能为 null。 */
    fun onOneDriveSignedIn(accountId: String?) {
        viewModelScope.launch {
            if (!accountId.isNullOrBlank()) {
                // 具名登录成功后旧版的「默认账号」令牌已被 OneDriveAuth 退役，这里清掉它的停用键
                if (LEGACY_ACCOUNT_ID !in OneDriveAuth.accountIds(appContext)) {
                    clearLegacyDisabledKey(SyncProviderType.ONEDRIVE)
                }
                enableBinding(SyncProviderFactory.bindingKey(SyncProviderType.ONEDRIVE, accountId))
            }
            refreshBindings()
            _state.value = _state.value.copy(
                message = "OneDrive 已连接：" + accountDisplayName(accountId.orEmpty())
            )
        }
    }

    fun setGitee(ownerRepo: String, path: String, token: String) {
        _state.value = _state.value.copy(giteeOwnerRepo = ownerRepo, giteePath = path, giteeToken = token)
        viewModelScope.launch {
            settings.setGitee(ownerRepo, path, token)
            enableBinding(SyncProviderType.GITEE.name)
            refreshBindings()
        }
    }

    fun setGist(token: String) {
        _state.value = _state.value.copy(gistToken = token)
        viewModelScope.launch {
            settings.setGistToken(token)
            enableBinding(SyncProviderType.GIST.name)
            refreshBindings()
        }
    }

    /** 绑定成功的后端默认参与同步：把它从停用集合里移除。 */
    private suspend fun enableBinding(key: String) {
        val current = settings.settings.first()
        if (key in current.disabledProviders) {
            settings.setDisabledProviders(current.disabledProviders - key)
        }
    }

    /** 重新读取设置，刷新「已绑定后端」「云端数据」与各表单的当前值。 */
    private suspend fun refreshBindings() {
        val s = settings.settings.first()
        val oneDrive = OneDriveAuth.accountIds(appContext)
        _state.value = _state.value.copy(
            oneDriveConnected = oneDrive.isNotEmpty(),
            webdavServers = s.webdavServers,
            githubOwnerRepo = s.githubOwnerRepo,
            githubPath = s.githubPath,
            githubToken = s.githubToken,
            gistToken = s.gistToken,
            gistId = s.gistId,
            giteeOwnerRepo = s.giteeOwnerRepo,
            giteePath = s.giteePath,
            giteeToken = s.giteeToken,
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
                    val targets = SyncProviderFactory.targets(current, OneDriveAuth.accountIds(appContext))
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
        val targets = SyncProviderFactory.targets(current, OneDriveAuth.accountIds(appContext))
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
