package com.tessera.otp

import android.accounts.AccountManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tessera.otp.data.prefs.ListDensity
import com.tessera.otp.data.prefs.ThemeMode
import com.tessera.otp.feature.exporter.ExportFormat
import com.tessera.otp.feature.exporter.Exporter
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tessera.otp.di.AppContainer
import com.tessera.otp.feature.detail.DetailScreen
import com.tessera.otp.feature.home.HomeScreen
import com.tessera.otp.feature.iconpicker.IconPickerScreen
import com.tessera.otp.feature.add.AddAccountSheet
import com.tessera.otp.feature.add.AddGuideDialog
import com.tessera.otp.feature.add.PairingCompleteSheet
import com.tessera.otp.feature.importer.decodeQrFromImage
import com.tessera.otp.feature.importer.scanErrorMessage
import com.tessera.otp.feature.importer.ImportScreen
import com.tessera.otp.feature.sync.SyncScreen
import com.tessera.otp.feature.sync.SyncBackendsScreen
import com.tessera.otp.sync.GoogleDriveAuth
import com.tessera.otp.sync.OneDriveAuth
import com.tessera.otp.feature.settings.SettingsActions
import com.tessera.otp.feature.settings.SettingsScreen
import com.tessera.otp.feature.settings.SettingsSubRoute
import com.tessera.otp.feature.settings.AboutScreen
import com.tessera.otp.feature.settings.UpdateAvailableDialog
import com.tessera.otp.feature.settings.CategoryManageScreen
import com.tessera.otp.feature.settings.CategoryAssignScreen
import com.tessera.otp.feature.settings.SortScreen
import com.tessera.otp.feature.sync.SyncUiState
import com.tessera.otp.feature.trash.TrashScreen
import com.tessera.otp.ui.lock.LockScreen
import com.tessera.otp.ui.theme.TesseraTheme

/**
 * 宿主 Activity。
 *
 * 继承 FragmentActivity 是因为 androidx.biometric 的 BiometricPrompt 需要 FragmentManager。
 */
class MainActivity : FragmentActivity() {

    /** OneDrive 登录回跳（冷启动时先存起来，等 ViewModel 就绪再处理）。 */
    private var pendingAuthIntent: Intent? = null

    /** 正在授权的 Google 账号（从系统账号选择器里选中的邮箱），授权回跳时用它记账。 */
    private var pendingGoogleAccount: String? = null

    /** 系统账号选择器：用户挑一个 Google 账号，回跳后针对该账号发起授权。 */
    private val googleAccountChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val account = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode != RESULT_OK || account.isNullOrBlank()) {
            googleAuthFailed("未选择 Google 账号")
            return@registerForActivityResult
        }
        pendingGoogleAccount = account
        authorizeGoogleAccount(account)
    }

    /**
     * Google Drive 授权：首次授权时 GMS 会返回一个 resolution（PendingIntent），
     * 需要用它启动授权界面，回跳后从 Intent 解析出 access token。
     */
    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val token = GoogleDriveAuth.resultFromIntent(this, result.data)?.accessToken
            if (token.isNullOrBlank()) {
                googleAuthFailed("Google 授权未返回令牌，请重试")
            } else {
                model?.sync?.onGoogleAccountAuthorized(pendingGoogleAccount.orEmpty())
            }
        } else {
            googleAuthFailed("Google 授权已取消")
        }
    }

    /** 针对已选中的账号取令牌：已授权则直接记账，否则弹出授权界面。 */
    private fun authorizeGoogleAccount(account: String) {
        GoogleDriveAuth.authorize(this, account)
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                when {
                    result.hasResolution() && pending != null -> googleAuthLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(pending.intentSender).build()
                    )
                    !result.accessToken.isNullOrBlank() -> model?.sync?.onGoogleAccountAuthorized(account)
                    else -> googleAuthFailed("Google 授权失败：未获得令牌")
                }
            }
            .addOnFailureListener { e -> googleAuthFailed("Google 授权失败：" + GoogleDriveAuth.describe(e)) }
    }

    /** GMS 的授权失败提示（英文错误码对用户无意义，这里统一转成中文）。 */
    private fun googleAuthFailed(reason: String) {
        android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_LONG).show()
    }

    private val container: AppContainer get() = (application as TesseraApp).container
    private var model: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (OneDriveAuth.looksLikeAuthResponse(intent)) pendingAuthIntent = intent

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)

            // 截图开关：默认禁止（FLAG_SECURE 同时屏蔽系统截图与最近任务预览）
            LaunchedEffect(settings?.allowScreenshots) {
                if (settings?.allowScreenshots == true) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            TesseraTheme(
                themeMode = runCatching { ThemeMode.valueOf(settings?.themeMode ?: ThemeMode.AUTO.name) }.getOrDefault(ThemeMode.AUTO),
                dynamicColor = settings?.dynamicColor ?: true
            ) {
                val vm: MainViewModel = viewModel(factory = MainViewModel.factory(container))
                if (model == null) {
                    val manager = BiometricManager.from(this)
                    vm.biometricAvailable = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
                    model = vm
                    consumePendingAuth()
                }
                AppShell(
                    container = container,
                    model = vm,
                    onRequestBiometric = { requestBiometric() },
                    onOneDriveSignIn = { startOneDriveSignIn() },
                    onGoogleSignIn = { startGoogleSignIn() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (OneDriveAuth.looksLikeAuthResponse(intent)) {
            val m = model
            if (m == null) pendingAuthIntent = intent else handleAuthResult(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        model?.onReturnToForeground()
    }

    override fun onPause() {
        model?.onMoveToBackground()
        super.onPause()
    }

    /** 发起 OneDrive 登录（跳系统浏览器，PKCE 由 AppAuth 负责）。 */
    private fun startOneDriveSignIn() {
        if (!OneDriveAuth.isConfigured()) {
            android.widget.Toast.makeText(this, "请先在 OAuthProviders.kt 填入 OneDrive 客户端 ID", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val completion = Intent(this, MainActivity::class.java)
        val cancel = Intent(this, MainActivity::class.java)
        runCatching { OneDriveAuth.signIn(this, completion, cancel) }
            .onFailure { android.widget.Toast.makeText(this, "无法发起登录：" + it.message, android.widget.Toast.LENGTH_LONG).show() }
    }

    /** 发起 Google Drive 授权：先让用户选账号，再针对该账号取令牌（可绑定多个账号）。 */
    private fun startGoogleSignIn() {
        runCatching { googleAccountChooser.launch(GoogleDriveAuth.chooseAccountIntent()) }
            .onFailure {
                googleAuthFailed(
                    if (it is android.content.ActivityNotFoundException) "设备上没有可用的 Google 账号，请先在系统设置里添加"
                    else "无法打开账号选择器：" + it.message
                )
            }
    }

    /** 冷启动回跳：等 ViewModel 就绪后再换取令牌。 */
    private fun consumePendingAuth() {
        val pending = pendingAuthIntent ?: return
        val m = model ?: return
        pendingAuthIntent = null
        handleAuthResult(pending)
    }

    /** 处理登录回跳：成功刷新状态，失败弹出可读原因（否则用户无从排查）。 */
    private fun handleAuthResult(intent: Intent) {
        val m = model ?: return
        lifecycleScope.launch {
            val error = OneDriveAuth.handleRedirect(this@MainActivity, intent)
            if (error == null) {
                m.sync.onOneDriveSignedIn(OneDriveAuth.lastSignedInAccount())
            } else {
                android.widget.Toast.makeText(this@MainActivity, "OneDrive 登录失败：" + error, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 弹出生物识别；失败或取消时静默回到 PIN 输入（不会产生无法解锁的死锁）。 */
    private fun requestBiometric() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    model?.onBiometricSucceeded()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("解锁验证器")
            .setSubtitle("使用指纹或人脸继续")
            .setNegativeButtonText("使用 PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        runCatching { prompt.authenticate(info) }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(
    container: AppContainer,
    model: MainViewModel,
    onRequestBiometric: () -> Unit,
    onOneDriveSignIn: () -> Unit,
    onGoogleSignIn: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // 数据库仓库在异步初始化；未就绪前不访问 home/trash/importer/sync，避免主线程阻塞
    val ready by model.ready.collectAsStateWithLifecycle()
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val homeState by model.home.uiState.collectAsStateWithLifecycle()
    // 首页下拉刷新复用同步页的 ViewModel：同一套「拉取 -> 合并 -> 推送」逻辑与状态
    val homeSyncState by model.sync.state.collectAsStateWithLifecycle()
    val trashItems by model.trash.items.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val locked by model.locked.collectAsStateWithLifecycle()
    val hasPin by model.hasPin.collectAsStateWithLifecycle()
    val screen by model.screen.collectAsStateWithLifecycle()
    val timeStatus by model.timeStatus.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    // 已在首页时再点底部「验证码」：不发导航，只递增该计数让首页滚回顶部
    var homeScrollTopTick by remember { mutableStateOf(0) }
    val justPaired by model.justPaired.collectAsStateWithLifecycle()
    val updateState by model.update.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> model.onMoveToBackground()
                Lifecycle.Event.ON_START -> model.onReturnToForeground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (sheet) {
        AddAccountSheet(
            onDismiss = { sheet = false },
            onManual = {
                sheet = false
                model.openEdit(null)
            },
            onScanned = { raw ->
                sheet = false
                model.onQrText(raw)
            },
            onPickImage = { uri ->
                sheet = false
                scope.launch {
                    val result = decodeQrFromImage(context, uri)
                    val raw = result.getOrNull()
                    if (raw.isNullOrBlank()) {
                        model.importer.reportScanFailure(scanErrorMessage(result.exceptionOrNull()))
                        model.navigate(Screen.Import)
                    } else {
                        model.onQrText(raw)
                    }
                }
            },
            onGuide = { showGuide = true }
        )
    }
    if (showGuide) {
        AddGuideDialog(onDismiss = { showGuide = false })
    }

    // 扫码 / 相册配对成功后：主页上方展示「即将完成」完成页（实时令牌 + 复制）
    // 扫码 / 相册配对成功后：主页上方展示「即将完成」完成页（实时令牌 + 复制）
    if (justPaired.isNotEmpty()) {
        PairingCompleteSheet(items = justPaired, onDismiss = { model.clearJustPaired() })
    }

    if (locked && hasPin) {
        val biometricEnabled = settings?.biometricEnabled ?: true
        // 进入锁屏时若已开启生物识别，自动弹出一次指纹/人脸；
        // key 用 Unit：只在「进入锁屏」这一帧触发一次，取消/失败后静默回落到 PIN，不会反复重弹
        LaunchedEffect(Unit) {
            if (biometricEnabled && model.biometricAvailable) onRequestBiometric()
        }
        LockScreen(
            biometricEnabled = biometricEnabled,
            biometricAvailable = model.biometricAvailable,
            onUnlockWithPin = { pin -> model.unlockWithPin(pin) },
            onUnlockWithBiometric = {
                onRequestBiometric()
                true
            },
            throttleSecondsProvider = { model.throttleSeconds() }
        )
        return
    }

    // 冷启动自动检查更新（解锁后进行）；只有发现新版才会弹窗，失败静默
    LaunchedEffect(Unit) { model.update.autoCheck() }
    updateState.autoUpdate?.let { info ->
        UpdateAvailableDialog(info = info, onDismiss = model.update::dismissAuto)
    }

    val current = settings
    val trashTab = screen is Screen.Trash
    val settingsTab = screen is Screen.Settings
    // 设置相关回调集中一份：设置根页与各二级页共用
    val settingsActions = SettingsActions(
        onCodesVisible = model::setCodesVisible,
        onNextTokenThreshold = model::setNextTokenThreshold,
        onAllowScreenshots = model::setAllowScreenshots,
        onAutoLockEnabled = model::setAutoLockEnabled,
        onAutoLockDelay = model::setAutoLockDelay,
        onBiometric = model::setBiometric,
        onDynamicColor = model::setDynamicColor,
        onTrashRetentionDays = model::setTrashRetention,
        onSetPin = model::setPin,
        onClearPin = model::clearPin,
        onOpenTrash = { model.navigate(Screen.Trash) },
        onOpenImport = { model.navigate(Screen.Import) },
        onOpenSync = { model.navigate(Screen.Sync) },
        onThemeMode = model::setThemeMode,
        onListDensity = model::setListDensity,
        onOpenExport = { model.navigate(Screen.Export) },
        onOpenCategories = { model.navigate(Screen.Categories) },
        onOpenSort = { model.navigate(Screen.Sort) },
        onOpenAbout = { model.navigate(Screen.About) },
        onSyncNetworkTime = model::syncNetworkTime
    )

    Scaffold(
        // 顶部不再重复显示应用名：各页面自带标题（首页为“凭牌”）
        topBar = {},
        bottomBar = {
            if (screen is Screen.Home || trashTab || settingsTab) {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen is Screen.Home,
                        onClick = {
                            if (screen is Screen.Home) homeScrollTopTick++ else model.navigate(Screen.Home)
                        },
                        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        label = { Text("验证码") }
                    )
                    NavigationBarItem(
                        selected = settingsTab,
                        onClick = { model.navigate(Screen.Settings) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("设置") }
                    )
                }
            }
        },
        floatingActionButton = {
            if (screen is Screen.Home) {
                FloatingActionButton(onClick = { sheet = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "新增账号")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val target = screen) {
                is Screen.Home -> HomeScreen(
                    state = homeState,
                    icons = container.icons,
                    onQueryChange = model.home::setQuery,
                    onCategorySelected = model.home::selectCategory,
                    onToggleVisibility = model.home::toggleVisibility,
                    onOpenSort = { model.navigate(Screen.Sort) },
                    onItemClick = { item -> model.openEdit(item.id) },
                    onMoveToTrash = model.home::moveToTrash,
                    syncing = homeSyncState.running,
                    syncStatus = homeSyncState.lastStatus,
                    onRefresh = model.sync::runManualSync,
                    onToggleGroupCollapsed = model.home::toggleGroupCollapsed,
                    onReorderGroups = model.home::commitGroupOrder,
                    density = runCatching { ListDensity.valueOf(settings?.listDensity ?: ListDensity.DEFAULT.name) }.getOrDefault(ListDensity.DEFAULT),
                    scrollToTopRequest = homeScrollTopTick
                )

                is Screen.Trash -> TrashScreen(
                onBack = model::back,
                    items = trashItems,
                    retentionDays = current?.trashRetentionDays ?: 30,
                    onRestore = model.trash::restore,
                    onDeleteForever = model.trash::deleteForever,
                    onEmpty = model.trash::emptyTrash
                )

                is Screen.Settings -> if (current != null) {
                    SettingsScreen(
                        settings = current,
                        hasPin = hasPin,
                        timeStatusText = networkTimeText(timeStatus),
                        actions = settingsActions,
                        onOpenSection = { section -> model.navigate(Screen.SettingsSub(section.key)) }
                    )
                }

                is Screen.SettingsSub -> if (current != null) {
                    SettingsSubRoute(
                        sectionKey = target.section,
                        settings = current,
                        hasPin = hasPin,
                        timeStatusText = networkTimeText(timeStatus),
                        actions = settingsActions,
                        onBack = model::back
                    )
                }

                is Screen.About -> {
                    AboutScreen(
                        updateState = updateState,
                        onCheckUpdate = model.update::check,
                        onDismissUpdate = model.update::dismiss,
                        onBack = model::back
                    )
                }

                is Screen.IconPicker -> IconPickerScreen(
                    icons = container.icons,
                    onPick = { name ->
                        val detail = model.detail()
                        if (detail != null) {
                            detail.update(detail.state.value.form.copy(iconKey = name))
                        }
                        model.navigate(Screen.Edit(detail?.state?.value?.form?.id))
                    },
                    onBack = model::back
                )

                is Screen.Edit -> {
                    val detail = model.detail()
                    if (detail == null) {
                        model.back()
                    } else {
                        val detailState by detail.state.collectAsStateWithLifecycle()
                        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                        // 保存成功后先让抽屉播放滑出动画，再回到列表
                        LaunchedEffect(detailState.saved) {
                            if (detailState.saved) {
                                sheetState.hide()
                                model.back()
                            }
                        }
                        ModalBottomSheet(
                            onDismissRequest = { model.back() },
                            sheetState = sheetState
                        ) {
                            Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (target.accountId == null) "添加账号" else "编辑账号",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { model.back() }) {
                                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                                    }
                                }
                                Box(Modifier.fillMaxWidth().weight(1f)) {
                                    DetailScreen(
                                        form = detailState.form,
                                        categories = detailState.categories,
                                        icons = container.icons,
                                        isNew = detailState.isNew,
                                        onFormChange = detail::update,
                                        onCategoryAdd = detail::addCategory,
                                        onCategoryDelete = detail::deleteCategory,
                                        onPickIcon = { model.navigate(Screen.IconPicker) },
                                        onMoveToTrash = { detail.moveToTrash() },
                                        onSave = { detail.save() },
                                        onBack = { model.back() }
                                    )
                                }
                            }
                        }
                    }
                }

                is Screen.Import -> {
                    val importState by model.importer.state.collectAsStateWithLifecycle()
                    ImportRoute(
                        state = importState,
                        onFileContent = model.importer::parseContent,
                        onScanned = model::onQrText,
                        onScanFailure = model.importer::reportScanFailure,
                        onPassword = model.importer::submitPassword,
                        onConfirm = { model.importer.confirmImport() },
                        onReset = { model.importer.reset() },
                        onBack = model::back
                    )
                }
                is Screen.Export -> ExportRoute(model = model, onBack = model::back)

                is Screen.Sort -> {
                    val sortState by model.sort.state.collectAsStateWithLifecycle()
                    SortScreen(
                        state = sortState,
                        icons = container.icons,
                        onCommitCategories = model.sort::commitCategories,
                        onCommitAccountGroup = model.sort::commitAccountGroup,
                        onBack = model::back
                    )
                }

                is Screen.Categories -> {
                    val categoryState by model.categories.state.collectAsStateWithLifecycle()
                    CategoryManageScreen(
                        state = categoryState,
                        onAdd = model.categories::add,
                        onEdit = model.categories::edit,
                        onDelete = model.categories::delete,
                        onMove = model.categories::move,
                        onManageAccounts = { category -> model.navigate(Screen.CategoryAssign(category.id)) },
                        onBack = model::back
                    )
                }

                is Screen.CategoryAssign -> {
                    val categoryState by model.categories.state.collectAsStateWithLifecycle()
                    val category = categoryState.rows.firstOrNull { it.entity.id == target.categoryId }?.entity
                    if (category == null) {
                        model.back()
                    } else {
                        CategoryAssignScreen(
                            category = category,
                            accounts = categoryState.accounts,
                            categories = categoryState.rows.map { it.entity },
                            onConfirm = { ids -> model.categories.assign(category, ids) },
                            onBack = model::back
                        )
                    }
                }

                is Screen.Sync -> {
                    val syncState by model.sync.state.collectAsStateWithLifecycle()
                    SyncRoute(
                        state = syncState,
                        model = model,
                        onBack = model::back,
                        onOneDriveSignIn = onOneDriveSignIn,
                        onGoogleSignIn = onGoogleSignIn
                    )
                }

                is Screen.SyncBackends -> {
                    val syncState by model.sync.state.collectAsStateWithLifecycle()
                    SyncBackendsRoute(
                        state = syncState,
                        model = model,
                        onBack = model::back,
                        onOneDriveSignIn = onOneDriveSignIn,
                        onGoogleSignIn = onGoogleSignIn
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceholderPage(title: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Text(title, Modifier.padding(24.dp))
        }
    }
}
/** 导入页 + 扫码页的组合路由：两者共用同一个 ImportViewModel。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportRoute(
    state: com.tessera.otp.feature.importer.ImportUiState,
    onFileContent: (String, String) -> Unit,
    onScanned: (String) -> Unit,
    onScanFailure: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            ImportScreen(
                state = state,
                onFileContent = onFileContent,
                onScanned = onScanned,
                onScanFailure = onScanFailure,
                onPassword = onPassword,
                onConfirm = onConfirm,
                onReset = onReset
            )
        }
    }
}

/** 云同步页容器（顶层 AppBar + 返回）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncRoute(
    state: SyncUiState,
    model: MainViewModel,
    onBack: () -> Unit,
    onOneDriveSignIn: () -> Unit,
    onGoogleSignIn: () -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云同步") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            SyncScreen(
                state = state,
                onPassphraseChange = model.sync::updatePassphraseInput,
                onSavePassphrase = model.sync::savePassphrase,
                onInterval = { interval ->
                    model.sync.setInterval(interval)
                    model.scheduleSync(interval)
                },
                onOpenBackends = { model.navigate(Screen.SyncBackends) },
                onSyncNow = model.sync::runManualSync,
                onSyncEnabled = model.sync::setSyncEnabled
            )
        }
    }
}

/** 云同步二级页：后端绑定与云端数据管理。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncBackendsRoute(
    state: SyncUiState,
    model: MainViewModel,
    onBack: () -> Unit,
    onOneDriveSignIn: () -> Unit,
    onGoogleSignIn: () -> Unit
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步后端") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
            SyncBackendsScreen(
                state = state,
                onSaveWebDavServer = model.sync::saveWebDavServer,
                onSaveS3Account = model.sync::saveS3Account,
                onGitHub = model.sync::setGitHub,
                onGist = model.sync::setGist,
                onGitee = model.sync::setGitee,
                onGoogleSignIn = onGoogleSignIn,
                onOneDriveSignIn = onOneDriveSignIn,
                onSetAccountLabel = model.sync::setAccountLabel,
                onRemoveBinding = model.sync::removeBinding,
                onToggleBinding = model.sync::setBindingActive,
                onDeleteCloud = model.sync::deleteCloud
            )
        }
    }
}

/** 导出页：选择格式 -> 明文密钥风险确认 -> SAF 保存到用户选定位置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportRoute(model: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.JSON) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val content = pending
        pending = null
        if (uri != null && content != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                }
            }.isSuccess
            message = if (ok) "已导出到所选位置" else "写入失败，请换一个位置重试"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导出验证码") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "导出文件包含账户与明文密钥，密钥可直接生成验证码，请只保存到可信位置。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            ExportFormat.entries.forEach { option ->
                FilterChip(
                    selected = format == option,
                    onClick = { format = option },
                    label = { Text(option.label) }
                )
            }
            if (format == ExportFormat.TESSERA) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("密码（留空则不加密）") },
                    singleLine = true,
                    supportingText = {
                        Text(if (passphrase.isNotEmpty()) "将用该密码加密整包；忘记密码将无法恢复。" else "留空导出为明文 JSON，便于查看但无保护。")
                    }
                )
            }
            Button(onClick = { confirm = true }) { Text("导出为 " + format.label) }
            message?.let { text -> Text(text, color = MaterialTheme.colorScheme.primary) }
        }
    }

    if (confirm) {
        val encrypting = format == ExportFormat.TESSERA && passphrase.isNotEmpty()
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (encrypting) "确认加密导出？" else "确认导出明文密钥？") },
            text = {
                Text(
                    if (encrypting) "备份将用你设置的密码加密，请牢记：忘记密码将无法恢复。"
                    else "导出的密钥等同于账号本身，可被任何持有者生成验证码。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        val accounts = model.accountsForExport()
                        val categories = if (format == ExportFormat.TESSERA) model.categoriesForExport() else emptyList()
                        pending = Exporter.build(accounts, format, categories, passphrase)
                        saver.launch(format.fileName)
                    }
                }) { Text("继续导出") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}
/** 把校时状态转成设置页要显示的一句话。 */
private fun networkTimeText(status: com.tessera.otp.data.time.TimeSyncStatus): String = when {
    status.synced && status.lastSyncAtMillis > 0L -> {
        val abs = kotlin.math.abs(status.offsetMillis)
        val sign = if (status.offsetMillis >= 0) "+" else "-"
        val minutes = (System.currentTimeMillis() - status.lastSyncAtMillis) / 60_000L
        val ago = if (minutes <= 0L) "刚刚" else minutes.toString() + " 分钟前"
        "已校准 " + sign + (abs / 1000) + "." + (abs % 1000).toString().padStart(3, '0') + " 秒（" + ago + "）"
    }
    status.lastError != null -> "上次校准失败：" + status.lastError + "（继续使用系统时间）"
    else -> "尚未校准，点击立即校准"
}

