package com.tessera.otp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tessera.otp.data.prefs.AutoLockDelay
import com.tessera.otp.data.prefs.ListDensity
import com.tessera.otp.data.prefs.SyncInterval
import com.tessera.otp.data.prefs.ThemeMode
import com.tessera.otp.data.repo.AccountRepository
import com.tessera.otp.di.AppContainer
import com.tessera.otp.feature.detail.AccountDetailViewModel
import com.tessera.otp.feature.home.HomeViewModel
import com.tessera.otp.feature.importer.ImportViewModel
import com.tessera.otp.feature.sync.SyncViewModel
import com.tessera.otp.feature.trash.TrashViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 顶层页面路由。 */
sealed interface Screen {
    data object Home : Screen
    data object Trash : Screen
    data object Settings : Screen
    /** 设置的二级页；section 是 SettingsSection.key。 */
    data class SettingsSub(val section: String) : Screen
    data object IconPicker : Screen
    data object Import : Screen
    data object Sync : Screen
    /** 云同步的二级页：后端绑定与云端数据管理。 */
    data object SyncBackends : Screen
    data object Export : Screen
    data object Categories : Screen
    /** 关于页（版本、许可）。 */
    data object About : Screen
    /** 分类归类页：把账号多选归入 categoryId 指定的分类。 */
    data class CategoryAssign(val categoryId: String) : Screen
    data class Edit(val accountId: String?) : Screen
}

/**
 * 应用级 ViewModel：路由、锁定状态、设置写入，以及各页面 ViewModel。
 *
 * 仓库初始化需要 Keystore 与 SQLCipher，放在 IO 线程完成后再暴露给子 ViewModel；
 * 因此子 ViewModel 与仓库都通过 [ready] 标志驱动 UI 等待。
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    /** 数据库仓库：在 IO 线程异步初始化，绝不阻塞主线程。 */
    @Volatile private var repositoryRef: AccountRepository? = null

    private val _ready = MutableStateFlow(false)

    /** 仓库是否已就绪；UI 需等它为 true 后再访问 home/trash/importer/sync。 */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    init {
        viewModelScope.launch {
            // Keystore 取口令 + 打开 SQLCipher 库都属于重操作，放到 IO 线程
            repositoryRef = withContext(Dispatchers.IO) { container.accountRepository() }
            val stored = container.settings.settings.first()
            // 冷启动是否上锁听「自动锁定」：关掉开关（延迟=从不同理）时不该在启动时拦人，
            // 否则那个开关看起来是坏的。这段必须在 ready 之前算完，UI 才不会先显示内容再跳到锁屏。
            if (_hasPin.value) {
                val delay = runCatching { AutoLockDelay.valueOf(stored.autoLockDelayName) }
                    .getOrDefault(AutoLockDelay.SEC_30)
                _locked.value = stored.autoLockEnabled && delay.millis >= 0
            }
            _ready.value = true
            // 没有 PIN 时自动锁定与生物识别必然是无效状态（锁屏只在设置了 PIN 时才出现）；
            // 启动时一次性归一化，避免出现「自动锁定：开」但实际没有应用锁的错觉
            if (!_hasPin.value) {
                if (stored.autoLockEnabled) container.settings.setAutoLockEnabled(false)
                if (stored.biometricEnabled) container.settings.setBiometricEnabled(false)
            }
            // 冷启动静默跑一次同步（口令/后端没配好会直接返回，不弹任何提示）；
            // 账号数据一变也静默同步一次，同步自身写库不会触发（见 AccountRepository.muted）
            sync.requestAutoSync()
            val repo = requireRepository()
            repo.localChanges.collect { sync.requestAutoSync() }
        }
    }

    private fun requireRepository(): AccountRepository =
        repositoryRef ?: error("仓库尚未初始化，请等待 ready 为 true")

    val home: HomeViewModel by lazy { HomeViewModel(requireRepository(), container.settings) }
    val trash: TrashViewModel by lazy { TrashViewModel(requireRepository()) }
    val importer: ImportViewModel by lazy { ImportViewModel(requireRepository()) }

    /** 分类（文件夹）管理 ViewModel（设置 → 数据 → 分类管理）。 */
    val categories: com.tessera.otp.feature.settings.CategoryManageViewModel by lazy {
        com.tessera.otp.feature.settings.CategoryManageViewModel(requireRepository())
    }

    /** 同步页 ViewModel；周期任务由 Activity 注入的 scheduler 注册。 */
    val sync: SyncViewModel by lazy {
        SyncViewModel(
            appContext = container.appContext,
            repository = requireRepository(),
            settings = container.settings,
            passphraseStore = container.syncPassphrase(),
            scheduleWorker = { interval ->
                container.workScheduler?.invoke(interval)
            }
        )
    }

    /** 网络校时状态（设置页展示用）；跟随 NetworkTime 的静默校准结果实时刷新。 */
    val timeStatus: StateFlow<com.tessera.otp.data.time.TimeSyncStatus> =
        com.tessera.otp.data.time.NetworkTime.statusFlow

    private val _screen = MutableStateFlow<Screen>(Screen.Home)

    /**
     * 返回栈：记录"从哪来"，让 back() 返回上一页而不是一律回主页。
     * 底部导航的主页/设置视为根页面，切过去时清空栈，避免栈里堆积 主页/设置。
     */
    private val backStack = ArrayDeque<Screen>()
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _locked = MutableStateFlow(container.lockPrefs.hasPin)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _hasPin = MutableStateFlow(container.lockPrefs.hasPin)
    val hasPin: StateFlow<Boolean> = _hasPin.asStateFlow()

    var biometricAvailable: Boolean = false

    private var lastBackgroundAt: Long = 0L
    private var detailModel: AccountDetailViewModel? = null

    /** 打开编辑页：accountId 为 null 表示新建。 */
    fun openEdit(accountId: String?) {
        detailModel = AccountDetailViewModel(requireRepository(), accountId)
        pushCurrent()
        _screen.value = Screen.Edit(accountId)
    }

    fun detail(): AccountDetailViewModel? = detailModel

    fun navigate(target: Screen) {
        if (target == Screen.Home || target == Screen.Settings) {
            // 根页面：切换时清空返回栈
            backStack.clear()
        } else if (_screen.value != target) {
            pushCurrent()
        }
        _screen.value = target
    }

    /** 扫码或相册识别出的二维码：配对流程直接落库并回主页弹「即将完成」，不再进导入确认页。 */
    fun onQrText(raw: String) {
        if (raw.isBlank()) return
        viewModelScope.launch {
            val accounts = importer.importScanned(raw)
            _justPaired.value = accounts
            navigate(Screen.Home)
        }
    }

    private val _justPaired = MutableStateFlow<List<com.tessera.otp.importer.ParsedAccount>>(emptyList())

    /** 本次配对解析出的条目；非空时主页上方展示「即将完成」完成页。 */
    val justPaired: StateFlow<List<com.tessera.otp.importer.ParsedAccount>> = _justPaired.asStateFlow()

    fun clearJustPaired() { _justPaired.value = emptyList() }

    /** 返回上一页；栈空时回主页。 */
    fun back() { _screen.value = backStack.removeLastOrNull() ?: Screen.Home }

    /** 把当前页面压栈（不在栈顶重复压入）。 */
    private fun pushCurrent() {
        val current = _screen.value
        if (backStack.lastOrNull() != current) backStack.addLast(current)
    }

    fun onMoveToBackground() { lastBackgroundAt = System.currentTimeMillis() }

    /** 回前台：按延迟与设置决定是否重新锁定。 */
    fun onReturnToForeground() {
        if (!_hasPin.value) return
        viewModelScope.launch {
            val settings = container.settings.settings.first()
            if (!settings.autoLockEnabled) return@launch
            val delay = AutoLockDelay.valueOf(settings.autoLockDelayName).millis
            if (delay < 0) return@launch
            val elapsed = System.currentTimeMillis() - lastBackgroundAt
            if (lastBackgroundAt > 0 && elapsed >= delay) _locked.value = true
        }
    }

    fun lock() { if (_hasPin.value) _locked.value = true }

    /** 返回 true 表示解锁成功（供 LockScreen 判断是否清空输入）。 */
    fun unlockWithPin(pin: String): Boolean {
        val ok = container.lockPrefs.verifyPin(pin)
        if (ok) _locked.value = false
        return ok
    }

    fun throttleSeconds(): Int = container.lockPrefs.throttleSeconds()

    /** 生物识别成功后的回调（实际校验由 BiometricPrompt 完成）。 */
    fun onBiometricSucceeded() { _locked.value = false }

    fun setPin(pin: String) {
        container.lockPrefs.setPin(pin)
        _hasPin.value = true
        _locked.value = false
    }

    fun clearPin() {
        container.lockPrefs.clearPin()
        _hasPin.value = false
        _locked.value = false
        // 没有 PIN 就没有应用锁：顺手关掉自动锁定与生物识别，别留下「开着但无效」的开关
        viewModelScope.launch {
            container.settings.setAutoLockEnabled(false)
            container.settings.setBiometricEnabled(false)
        }
    }

    fun setCodesVisible(value: Boolean) { viewModelScope.launch { container.settings.setCodesVisible(value) } }
    fun setNextTokenThreshold(seconds: Int) { viewModelScope.launch { container.settings.setNextTokenThreshold(seconds) } }
    fun setAllowScreenshots(value: Boolean) { viewModelScope.launch { container.settings.setAllowScreenshots(value) } }
    /**
     * 自动锁定的前置条件是已设置 PIN：没有 PIN 时锁屏根本不会出现，
     * 开启只会造成「显示为开、实际不锁」的假象，因此直接拒绝。
     */
    fun setAutoLockEnabled(value: Boolean) {
        if (value && !_hasPin.value) return
        viewModelScope.launch { container.settings.setAutoLockEnabled(value) }
    }
    fun setAutoLockDelay(delay: AutoLockDelay) { viewModelScope.launch { container.settings.setAutoLockDelay(delay) } }
    /** 同自动锁定：没有 PIN 就没有锁屏，生物识别开关打开也不会生效，因此拒绝。 */
    fun setBiometric(value: Boolean) {
        if (value && !_hasPin.value) return
        viewModelScope.launch { container.settings.setBiometricEnabled(value) }
    }
    fun setDynamicColor(value: Boolean) { viewModelScope.launch { container.settings.setDynamicColor(value) } }
    fun setTrashRetention(days: Int) { viewModelScope.launch { container.settings.setTrashRetentionDays(days) } }
    /** 手动触发网络校时（出码使用校准后的时间）。 */
    fun syncNetworkTime() {
        viewModelScope.launch {
            // 结果由 NetworkTime.statusFlow 推给 UI，这里不用再手工赋值
            com.tessera.otp.data.time.NetworkTime.sync()
        }
    }

    fun setThemeMode(mode: ThemeMode) { viewModelScope.launch { container.settings.setThemeMode(mode) } }
    fun setListDensity(density: ListDensity) { viewModelScope.launch { container.settings.setListDensity(density) } }

    /**
     * 导出用：取当前全部未删除条目（含明文密钥，仅在内存中使用）。
     *
     * 解不开密钥的条目（旧格式同步残留）会被排除：导出空密钥等于给出一份坏文件，
     * 再导入时只会得到一堆无效条目。
     */
    suspend fun accountsForExport(): List<com.tessera.otp.data.repo.Account> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            requireRepository().observeActive().first().filter { it.usable }
        }

    /** 导出用：取当前全部未删除分类（Tessera 备份会连同分类一起导出）。 */
    suspend fun categoriesForExport(): List<com.tessera.otp.data.db.entity.CategoryEntity> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            requireRepository().categories()
        }


    /** 注册/取消周期自动同步（转发给 Application 注入的调度器）。 */
    fun scheduleSync(interval: SyncInterval) {
        container.workScheduler?.invoke(interval)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
