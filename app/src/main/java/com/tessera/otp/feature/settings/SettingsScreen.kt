package com.tessera.otp.feature.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tessera.otp.BuildConfig
import com.tessera.otp.R
import com.tessera.otp.data.prefs.AppSettings
import com.tessera.otp.data.prefs.AutoLockDelay
import com.tessera.otp.data.prefs.ListDensity
import com.tessera.otp.data.prefs.SyncInterval
import com.tessera.otp.data.prefs.ThemeMode

/** 设置页回调：所有持久化都交给 MainActivity 里的 AppContainer。 */
data class SettingsActions(
    val onCodesVisible: (Boolean) -> Unit,
    val onNextTokenThreshold: (Int) -> Unit,
    val onAllowScreenshots: (Boolean) -> Unit,
    val onAutoLockEnabled: (Boolean) -> Unit,
    val onAutoLockDelay: (AutoLockDelay) -> Unit,
    val onBiometric: (Boolean) -> Unit,
    val onDynamicColor: (Boolean) -> Unit,
    val onTrashRetentionDays: (Int) -> Unit,
    val onSetPin: (String) -> Unit,
    val onClearPin: () -> Unit,
    val onOpenTrash: () -> Unit,
    val onOpenImport: () -> Unit,
    val onOpenSync: () -> Unit,
    val onThemeMode: (ThemeMode) -> Unit,
    val onListDensity: (ListDensity) -> Unit,
    val onOpenExport: () -> Unit,
    val onOpenCategories: () -> Unit,
    val onOpenSort: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onSyncNetworkTime: () -> Unit
)

/**
 * 设置分区：设置根页只列这几个入口，具体设置项都放在各自对应的二级页里，
 * 避免根页把所有开关堆成一条长列表。
 */
enum class SettingsSection(val key: String, val title: String) {
    APPEARANCE("appearance", "外观"),
    SECURITY("security", "安全"),
    TIME("time", "时间"),
    DATA("data", "数据")
}

/**
 * 设置根页：只有分区入口，每行右侧的副标题给出该分区的当前状态摘要。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    hasPin: Boolean,
    timeStatusText: String,
    actions: SettingsActions,
    onOpenSection: (SettingsSection) -> Unit
) {
    val themeLabel = runCatching { ThemeMode.valueOf(settings.themeMode).label }.getOrDefault("自动")
    val densityLabel = runCatching { ListDensity.valueOf(settings.listDensity).label }.getOrDefault("默认")
    val delayLabel = runCatching { AutoLockDelay.valueOf(settings.autoLockDelayName).label }.getOrDefault("30 秒")
    val intervalLabel = runCatching { SyncInterval.valueOf(settings.syncIntervalName).label }.getOrDefault("关闭")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth()
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                ChoiceRow(
                    title = SettingsSection.APPEARANCE.title,
                    subtitle = "主题：" + themeLabel + " · 列表：" + densityLabel +
                        " · 验证码：" + (if (settings.codesVisible) "显示" else "隐藏") +
                        " · 下一令牌：" + nextTokenSummary(settings.nextTokenThresholdSeconds),
                    icon = Icons.Filled.Palette,
                    onClick = { onOpenSection(SettingsSection.APPEARANCE) }
                )
                HorizontalDivider()
                ChoiceRow(
                    title = SettingsSection.SECURITY.title,
                    subtitle = "自动锁定：" + (if (settings.autoLockEnabled && hasPin) "开（" + delayLabel + "）" else "关") +
                        " · PIN：" + (if (hasPin) "已设置" else "未设置"),
                    icon = Icons.Filled.Lock,
                    onClick = { onOpenSection(SettingsSection.SECURITY) }
                )
                HorizontalDivider()
                ChoiceRow(
                    title = SettingsSection.TIME.title,
                    subtitle = timeStatusText,
                    icon = Icons.Filled.AccessTime,
                    onClick = { onOpenSection(SettingsSection.TIME) }
                )
                HorizontalDivider()
                ChoiceRow(
                    title = SettingsSection.DATA.title,
                    subtitle = "回收站保留 " + settings.trashRetentionDays + " 天 · 自动同步 " +
                        (if (settings.syncEnabled) intervalLabel else "已关闭"),
                    icon = Icons.Filled.Storage,
                    onClick = { onOpenSection(SettingsSection.DATA) }
                )
            }
        }

        // 分类管理是独立页面（不属于任何分区），直接挂在根页，省得先进「数据」再找
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ChoiceRow(
                title = "分类管理",
                subtitle = "新建 / 重命名 / 改色 / 删除 · 选择账号归类（首页可按分类分组查看）",
                icon = Icons.Filled.Category,
                onClick = actions.onOpenCategories
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ChoiceRow(
                title = "排序",
                subtitle = "拖动调整分类文件夹与验证码的显示顺序",
                icon = Icons.Filled.SwapVert,
                onClick = actions.onOpenSort
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ChoiceRow(
                title = "关于",
                subtitle = "版本 " + BuildConfig.VERSION_NAME + " · 开源许可与项目地址",
                icon = Icons.Filled.Info,
                onClick = actions.onOpenAbout
            )
        }
    }
}

/** 二级设置页的分发：路由里带的是分区 key，未知 key 回退到「外观」。 */
@Composable
fun SettingsSubRoute(
    sectionKey: String,
    settings: AppSettings,
    hasPin: Boolean,
    timeStatusText: String,
    actions: SettingsActions,
    onBack: () -> Unit
) {
    when (SettingsSection.entries.firstOrNull { it.key == sectionKey } ?: SettingsSection.APPEARANCE) {
        SettingsSection.APPEARANCE -> AppearanceSettingsScreen(settings, actions, onBack)
        SettingsSection.SECURITY -> SecuritySettingsScreen(settings, hasPin, actions, onBack)
        SettingsSection.TIME -> TimeSettingsScreen(timeStatusText, actions, onBack)
        SettingsSection.DATA -> DataSettingsScreen(settings, actions, onBack)
    }
}

/** 关于页：版本、检查更新、开源许可与项目地址。 */
@Composable
fun AboutScreen(
    updateState: UpdateUiState,
    onCheckUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    SettingsPage("关于", onBack) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppIconBadge(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                )
                Text("Tessera · 凭牌", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "版本 " + BuildConfig.VERSION_NAME + "（build " + BuildConfig.VERSION_CODE + "）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ChoiceRow(
                title = "检查更新",
                subtitle = when (val dialog = updateState.dialog) {
                    is UpdateDialogState.Available -> "发现新版本 " + dialog.info.latestVersion
                    is UpdateDialogState.UpToDate -> "已是最新版本"
                    is UpdateDialogState.Failed -> "检查失败，点按重试"
                    null -> if (updateState.checking) "正在检查…"
                    else "当前版本 " + BuildConfig.VERSION_NAME + " · 从 GitHub 获取最新版本"
                },
                icon = Icons.Filled.Refresh,
                onClick = onCheckUpdate
            )
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                InfoRow(title = "版本", value = BuildConfig.VERSION_NAME + "（build " + BuildConfig.VERSION_CODE + "）")
                HorizontalDivider()
                InfoRow(title = "开源许可", value = "GNU General Public License v3.0")
                HorizontalDivider()
                ChoiceRow(
                    title = "项目主页",
                    subtitle = REPO_URL.removePrefix("https://"),
                    icon = Icons.Filled.Public,
                    onClick = { openHttps(context, REPO_URL) }
                )
            }
        }

        Text(
            text = "本地优先的 TOTP 验证器：条目密钥由 Android Keystore 主密钥加密后写入加密数据库，" +
                "云同步包用独立的同步口令加密，服务商只能看到密文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Copyright (C) 2026 akiker233 · 基于 GPL-3.0 发布，不提供任何担保。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    when (val dialog = updateState.dialog) {
        is UpdateDialogState.Available -> AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text("发现新版本 " + dialog.info.latestVersion) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前版本 " + BuildConfig.VERSION_NAME + "，可更新到 " + dialog.info.latestVersion + "。")
                    if (dialog.info.notes.isNotBlank()) {
                        val notes = dialog.info.notes.trim()
                        Text(
                            text = notes.take(1200) + if (notes.length > 1200) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDismissUpdate()
                    openHttps(context, dialog.info.downloadUrl ?: dialog.info.releaseUrl)
                }) {
                    Text(if (dialog.info.downloadUrl != null) "下载更新" else "前往下载")
                }
            },
            dismissButton = { TextButton(onClick = onDismissUpdate) { Text("稍后") } }
        )

        is UpdateDialogState.UpToDate -> AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text("已是最新版本") },
            text = { Text("当前版本 " + dialog.currentVersion + " 已是最新。") },
            confirmButton = { TextButton(onClick = onDismissUpdate) { Text("好") } }
        )

        is UpdateDialogState.Failed -> AlertDialog(
            onDismissRequest = onDismissUpdate,
            title = { Text("检查更新失败") },
            text = { Text(dialog.message) },
            confirmButton = {
                TextButton(onClick = {
                    onDismissUpdate()
                    onCheckUpdate()
                }) { Text("重试") }
            },
            dismissButton = { TextButton(onClick = onDismissUpdate) { Text("取消") } }
        )

        null -> Unit
    }
}

private const val REPO_URL = "https://github.com/akiker233/Tessera"

/** 只允许打开 HTTPS 链接：下载地址来自外部 API，避免被引导到自定义 scheme。 */
private fun openHttps(context: Context, url: String) {
    val uri = Uri.parse(url)
    if (uri.scheme != "https") return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}

/**
 * 应用图标。启动图标是自适应图标（XML 图层），Compose 的 painterResource 只能加载矢量/位图资源，
 * 因此这里把真实图标画进位图再显示——顺带拿到与桌面完全一致的观感。
 */
@Composable
private fun AppIconBadge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon = remember {
        runCatching {
            val px = (96 * context.resources.displayMetrics.density).toInt()
            val drawable = context.getDrawable(R.mipmap.ic_launcher) ?: return@runCatching null
            drawable.setBounds(0, 0, px, px)
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = modifier)
    }
}

/** 二级设置页外壳：返回箭头 + 标题，内容可滚动。 */
@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    // 系统返回键也回到设置根页
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun AppearanceSettingsScreen(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    var showNextTokenDialog by remember { mutableStateOf(false) }

    SettingsPage("外观", onBack) {
        SectionTitle("主题")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                ListItem(
                    headlineContent = { Text("主题") },
                    supportingContent = { Text("自动 / 浅色 / 深色") }
                )
                Row(Modifier.padding(start = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = ThemeMode.valueOf(settings.themeMode) == mode,
                            onClick = { actions.onThemeMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }
        }

        SectionTitle("列表布局")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                ListItem(
                    headlineContent = { Text("列表布局") },
                    supportingContent = { Text("大 / 默认 / 紧凑") }
                )
                Row(Modifier.padding(start = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListDensity.entries.forEach { density ->
                        FilterChip(
                            selected = ListDensity.valueOf(settings.listDensity) == density,
                            onClick = { actions.onListDensity(density) },
                            label = { Text(density.label) }
                        )
                    }
                }
            }
        }

        SectionTitle("显示")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                SwitchRow(
                    title = "显示验证码",
                    subtitle = "关闭后列表只显示占位符，可随时点顶部眼睛切换",
                    checked = settings.codesVisible,
                    onCheckedChange = actions.onCodesVisible
                )
                HorizontalDivider()
                SwitchRow(
                    title = "使用动态取色",
                    subtitle = "跟随系统壁纸配色（Android 12+）",
                    checked = settings.dynamicColor,
                    onCheckedChange = actions.onDynamicColor
                )
                HorizontalDivider()
                ChoiceRow(
                    title = "即将过期时显示下一个令牌",
                    subtitle = nextTokenSummary(settings.nextTokenThresholdSeconds),
                    onClick = { showNextTokenDialog = true }
                )
            }
        }
    }

    if (showNextTokenDialog) {
        NextTokenDialog(
            current = settings.nextTokenThresholdSeconds,
            onDismiss = { showNextTokenDialog = false },
            onConfirm = { seconds ->
                actions.onNextTokenThreshold(seconds)
                showNextTokenDialog = false
            }
        )
    }
}

@Composable
private fun SecuritySettingsScreen(
    settings: AppSettings,
    hasPin: Boolean,
    actions: SettingsActions,
    onBack: () -> Unit
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var showClearPinConfirm by remember { mutableStateOf(false) }

    SettingsPage("安全", onBack) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                SwitchRow(
                    title = "自动锁定",
                    subtitle = if (hasPin) "回到后台或启动时要求解锁" else "需先设置 PIN 才能开启",
                    checked = settings.autoLockEnabled && hasPin,
                    onCheckedChange = actions.onAutoLockEnabled,
                    enabled = hasPin,
                    // 未设置 PIN 时点这一行＝直接去设置 PIN，省得用户自己找上面那一行
                    onDisabledTap = { showPinDialog = true }
                )
                HorizontalDivider()
                ChoiceRow(
                    title = "锁定延迟",
                    subtitle = AutoLockDelay.valueOf(settings.autoLockDelayName).label,
                    onClick = { showAutoLockDialog = true }
                )
                HorizontalDivider()
                SwitchRow(
                    title = "生物识别解锁",
                    subtitle = if (hasPin) "指纹/人脸，不可用时回退 PIN" else "需先设置 PIN 才能开启",
                    checked = settings.biometricEnabled && hasPin,
                    onCheckedChange = actions.onBiometric,
                    enabled = hasPin,
                    onDisabledTap = { showPinDialog = true }
                )
                HorizontalDivider()
                SwitchRow(
                    title = "允许截图",
                    subtitle = "关闭时系统截图与最近任务预览都会被屏蔽",
                    checked = settings.allowScreenshots,
                    onCheckedChange = actions.onAllowScreenshots
                )
                HorizontalDivider()
                ChoiceRow(
                    title = if (hasPin) "修改 PIN" else "设置 PIN",
                    subtitle = if (hasPin) "已设置" else "尚未设置",
                    onClick = { showPinDialog = true }
                )
                if (hasPin) {
                    HorizontalDivider()
                    ChoiceRow(
                        title = "清除 PIN",
                        subtitle = "同时关闭锁定功能",
                        onClick = { showClearPinConfirm = true }
                    )
                }
            }
        }
    }

    if (showPinDialog) {
        PinDialog(
            onDismiss = { showPinDialog = false },
            onConfirm = { pin ->
                actions.onSetPin(pin)
                showPinDialog = false
            }
        )
    }

    if (showAutoLockDialog) {
        AutoLockDelayDialog(
            current = AutoLockDelay.valueOf(settings.autoLockDelayName),
            onDismiss = { showAutoLockDialog = false },
            onConfirm = { delay ->
                actions.onAutoLockDelay(delay)
                showAutoLockDialog = false
            }
        )
    }

    if (showClearPinConfirm) {
        AlertDialog(
            onDismissRequest = { showClearPinConfirm = false },
            title = { Text("清除 PIN") },
            text = { Text("清除后不再需要 PIN 解锁，应用锁与生物识别都会关闭。要清除吗？") },
            confirmButton = {
                TextButton(onClick = {
                    actions.onClearPin()
                    showClearPinConfirm = false
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { showClearPinConfirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TimeSettingsScreen(timeStatusText: String, actions: SettingsActions, onBack: () -> Unit) {
    SettingsPage("时间", onBack) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ChoiceRow(
                title = "网络时间校准",
                subtitle = timeStatusText,
                onClick = actions.onSyncNetworkTime
            )
        }
    }
}

@Composable
private fun DataSettingsScreen(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    var showTrashRetention by remember { mutableStateOf(false) }

    SettingsPage("数据", onBack) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                ChoiceRow(
                    title = "回收站",
                    subtitle = "查看与恢复已删除的验证码",
                    onClick = actions.onOpenTrash
                )
                HorizontalDivider()
                ChoiceRow(
                    title = "回收站保留时间",
                    subtitle = "保留 " + settings.trashRetentionDays + " 天（点击修改）",
                    onClick = { showTrashRetention = true }
                )
                HorizontalDivider()
                ChoiceRow(
                    title = "导入",
                    subtitle = "2FAS / Aegis / andOTP / Raivo / LastPass / Google Authenticator / Tessera",
                    onClick = actions.onOpenImport
                )
                HorizontalDivider()
                ChoiceRow(
                    title = "导出验证码",
                    subtitle = "Tessera 备份（可加密）/ JSON / otpauth / CSV",
                    onClick = actions.onOpenExport
                )
            }
        }

        SectionTitle("云同步")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ChoiceRow(
                title = "云同步",
                subtitle = settings.lastSyncStatus,
                onClick = actions.onOpenSync
            )
        }
    }

    if (showTrashRetention) {
        RetentionDialog(
            current = settings.trashRetentionDays,
            onDismiss = { showTrashRetention = false },
            onConfirm = { days ->
                actions.onTrashRetentionDays(days)
                showTrashRetention = false
            }
        )
    }
}

/** 「下一个令牌」阈值文案，根页摘要与二级页共用。 */
private fun nextTokenSummary(seconds: Int): String =
    if (seconds <= 0) "不显示" else "剩余 " + seconds + " 秒时显示"

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    )
}

/**
 * 设置行 + 开关。enabled = false 时开关灰掉且不可切换（例如「未设置 PIN 不能开自动锁定」），
 * 整行仍可点击并回调 onDisabledTap，用来给出「为什么不能开」的下一步入口。
 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    onDisabledTap: (() -> Unit)? = null
) {
    val dimmed = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    ListItem(
        // 禁用态整行仍可点：用于给出「为什么不能开」的下一步入口
        modifier = if (!enabled && onDisabledTap != null) Modifier.clickable(onClick = onDisabledTap) else Modifier,
        headlineContent = { Text(title, color = if (enabled) Color.Unspecified else dimmed) },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (enabled) Color.Unspecified else dimmed)
        },
        trailingContent = {
            Switch(
                checked = checked,
                // 禁用态传 null：开关渲染成灰色读值但完全不接受点击，
                // 这样点击会落到整行（onDisabledTap），而不是被 Switch 自己吞掉
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled
            )
        }
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: ImageVector? = null
) {
    // 用 Row 而非 ListItem：ListItem 的 leading/trailing 是垂直居中的，
    // 副标题一换行图标就被压到整行中间，与只有一行副标题的行对不齐。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
    }
}

/** 只读信息行：没有点击行为，因此不带右侧箭头。 */
@Composable
private fun InfoRow(title: String, value: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value, style = MaterialTheme.typography.bodySmall) }
    )
}

@Composable
private fun PinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val valid = pin.length in 4..12 && pin == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置 PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit) },
                    label = { Text("PIN（4-12 位数字）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit) },
                    label = { Text("再输一次") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    isError = confirm.isNotEmpty() && confirm != pin
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pin) }, enabled = valid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RetentionDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val options = listOf(7, 30, 90, 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回收站保留时间") },
        text = {
            Column {
                options.forEach { days ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(days) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == days, onClick = { onConfirm(days) })
                        Text(if (days == 0) "永久保留（不自动清理）" else days.toString() + " 天")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 选择「即将过期时显示下一个令牌」的阈值：3/5/10 秒，或完全关闭。 */
@Composable
private fun NextTokenDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val options = listOf(3, 5, 10, 0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("显示下一个令牌") },
        text = {
            Column {
                options.forEach { seconds ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(seconds) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == seconds, onClick = { onConfirm(seconds) })
                        Text(if (seconds == 0) "不显示" else "剩余 " + seconds + " 秒时显示")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 锁定延迟选择（与其它同类设置一致：点击弹单选，不再点击循环）。 */
@Composable
private fun AutoLockDelayDialog(
    current: AutoLockDelay,
    onDismiss: () -> Unit,
    onConfirm: (AutoLockDelay) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("锁定延迟") },
        text = {
            Column {
                AutoLockDelay.entries.forEach { delay ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onConfirm(delay) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == delay, onClick = { onConfirm(delay) })
                        Text(delay.label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
