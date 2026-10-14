package com.tessera.otp.feature.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tessera.otp.data.prefs.SINGLETON_PROVIDER_ORDER
import com.tessera.otp.data.prefs.SyncInterval
import com.tessera.otp.data.prefs.SyncProviderType
import com.tessera.otp.data.prefs.WebDavServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步设置页：预设 + 添加配置。
 *
 * 页面**只显示已绑定的后端**（[SyncUiState.bindings]）；未绑定的服务不出现在这里，
 * 需要点「添加同步后端」选一种，完成登录或填写凭据后才会加入列表。
 * 后端默认参与同步，可用行内开关临时暂停。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncScreen(
    state: SyncUiState,
    onPassphraseChange: (String) -> Unit,
    onSavePassphrase: () -> Unit,
    onSaveWebDavServer: (WebDavServer) -> Unit,
    onGitHub: (String, String, String) -> Unit,
    onGist: (String) -> Unit,
    onGitee: (String, String, String) -> Unit,
    onInterval: (SyncInterval) -> Unit,
    onSyncEnabled: (Boolean) -> Unit,
    onGoogleSignIn: () -> Unit,
    onOneDriveSignIn: () -> Unit,
    onRemoveBinding: (SyncBinding) -> Unit,
    onToggleBinding: (String, Boolean) -> Unit,
    onDeleteCloud: (CloudTarget) -> Unit,
    onSyncNow: () -> Unit
) {
    var showPassphraseDialog by remember { mutableStateOf(false) }
    var showAddPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CloudTarget?>(null) }
    var pendingRemove by remember { mutableStateOf<SyncBinding?>(null) }
    var editingWebDav by remember { mutableStateOf(false) }
    var webdavInitial by remember { mutableStateOf(WebDavServer(id = "")) }
    var showGitHubDialog by remember { mutableStateOf(false) }
    var showGistDialog by remember { mutableStateOf(false) }
    var showGiteeDialog by remember { mutableStateOf(false) }

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

        Text("同步后端", style = MaterialTheme.typography.labelLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.bindings.isEmpty()) {
                    Text(
                        text = "还没有配置任何同步后端。点下方「添加同步后端」选择一种云服务，" +
                            "登录或填写凭据后即可开始同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.bindings.forEachIndexed { index, binding ->
                        if (index > 0) HorizontalDivider()
                        BindingRow(
                            binding = binding,
                            onToggle = { onToggleBinding(binding.key, it) },
                            onEdit = {
                                when (binding.type) {
                                    SyncProviderType.WEBDAV -> {
                                        webdavInitial = state.webdavServers
                                            .firstOrNull { it.id == binding.webdavServerId }
                                            ?: WebDavServer(id = "")
                                        editingWebDav = true
                                    }
                                    SyncProviderType.GITHUB -> showGitHubDialog = true
                                    SyncProviderType.GIST -> showGistDialog = true
                                    SyncProviderType.GITEE -> showGiteeDialog = true
                                    SyncProviderType.GOOGLE_DRIVE -> onGoogleSignIn()
                                    SyncProviderType.ONEDRIVE -> onOneDriveSignIn()
                                    SyncProviderType.NONE -> Unit
                                }
                            },
                            onRemove = { pendingRemove = binding }
                        )
                    }
                }
                OutlinedButton(onClick = { showAddPicker = true }) { Text("添加同步后端") }
                Text(
                    text = "只有已绑定的后端会参与同步，可用左侧开关临时暂停；" +
                        "WebDAV 可添加多台，各自指定云端文件名。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text("云端数据", style = MaterialTheme.typography.labelLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "删除某个云端里保存的备份文件。只删云端，本机账号与分类不受影响；下次同步会把本机数据重新推上去。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.configuredTargets.isEmpty()) {
                    Text("尚无已配置的后端", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.configuredTargets.forEach { target ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(target.label, modifier = Modifier.weight(1f))
                            TextButton(onClick = { pendingDelete = target }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
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

    if (showAddPicker) {
        AddBackendDialog(
            boundTypes = state.bindings.map { it.type }.toSet(),
            onDismiss = { showAddPicker = false },
            onPick = { type ->
                showAddPicker = false
                when (type) {
                    SyncProviderType.WEBDAV -> {
                        webdavInitial = WebDavServer(id = "")
                        editingWebDav = true
                    }
                    SyncProviderType.GITHUB -> showGitHubDialog = true
                    SyncProviderType.GIST -> showGistDialog = true
                    SyncProviderType.GITEE -> showGiteeDialog = true
                    SyncProviderType.GOOGLE_DRIVE -> onGoogleSignIn()
                    SyncProviderType.ONEDRIVE -> onOneDriveSignIn()
                    SyncProviderType.NONE -> Unit
                }
            }
        )
    }

    if (editingWebDav) {
        WebDavServerDialog(
            initial = webdavInitial,
            onDismiss = { editingWebDav = false },
            onConfirm = { onSaveWebDavServer(it); editingWebDav = false }
        )
    }

    if (showGitHubDialog) {
        GitHubDialog(
            state = state,
            onDismiss = { showGitHubDialog = false },
            onConfirm = { ownerRepo, token, path ->
                onGitHub(ownerRepo, token, path)
                showGitHubDialog = false
            }
        )
    }

    if (showGistDialog) {
        GistDialog(
            state = state,
            onDismiss = { showGistDialog = false },
            onConfirm = { token ->
                onGist(token)
                showGistDialog = false
            }
        )
    }

    if (showGiteeDialog) {
        GiteeDialog(
            state = state,
            onDismiss = { showGiteeDialog = false },
            onConfirm = { ownerRepo, path, token ->
                onGitee(ownerRepo, path, token)
                showGiteeDialog = false
            }
        )
    }

    pendingRemove?.let { binding ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除「" + binding.label + "」？") },
            text = {
                Text(
                    "将从此应用移除「" + binding.label + "」的配置。\n" +
                        "只删本机配置，云端已保存的备份文件不会被删除（如需清除请到「云端数据」里删除）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = binding
                    pendingRemove = null
                    onRemoveBinding(target)
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } }
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除云端数据？") },
            text = {
                Text(
                    "将删除「" + target.label + "」里保存的备份文件，云端此前的备份会消失。\n" +
                        "本机账号、分类与回收站都不受影响；下次同步会把本机数据重新上传。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val deleting = target
                    pendingDelete = null
                    onDeleteCloud(deleting)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

/** 已绑定后端的一行：名称 + 说明 + 参与同步开关 + 编辑/移除。 */
@Composable
private fun BindingRow(
    binding: SyncBinding,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(binding.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = binding.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = binding.active, onCheckedChange = onToggle)
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onEdit) { Text(if (binding.editable) "编辑" else "重新登录") }
            TextButton(onClick = onRemove) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 「添加同步后端」选择窗：WebDAV 可多台，其余每种只能绑一个，已绑定的不再列出。 */
@Composable
private fun AddBackendDialog(
    boundTypes: Set<SyncProviderType>,
    onDismiss: () -> Unit,
    onPick: (SyncProviderType) -> Unit
) {
    val options = buildList {
        add(SyncProviderType.WEBDAV)
        SINGLETON_PROVIDER_ORDER.forEach { if (it !in boundTypes) add(it) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加同步后端") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                options.forEach { type ->
                    ListItem(
                        modifier = Modifier.clickable { onPick(type) },
                        headlineContent = { Text(type.label) },
                        supportingContent = { Text(addHint(type)) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun addHint(type: SyncProviderType): String = when (type) {
    SyncProviderType.WEBDAV -> "自建或第三方的 WebDAV 目录，可添加多台并各自指定文件名"
    SyncProviderType.GOOGLE_DRIVE -> "登录 Google 账号，只访问本应用的隐藏目录"
    SyncProviderType.ONEDRIVE -> "登录微软账号，只访问本应用的私有文件夹"
    SyncProviderType.GITHUB -> "写入你自己的仓库文件（需要 Personal Access Token）"
    SyncProviderType.GIST -> "写入一个私有 Gist（需要 gist 权限的令牌）"
    SyncProviderType.GITEE -> "写入 Gitee 仓库文件（需要私人令牌）"
    SyncProviderType.NONE -> ""
}

/** WebDAV 服务器编辑窗口：备注名（可选）、目录地址、用户名、密码、文件名。 */
@Composable
private fun WebDavServerDialog(
    initial: WebDavServer,
    onDismiss: () -> Unit,
    onConfirm: (WebDavServer) -> Unit
) {
    var label by remember { mutableStateOf(initial.label) }
    var url by remember { mutableStateOf(initial.url) }
    var user by remember { mutableStateOf(initial.user) }
    var password by remember { mutableStateOf(initial.password) }
    var fileName by remember { mutableStateOf(initial.fileName.ifBlank { "tessera.vault" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "添加 WebDAV 服务器" else "编辑 WebDAV 服务器") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("备注名（可选，用于区分多个服务器）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("目录地址") },
                    placeholder = { Text("https://dav.example.com/remote.php/dav/files/me/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码 / 应用专用密码") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("文件名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        initial.copy(
                            label = label.trim(),
                            url = url.trim(),
                            user = user.trim(),
                            password = password,
                            fileName = fileName.trim().ifBlank { "tessera.vault" }
                        )
                    )
                },
                enabled = url.isNotBlank()
            ) { Text("保存并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** GitHub 仓库绑定窗口。 */
@Composable
private fun GitHubDialog(
    state: SyncUiState,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var ownerRepo by remember(state.githubOwnerRepo) { mutableStateOf(state.githubOwnerRepo) }
    var path by remember(state.githubPath) { mutableStateOf(state.githubPath) }
    var token by remember(state.githubToken) { mutableStateOf(state.githubToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.githubToken.isBlank()) "连接 GitHub 仓库" else "编辑 GitHub 仓库") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(ownerRepo, { ownerRepo = it }, label = { Text("owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(path, { path = it }, label = { Text("文件路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text("Personal Access Token（需 contents 读写）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(ownerRepo.trim(), token.trim(), path.trim().ifBlank { "tessera.vault" }) },
                enabled = ownerRepo.count { it == '/' } == 1 && token.isNotBlank()
            ) { Text("保存并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** GitHub Gist 绑定窗口。 */
@Composable
private fun GistDialog(
    state: SyncUiState,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var token by remember(state.gistToken) { mutableStateOf(state.gistToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.gistToken.isBlank()) "连接 GitHub Gist" else "编辑 GitHub Gist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    token,
                    { token = it },
                    label = { Text("Personal Access Token（需 gist 权限）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = if (state.gistId.isBlank()) "首次同步会自动创建一个私有 Gist 并记录 ID"
                    else "将使用已创建的 Gist：" + state.gistId,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(token.trim()) }, enabled = token.isNotBlank()) { Text("保存并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Gitee（码云）绑定窗口：owner/repo、文件路径、私人令牌。 */
@Composable
private fun GiteeDialog(
    state: SyncUiState,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var ownerRepo by remember(state.giteeOwnerRepo) { mutableStateOf(state.giteeOwnerRepo) }
    var path by remember(state.giteePath) { mutableStateOf(state.giteePath) }
    var token by remember(state.giteeToken) { mutableStateOf(state.giteeToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.giteeToken.isBlank()) "连接 Gitee" else "编辑 Gitee") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                Text(
                    text = "令牌在 Gitee → 设置 → 私人令牌创建，需勾选 projects 权限；需先在仓库里建好目标分支（默认 master）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(ownerRepo.trim(), path.trim().ifBlank { "tessera.vault" }, token.trim()) },
                enabled = ownerRepo.count { it == '/' } == 1 && token.isNotBlank()
            ) { Text("保存并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
