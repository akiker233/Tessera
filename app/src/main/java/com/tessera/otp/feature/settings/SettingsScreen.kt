package com.tessera.otp.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
                    onClick = { onOpenSection(SettingsSection.APPEARANCE) }
                )
                HorizontalDivider()
                ChoiceRow(
                    title = SettingsSection.SECURITY.title,
                    subtitle = "自动锁定：" + (if (settings.autoLockEnabled && hasPin) "开（" + delayLabel + "）" else "关") +
                        " · PIN：" + (if (hasPin) "已设置" else "未设置"),
                    onClick = { onOpenSection(SettingsSection.SECURITY) }
                )
                HorizontalDivider()
                ChoiceRow(
                    title = SettingsSection.TIME.title,
                    subtitle = timeStatusText,
                    onClick = { onOpenSection(SettingsSection.TIME) }
                )
                HorizontalDivider()
                ChoiceRow(
                    title = SettingsSection.DATA.title,
                    subtitle = "回收站保留 " + settings.trashRetentionDays + " 天 · 自动同步 " +
                        (if (settings.syncEnabled) intervalLabel else "已关闭"),
                    onClick = { onOpenSection(SettingsSection.DATA) }
                )
            }
        }

        // 分类管理是独立页面（不属于任何分区），直接挂在根页，省得先进「数据」再找
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            ChoiceRow(
                title = "分类管理",
                subtitle = "新建 / 重命名 / 改色 / 删除 / 排序 · 选择账号归类（首页可按分类分组查看）",
                onClick = actions.onOpenCategories
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
private fun ChoiceRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
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
