package com.tessera.otp.feature.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.tessera.otp.sync.GoogleDriveProvider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tessera.otp.data.prefs.SyncInterval
import com.tessera.otp.data.prefs.SyncProviderType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步设置页：选择后端、填写凭据、设置同步口令、手动同步与自动周期、查看同步状态。
 *
 * Google Drive / OneDrive 需要用户先完成 OAuth（代码里是占位常量），
 * 未配置时点击同步会给出明确提示，而不是静默失败。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncScreen(
    state: SyncUiState,
    onSelectProvider: (SyncProviderType) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onSavePassphrase: () -> Unit,
    onWebDav: (String, String, String) -> Unit,
    onGitHub: (String, String, String) -> Unit,
    onGist: (String) -> Unit,
    onGitee: (String, String, String) -> Unit,
    onInterval: (SyncInterval) -> Unit,
    onSyncEnabled: (Boolean) -> Unit,
    onGoogleSignIn: () -> Unit,
    onToggleProvider: (SyncProviderType, Boolean) -> Unit,
    onClearGoogleAccount: () -> Unit,
    onOneDriveSignIn: () -> Unit,
    onOneDriveSignOut: () -> Unit,
    onSyncNow: () -> Unit
) {
    var showPassphraseDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "云端保存的是加密文件，只有同步口令能解开；请用足够强的口令，换机时输入同一口令即可恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("同步状态", style = MaterialTheme.typography.labelLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp)) {
                Text(state.lastStatus, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (state.lastSyncAt > 0) "上次成功：" + formatTime(state.lastSyncAt) else "还没有成功同步记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.gistId.isNotBlank()) {
                    Text("Gist ID：" + state.gistId, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Button(onClick = onSyncNow, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.running) "同步中…" else "立即同步")
        }

        state.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Text("同步方式", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SyncProviderType.entries.filter { it != SyncProviderType.NONE }.forEach { type ->
                FilterChip(
                    selected = state.provider == type,
                    onClick = { onSelectProvider(type) },
                    label = { Text(type.label) }
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // 口令不再内联编辑，改为点开后弹二级窗口输入
            ListItem(
                modifier = Modifier.clickable { showPassphraseDialog = true },
                headlineContent = { Text("同步口令") },
                supportingContent = {
                    Text(if (state.passphraseSet) "已设置（点击修改）" else "尚未设置（点击设置）")
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            )
        }

        when (state.provider) {
            SyncProviderType.WEBDAV -> WebDavForm(state, onWebDav)
            SyncProviderType.GITHUB -> GitHubForm(state, onGitHub)
            SyncProviderType.GIST -> GistForm(state, onGist)
            SyncProviderType.GITEE -> GiteeForm(state, onGitee)
            SyncProviderType.GOOGLE_DRIVE -> GoogleDriveCard(state, onGoogleSignIn, onClearGoogleAccount)
            SyncProviderType.ONEDRIVE -> OneDriveCard(state, onOneDriveSignIn, onOneDriveSignOut)
            SyncProviderType.NONE -> HintCard("未选择", "请选择一种同步方式。WebDAV 与 GitHub/Gist 只需填写地址与令牌即可使用。")
        }

        Text("同时同步", style = MaterialTheme.typography.labelLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "当前选择的后端默认参与同步；勾选的后端会在每次同步时与它一起读写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(
                    SyncProviderType.WEBDAV,
                    SyncProviderType.GIST,
                    SyncProviderType.GITHUB,
                    SyncProviderType.GITEE,
                    SyncProviderType.GOOGLE_DRIVE,
                    SyncProviderType.ONEDRIVE
                ).filter { it != state.provider }.forEach { type ->
                    val checked = type.name in state.enabledProviders
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleProvider(type, !checked) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { onToggleProvider(type, it) })
                        Text(type.label)
                    }
                }
            }
        }

        Text("自动同步", style = MaterialTheme.typography.labelLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                ListItem(
                    headlineContent = { Text("启用自动同步") },
                    supportingContent = {
                        Text(
                            if (state.syncEnabled)
                                "冷启动、账号变更后与后台周期都会自动同步"
                            else
                                "已关闭：只有下拉刷新与「立即同步」会同步"
                        )
                    },
                    trailingContent = {
                        Switch(checked = state.syncEnabled, onCheckedChange = onSyncEnabled)
                    }
                )
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "同步周期",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.syncEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SyncInterval.entries.forEach { interval ->
                            FilterChip(
                                selected = state.interval == interval,
                                onClick = { onInterval(interval) },
                                enabled = state.syncEnabled,
                                label = { Text(interval.label) }
                            )
                        }
                    }
                }
            }
        }

    }

    if (showPassphraseDialog) {
        PassphraseDialog(
            isUpdate = state.passphraseSet,
            onDismiss = { showPassphraseDialog = false },
            onConfirm = { value ->
                onPassphraseChange(value)
                onSavePassphrase()
                showPassphraseDialog = false
            }
        )
    }
}

@Composable
private fun WebDavForm(state: SyncUiState, onWebDav: (String, String, String) -> Unit) {
    var url by remember(state.webdavUrl) { mutableStateOf(state.webdavUrl) }
    var user by remember(state.webdavUser) { mutableStateOf(state.webdavUser) }
    var password by remember(state.webdavPassword) { mutableStateOf(state.webdavPassword) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WebDAV", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(url, { url = it }, label = { Text("目录地址（如 https://dav.example.com/remote.php/dav/files/me/）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text("密码 / 应用专用密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { onWebDav(url, user, password) }) { Text("保存并连接") }
        }
    }
}

@Composable
private fun GitHubForm(state: SyncUiState, onGitHub: (String, String, String) -> Unit) {
    var ownerRepo by remember(state.githubOwnerRepo) { mutableStateOf(state.githubOwnerRepo) }
    var token by remember(state.githubToken) { mutableStateOf(state.githubToken) }
    var path by remember(state.githubPath) { mutableStateOf(state.githubPath) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GitHub 仓库", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(ownerRepo, { ownerRepo = it }, label = { Text("owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(path, { path = it }, label = { Text("文件路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(token, { token = it }, label = { Text("Personal Access Token（需 contents 读写）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { onGitHub(ownerRepo, token, path) }) { Text("保存并连接") }
        }
    }
}

@Composable
private fun GistForm(state: SyncUiState, onGist: (String) -> Unit) {
    var token by remember(state.gistToken) { mutableStateOf(state.gistToken) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GitHub Gist", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(token, { token = it }, label = { Text("Personal Access Token（需 gist 权限）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { onGist(token) }) { Text("保存并连接") }
            Text(
                text = if (state.gistId.isBlank()) "首次同步会自动创建一个私有 Gist 并记录 ID" else "将使用已创建的 Gist：" + state.gistId,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HintCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
/**
 * Google Drive 卡片。
 *
 * 当前实现只维护「账号邮箱」这一个 Drive 后端真正需要的输入：GoogleAccountCredential 会依据
 * 账号名与 drive.appdata 范围向 Google Play 服务申请令牌。
 *
 * 待办（方案 B 未完成）：改用 AndroidX Credential Manager + GoogleIdTokenCredential 做一键登录。
 * 相关依赖已暂时移除（避免出现「依赖已加、功能未接」的误导状态），
 * 接一键登录时再按官方示例引入并单独验证。
 */
@Composable
private fun GoogleDriveCard(
    state: SyncUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    val connected = state.googleAccount.isNotBlank()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Google Drive", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (connected) {
                    "已连接（Google 授权有效）；令牌由 Google Play 服务托管并自动刷新。"
                } else {
                    "尚未连接。登录后本应用只能访问它自己的隐藏目录（appDataFolder），看不到你其它 Drive 文件。"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSignIn) {
                    Text(if (connected) "重新授权" else "登录 Google 账号")
                }
                if (connected) {
                    OutlinedButton(onClick = onSignOut) { Text("断开") }
                }
            }
            if (!connected) {
                Text(
                    text = "需先在 Google Cloud 控制台启用 Drive API，并建 Android 类型 OAuth 客户端，登记当前包名 + SHA-1" +
                        "（调试包为 com.tessera.otp.debug）；无需客户端 ID。详见 docs/google-drive-setup.md。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
/** Gitee（码云）表单：owner/repo、文件路径、私人令牌。 */
@Composable
private fun GiteeForm(state: SyncUiState, onGitee: (String, String, String) -> Unit) {
    var ownerRepo by remember(state.giteeOwnerRepo) { mutableStateOf(state.giteeOwnerRepo) }
    var path by remember(state.giteePath) { mutableStateOf(state.giteePath) }
    var token by remember(state.giteeToken) { mutableStateOf(state.giteeToken) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gitee", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(ownerRepo, { ownerRepo = it }, label = { Text("owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(path, { path = it }, label = { Text("文件路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                token,
                { token = it },
                label = { Text("私人令牌") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(onClick = { onGitee(ownerRepo, path, token) }) { Text("保存并连接") }
            Text(
                text = "令牌在 Gitee → 设置 → 私人令牌创建，需勾选 projects 权限；需先在仓库里建好目标分支（默认 master）。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    
}
/** 同步口令的二级窗口：设置/修改口令。口令用于加密云端同步包，丢失后云端数据无法解密。 */
@Composable
private fun PassphraseDialog(
    isUpdate: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isUpdate) "修改同步口令" else "设置同步口令") },
        text = {
            Column {
                Text(
                    text = "口令用于加密云端同步包，至少 8 位。请务必牢记：丢失后无法解密云端已有的备份。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("同步口令") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input) },
                enabled = input.length >= 8
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
/** OneDrive 卡片：显示连接状态，提供登录 / 重新登录 / 断开。 */
@Composable
private fun OneDriveCard(
    state: SyncUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OneDrive", style = MaterialTheme.typography.titleSmall)
            Text(
                text = if (state.oneDriveConnected) {
                    "已连接微软账号；令牌过期会自动用 refresh token 续期。"
                } else {
                    "尚未连接。登录后本应用只能访问它自己的私有文件夹（approot），看不到你其它 OneDrive 文件。"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSignIn) {
                    Text(if (state.oneDriveConnected) "重新登录" else "登录微软账号")
                }
                if (state.oneDriveConnected) {
                    OutlinedButton(onClick = onSignOut) { Text("断开") }
                }
            }
            if (!state.oneDriveConnected) {
                Text(
                    text = "需先在 Azure 应用注册中登记重定向 URI tessera://auth 与委派权限 Files.ReadWrite.AppFolder，" +
                        "再把客户端 ID 写入项目根目录 local.properties 的 ONEDRIVE_CLIENT_ID。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

