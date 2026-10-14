package com.tessera.otp.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.tessera.otp.data.prefs.AppSettings
import com.tessera.otp.data.prefs.SettingsRepository
import com.tessera.otp.data.prefs.SyncInterval
import com.tessera.otp.data.prefs.SyncProviderType
import com.tessera.otp.data.repo.AccountRepository
import com.tessera.otp.sync.GoogleDriveAuth
import com.tessera.otp.sync.OneDriveAuth
import com.tessera.otp.sync.SyncCodec
import com.tessera.otp.sync.SyncEngine
import com.tessera.otp.sync.SyncOutcome
import com.tessera.otp.sync.SyncPassphraseStore
import com.tessera.otp.sync.SyncProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncUiState(
    val provider: SyncProviderType = SyncProviderType.NONE,
    val passphraseSet: Boolean = false,
    val passphraseInput: String = "",
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPassword: String = "",
    val githubOwnerRepo: String = "",
    val githubPath: String = "tessera.vault",
    val githubToken: String = "",
    val gistToken: String = "",
    val gistId: String = "",
    val giteeOwnerRepo: String = "",
    val giteePath: String = "tessera.vault",
    val giteeToken: String = "",
    val oneDriveConnected: Boolean = false,
    /** 参与「同时同步」的后端名称集合。 */
    val enabledProviders: Set<String> = emptySet(),
    /** 已配置（有可用凭据）的后端：可在「云端数据」里删除其远端备份。 */
    val configuredProviders: List<SyncProviderType> = emptyList(),
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
            _state.value = _state.value.copy(
                provider = runCatching { SyncProviderType.valueOf(current.syncProvider) }.getOrDefault(SyncProviderType.NONE),
                passphraseSet = !passphraseStore.passphrase.isNullOrBlank(),
                webdavUrl = current.webdavUrl,
                webdavUser = current.webdavUser,
                webdavPassword = current.webdavPassword,
                githubOwnerRepo = current.githubOwnerRepo,
                githubPath = current.githubPath,
                githubToken = current.githubToken,
                gistToken = current.gistToken,
                gistId = current.gistId,
                giteeOwnerRepo = current.giteeOwnerRepo,
                giteePath = current.giteePath,
                giteeToken = current.giteeToken,
                oneDriveConnected = OneDriveAuth.isConnected(appContext),
                interval = runCatching { SyncInterval.valueOf(current.syncIntervalName) }.getOrDefault(SyncInterval.OFF),
                syncEnabled = current.syncEnabled,
                lastStatus = current.lastSyncStatus,
                lastSyncAt = current.lastSyncAt,
                googleAccount = current.googleAccount,
                enabledProviders = current.enabledProviders,
                configuredProviders = configuredTypes(current)
            )
        }
    }

    /**
     * 判定哪些后端「已配置」——只有配好凭据的后端才可能云端有数据、才需要在「云端数据」里列出。
     * Gist 额外要求已有 gistId（没有 Gist 就没有远端数据可删）。
     */
    private fun configuredTypes(s: AppSettings): List<SyncProviderType> = buildList {
        if (s.webdavUrl.isNotBlank()) add(SyncProviderType.WEBDAV)
        if (s.gistToken.isNotBlank() && s.gistId.isNotBlank()) add(SyncProviderType.GIST)
        if (s.githubToken.isNotBlank() && s.githubOwnerRepo.count { it == '/' } == 1) add(SyncProviderType.GITHUB)
        if (s.giteeToken.isNotBlank() && s.giteeOwnerRepo.count { it == '/' } == 1) add(SyncProviderType.GITEE)
        if (s.googleAccount.isNotBlank()) add(SyncProviderType.GOOGLE_DRIVE)
        if (OneDriveAuth.isConnected(appContext)) add(SyncProviderType.ONEDRIVE)
    }

    /**
     * 删除某个后端在云端保存的备份文件（只删远端，本机账号/分类一律不动）。
     *
     * 删除动作不走同步引擎：直接构造该后端并调用 delete()。Gist 是整份删除，
     * 删成功后清掉本地记录的 gistId，否则下次同步会 PATCH 到一个已不存在的 Gist。
     */
    fun deleteCloud(type: SyncProviderType) {
        viewModelScope.launch {
            val current = settings.settings.first()
            val provider = try {
                SyncProviderFactory.createFor(appContext, current, type)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(message = type.label + "：" + (t.message ?: "配置不完整，无法删除"))
                return@launch
            }
            val ok = try {
                // 同 setWebDav：provider 只保证 execute() 在 IO 上，读响应体需要调用方在 IO 线程
                withContext(Dispatchers.IO) { provider.delete() }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(message = "删除失败：" + (t.message ?: t::class.java.simpleName))
                return@launch
            }
            if (ok && type == SyncProviderType.GIST) settings.setGistId("")
            val fresh = settings.settings.first()
            _state.value = _state.value.copy(
                configuredProviders = configuredTypes(fresh),
                message = if (ok) "已删除「" + type.label + "」云端的数据（本机数据未受影响）"
                else "删除失败：" + type.label
            )
        }
    }

    /** 勾选/取消某个后端参与「同时同步」。 */
    fun toggleProvider(type: SyncProviderType, enabled: Boolean) {
        val next = _state.value.enabledProviders.toMutableSet()
        if (enabled) next.add(type.name) else next.remove(type.name)
        val label = next.mapNotNull { runCatching { SyncProviderType.valueOf(it).label }.getOrNull() }
        _state.value = _state.value.copy(
            enabledProviders = next,
            message = if (next.isEmpty()) "已关闭同时同步：只同步当前选择的后端"
            else "同时同步：" + label.joinToString("、")
        )
        viewModelScope.launch { settings.setEnabledProviders(next) }
    }

    /** 参与同步的后端：当前选中的始终参与，勾选的后端与它一起读写。 */
    private fun activeTypes(settings: AppSettings): List<SyncProviderType> {
        val current = runCatching { SyncProviderType.valueOf(settings.syncProvider) }.getOrDefault(SyncProviderType.NONE)
        val enabled = settings.enabledProviders
            .mapNotNull { runCatching { SyncProviderType.valueOf(it) }.getOrNull() }
            .filter { it != SyncProviderType.NONE && it != current }
        return if (current == SyncProviderType.NONE) enabled else listOf(current) + enabled
    }

    fun selectProvider(type: SyncProviderType) {
        _state.value = _state.value.copy(provider = type, message = null)
        viewModelScope.launch { settings.setSyncProvider(type) }
    }

    fun updatePassphraseInput(value: String) { _state.value = _state.value.copy(passphraseInput = value) }

    /** GoogleSignIn 成功后保存账号名（GoogleAccountCredential 需要它来签发 appdata 令牌）。 */
    fun setGoogleAccount(email: String) {
        _state.value = _state.value.copy(googleAccount = email, message = "Google 账号已连接：" + email)
        viewModelScope.launch {
            settings.setGoogleAccount(email)
            refreshConfiguredProviders()
        }
    }

    /** 断开：仅清除本地标记。GMS 的授权无法由应用单方面撤销，用户可在 Google 账号设置里移除访问权限。 */
    fun clearGoogleAccount() {
        _state.value = _state.value.copy(googleAccount = "", message = "已断开 Google 账号（如需彻底撤销请到 Google 账号设置移除授权）")
        viewModelScope.launch {
            settings.setGoogleAccount("")
            refreshConfiguredProviders()
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
     * 保存 WebDAV 凭据并**实际测一次连通性**（按钮文案是「保存并连接」）。
     *
     * 用 pull() 探测：云端还没有备份时会收到 404，那也算连通——只有网络、认证或 TLS
     * 失败才算失败。这样用户填错地址/密码时立刻能看到原因，不必等下一次同步。
     */
    fun setWebDav(url: String, user: String, password: String) {
        _state.value = _state.value.copy(webdavUrl = url, webdavUser = user, webdavPassword = password)
        viewModelScope.launch {
            settings.setWebDav(url, user)
            settings.setWebDavPassword(password)
            refreshConfiguredProviders()
            if (url.isBlank()) {
                _state.value = _state.value.copy(message = "已清除 WebDAV 配置")
                return@launch
            }
            _state.value = _state.value.copy(message = "正在测试 WebDAV 连接…")
            val result = try {
                // 必须在 IO 线程调用：provider 里只有 execute() 自己切了 IO，读响应体仍在调用方线程
                withContext(Dispatchers.IO) {
                    SyncProviderFactory.createFor(appContext, settings.settings.first(), SyncProviderType.WEBDAV).pull()
                }
                "WebDAV 连接成功"
            } catch (t: Throwable) {
                "WebDAV 连接失败：" + (t.message ?: t::class.java.simpleName)
            }
            _state.value = _state.value.copy(message = result)
        }
    }

    fun setGitHub(ownerRepo: String, token: String, path: String) {
        _state.value = _state.value.copy(githubOwnerRepo = ownerRepo, githubToken = token, githubPath = path)
        viewModelScope.launch {
            settings.setGitHub(ownerRepo, path)
            settings.setGitHubToken(token)
            refreshConfiguredProviders()
        }
    }

    /** 登录成功后由宿主 Activity 通知刷新状态。 */
    fun onOneDriveSignedIn() {
        _state.value = _state.value.copy(oneDriveConnected = true, message = "OneDrive 已连接")
        viewModelScope.launch { refreshConfiguredProviders() }
    }

    /** 断开 OneDrive（清空本地令牌，不影响云端文件）。 */
    fun signOutOneDrive() {
        OneDriveAuth.signOut(appContext)
        _state.value = _state.value.copy(oneDriveConnected = false, message = "已断开 OneDrive")
        viewModelScope.launch { refreshConfiguredProviders() }
    }

    fun setGitee(ownerRepo: String, path: String, token: String) {
        _state.value = _state.value.copy(giteeOwnerRepo = ownerRepo, giteePath = path, giteeToken = token)
        viewModelScope.launch {
            settings.setGitee(ownerRepo, path, token)
            refreshConfiguredProviders()
        }
    }

    fun setGist(token: String) {
        _state.value = _state.value.copy(gistToken = token)
        viewModelScope.launch {
            settings.setGistToken(token)
            refreshConfiguredProviders()
        }
    }

    /** 凭据变更后重算「云端数据」可删除列表：原先只在 init 算一次，保存后不刷新。 */
    private suspend fun refreshConfiguredProviders() {
        _state.value = _state.value.copy(configuredProviders = configuredTypes(settings.settings.first()))
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
                    val types = activeTypes(current)
                    if (types.isEmpty()) {
                        _state.value = _state.value.copy(running = false, message = "尚未选择同步方式")
                        return@launch
                    }
                    // 逐个体检：某个后端配置不全不应拖垮其它后端
                    val (providers, setupErrors) = SyncProviderFactory.createAll(appContext, current, types) { id ->
                        viewModelScope.launch { settings.setGistId(id) }
                    }
                    if (providers.isEmpty()) {
                        SyncOutcome(false, message = "所有已勾选的后端都不可用：" + setupErrors.joinToString("、"))
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
        val types = activeTypes(current)
        if (types.isEmpty()) return
        val (providers, _) = SyncProviderFactory.createAll(appContext, current, types) { id ->
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
