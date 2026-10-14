package com.tessera.otp.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tessera.otp.data.prefs.MULTI_INSTANCE_PROVIDER_ORDER
import com.tessera.otp.data.prefs.S3Account
import com.tessera.otp.data.prefs.SINGLETON_PROVIDER_ORDER
import com.tessera.otp.data.prefs.SyncProviderType
import com.tessera.otp.data.prefs.WebDavServer
import com.tessera.otp.data.prefs.accountDisplayName

/**
 * 云同步二级页：后端绑定与云端数据管理。
 *
 * 从云同步主页拆出来，主页只留状态、口令与自动同步；需要增删后端或删除云端备份时进这一页。
 */
@Composable
fun SyncBackendsScreen(
    state: SyncUiState,
    onSaveWebDavServer: (WebDavServer) -> Unit,
    onSaveS3Account: (S3Account) -> Unit,
    onGitHub: (String, String, String, String) -> Unit,
    onGist: (String, String) -> Unit,
    onGitee: (String, String, String, String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onOneDriveSignIn: () -> Unit,
    onSetAccountLabel: (SyncProviderType, String, String) -> Unit,
    onRemoveBinding: (SyncBinding) -> Unit,
    onToggleBinding: (String, Boolean) -> Unit,
    onDeleteCloud: (CloudTarget) -> Unit
) {
    var showAddPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CloudTarget?>(null) }
    var pendingRemove by remember { mutableStateOf<SyncBinding?>(null) }
    var editingWebDav by remember { mutableStateOf(false) }
    var webdavInitial by remember { mutableStateOf(WebDavServer(id = "")) }
    var editingS3 by remember { mutableStateOf(false) }
    var s3Initial by remember { mutableStateOf(S3Account(id = "")) }
    var showGitHubDialog by remember { mutableStateOf(false) }
    var showGistDialog by remember { mutableStateOf(false) }
    var showGiteeDialog by remember { mutableStateOf(false) }
    var labelingBinding by remember { mutableStateOf<SyncBinding?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
                                            .firstOrNull { it.id == binding.instanceId }
                                            ?: WebDavServer(id = "")
                                        editingWebDav = true
                                    }
                                    SyncProviderType.GITHUB -> showGitHubDialog = true
                                    SyncProviderType.GIST -> showGistDialog = true
                                    SyncProviderType.GITEE -> showGiteeDialog = true
                                    SyncProviderType.S3 -> {
                                        s3Initial = state.s3Accounts
                                            .firstOrNull { it.id == binding.instanceId }
                                            ?: S3Account(id = "")
                                        editingS3 = true
                                    }
                                    SyncProviderType.GOOGLE_DRIVE -> onGoogleSignIn()
                                    SyncProviderType.ONEDRIVE -> onOneDriveSignIn()
                                    SyncProviderType.NONE -> Unit
                                }
                            },
                            onRemove = { pendingRemove = binding },
                            // OAuth 型后端只能「重新登录」，备注名单独给一个入口
                            onRemark = if (binding.type == SyncProviderType.GOOGLE_DRIVE ||
                                binding.type == SyncProviderType.ONEDRIVE
                            ) {
                                { labelingBinding = binding }
                            } else null
                        )
                    }
                }
                OutlinedButton(onClick = { showAddPicker = true }) { Text("添加同步后端") }
                Text(
                    text = "只有已绑定的后端会参与同步，可用左侧开关临时暂停；" +
                        "WebDAV 可添加多台，S3 对象存储可添加多个桶或账号，" +
                        "Google Drive / OneDrive 也可添加多个账号，各自独立同步一份备份。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                    SyncProviderType.S3 -> {
                        s3Initial = S3Account(id = "")
                        editingS3 = true
                    }
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

    if (editingS3) {
        S3AccountDialog(
            initial = s3Initial,
            onDismiss = { editingS3 = false },
            onConfirm = { onSaveS3Account(it); editingS3 = false }
        )
    }

    if (showGitHubDialog) {
        GitHubDialog(
            state = state,
            onDismiss = { showGitHubDialog = false },
            onConfirm = { ownerRepo, token, path, label ->
                onGitHub(ownerRepo, token, path, label)
                showGitHubDialog = false
            }
        )
    }

    if (showGistDialog) {
        GistDialog(
            state = state,
            onDismiss = { showGistDialog = false },
            onConfirm = { token, label ->
                onGist(token, label)
                showGistDialog = false
            }
        )
    }

    if (showGiteeDialog) {
        GiteeDialog(
            state = state,
            onDismiss = { showGiteeDialog = false },
            onConfirm = { ownerRepo, path, token, label ->
                onGitee(ownerRepo, path, token, label)
                showGiteeDialog = false
            }
        )
    }

    labelingBinding?.let { binding ->
        val accountId = binding.instanceId.orEmpty()
        val stored = if (binding.type == SyncProviderType.GOOGLE_DRIVE) {
            state.googleAccountLabels[accountId]
        } else {
            state.oneDriveAccountLabels[accountId]
        }
        AccountLabelDialog(
            accountName = accountDisplayName(accountId),
            initial = stored.orEmpty(),
            onDismiss = { labelingBinding = null },
            onConfirm = { label ->
                onSetAccountLabel(binding.type, accountId, label)
                labelingBinding = null
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

/** 已绑定后端的一行：名称 + 说明 + 参与同步开关 + 编辑/移除（OAuth 型额外给「备注」）。 */
@Composable
private fun BindingRow(
    binding: SyncBinding,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onRemark: (() -> Unit)? = null
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
            onRemark?.let { TextButton(onClick = it) { Text("备注") } }
            TextButton(onClick = onEdit) { Text(if (binding.editable) "编辑" else "重新登录") }
            TextButton(onClick = onRemove) {
                Text("移除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** OAuth 型账号（Google Drive / OneDrive）的备注名窗口：留空即清除备注、回落到账号名。 */
@Composable
private fun AccountLabelDialog(
    accountName: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var label by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账号备注名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "账号：" + accountName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("备注名（留空则用账号名显示）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(label.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * 「添加同步后端」选择窗：多实例后端（WebDAV / S3 / Google Drive / OneDrive）可反复添加，
 * 单实例后端（GitHub / Gist / Gitee）已绑定过就不再列出。
 *
 * 每个选项是一张圆角卡片：左侧色块图标 + 名称与说明 + 右侧箭头，比纯文字行更易点、更好认。
 */
@Composable
private fun AddBackendDialog(
    boundTypes: Set<SyncProviderType>,
    onDismiss: () -> Unit,
    onPick: (SyncProviderType) -> Unit
) {
    val options = MULTI_INSTANCE_PROVIDER_ORDER + SINGLETON_PROVIDER_ORDER.filterNot { it in boundTypes }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加同步后端") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                options.forEach { type ->
                    BackendOptionRow(type = type, onClick = { onPick(type) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 选择窗里的一行：色块图标 + 名称/说明 + 右箭头。 */
@Composable
private fun BackendOptionRow(type: SyncProviderType, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = providerIcon(type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(type.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = addHint(type),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 各后端的图标（仅用于选择窗的视觉区分）。 */
private fun providerIcon(type: SyncProviderType): ImageVector = when (type) {
    SyncProviderType.WEBDAV -> Icons.Filled.Dns
    SyncProviderType.S3 -> Icons.Filled.Storage
    SyncProviderType.GOOGLE_DRIVE -> Icons.Filled.Cloud
    SyncProviderType.ONEDRIVE -> Icons.Filled.CloudUpload
    SyncProviderType.GITHUB -> Icons.Filled.Code
    SyncProviderType.GIST -> Icons.Filled.Description
    SyncProviderType.GITEE -> Icons.Filled.Source
    SyncProviderType.NONE -> Icons.Filled.Cloud
}

private fun addHint(type: SyncProviderType): String = when (type) {
    SyncProviderType.WEBDAV -> "自建或第三方的 WebDAV 目录，可添加多台并各自指定文件名"
    SyncProviderType.S3 -> "AWS S3 及兼容的对象存储（R2 / B2 / MinIO / 阿里云 OSS / 腾讯云 COS 等）"
    SyncProviderType.GOOGLE_DRIVE -> "登录 Google 账号，只访问本应用的隐藏目录；可添加多个账号"
    SyncProviderType.ONEDRIVE -> "登录微软账号，只访问本应用的私有文件夹；可添加多个账号"
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

/** GitHub 仓库绑定窗口：owner/repo、文件路径、令牌、备注名。 */
@Composable
private fun GitHubDialog(
    state: SyncUiState,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit
) {
    var ownerRepo by remember(state.githubOwnerRepo) { mutableStateOf(state.githubOwnerRepo) }
    var path by remember(state.githubPath) { mutableStateOf(state.githubPath) }
    var token by remember(state.githubToken) { mutableStateOf(state.githubToken) }
    var label by remember(state.githubLabel) { mutableStateOf(state.githubLabel) }

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
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("备注名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(ownerRepo.trim(), token.trim(), path.trim().ifBlank { "tessera.vault" }, label.trim())
                },
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
    onConfirm: (String, String) -> Unit
) {
    var token by remember(state.gistToken) { mutableStateOf(state.gistToken) }
    var label by remember(state.gistLabel) { mutableStateOf(state.gistLabel) }

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
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("备注名（可选）") },
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
            TextButton(onClick = { onConfirm(token.trim(), label.trim()) }, enabled = token.isNotBlank()) { Text("保存并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** Gitee（码云）绑定窗口：owner/repo、文件路径、私人令牌、备注名。 */
@Composable
private fun GiteeDialog(
    state: SyncUiState,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit
) {
    var ownerRepo by remember(state.giteeOwnerRepo) { mutableStateOf(state.giteeOwnerRepo) }
    var path by remember(state.giteePath) { mutableStateOf(state.giteePath) }
    var token by remember(state.giteeToken) { mutableStateOf(state.giteeToken) }
    var label by remember(state.giteeLabel) { mutableStateOf(state.giteeLabel) }

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
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("备注名（可选）") },
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
                onClick = { onConfirm(ownerRepo.trim(), path.trim().ifBlank { "tessera.vault" }, token.trim(), label.trim()) },
                enabled = ownerRepo.count { it == '/' } == 1 && token.isNotBlank()
            ) { Text("保存并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/**
 * S3 对象存储编辑窗口：端点、区域、桶、密钥、可选前缀与文件名。
 *
 * 字段口径参照 oplist 的 S3 驱动（<https://doc.oplist.org/guide/drivers/s3>）：
 * 端点可省略协议（默认 https），区域默认 us-east-1，MinIO 一类自建服务需勾选路径风格。
 */
@Composable
private fun S3AccountDialog(
    initial: S3Account,
    onDismiss: () -> Unit,
    onConfirm: (S3Account) -> Unit
) {
    var label by remember { mutableStateOf(initial.label) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var region by remember { mutableStateOf(initial.region.ifBlank { "us-east-1" }) }
    var bucket by remember { mutableStateOf(initial.bucket) }
    var accessKey by remember { mutableStateOf(initial.accessKey) }
    var secretKey by remember { mutableStateOf(initial.secretKey) }
    var sessionToken by remember { mutableStateOf(initial.sessionToken) }
    var prefix by remember { mutableStateOf(initial.prefix) }
    var fileName by remember { mutableStateOf(initial.fileName.ifBlank { "tessera.vault" }) }
    var pathStyle by remember { mutableStateOf(initial.forcePathStyle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id.isBlank()) "添加 S3 对象存储" else "编辑 S3 对象存储") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("备注名（可选，用于区分多个桶）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = { Text("Endpoint") },
                    placeholder = { Text("https://s3.us-east-1.amazonaws.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = region,
                    onValueChange = { region = it },
                    label = { Text("区域（Region）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = bucket,
                    onValueChange = { bucket = it },
                    label = { Text("Bucket 名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accessKey,
                    onValueChange = { accessKey = it },
                    label = { Text("Access Key ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secretKey,
                    onValueChange = { secretKey = it },
                    label = { Text("Secret Access Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = sessionToken,
                    onValueChange = { sessionToken = it },
                    label = { Text("会话令牌（可选，临时凭据才需要）") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it },
                    label = { Text("路径前缀（可选，如 backup/2fa）") },
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("强制路径风格", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "自建 MinIO 等不支持虚拟主机风格时打开",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = pathStyle, onCheckedChange = { pathStyle = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        initial.copy(
                            label = label.trim(),
                            endpoint = endpoint.trim(),
                            region = region.trim().ifBlank { "us-east-1" },
                            bucket = bucket.trim(),
                            accessKey = accessKey.trim(),
                            secretKey = secretKey,
                            sessionToken = sessionToken.trim(),
                            prefix = prefix.trim(),
                            fileName = fileName.trim().ifBlank { "tessera.vault" },
                            forcePathStyle = pathStyle
                        )
                    )
                },
                enabled = endpoint.isNotBlank() && bucket.isNotBlank() &&
                    accessKey.isNotBlank() && secretKey.isNotBlank()
            ) { Text("保存并连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
