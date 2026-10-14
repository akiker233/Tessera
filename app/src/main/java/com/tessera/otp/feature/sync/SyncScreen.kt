package com.tessera.otp.feature.sync

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tessera.otp.data.prefs.SyncInterval
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步主页：状态、立刻同步、同步口令与自动同步。
 *
 * 后端的增删改与云端备份删除都在二级页 [SyncBackendsScreen] 里，这里只留一个入口，
 * 免得主页把「日常看一眼」和「偶尔配置一次」的东西混在一起。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncScreen(
    state: SyncUiState,
    onPassphraseChange: (String) -> Unit,
    onSavePassphrase: () -> Unit,
    onInterval: (SyncInterval) -> Unit,
    onOpenBackends: () -> Unit,
    onSyncNow: () -> Unit,
    onSyncEnabled: (Boolean) -> Unit
) {
    var showPassphraseDialog by remember { mutableStateOf(false) }

    val boundSummary = if (state.bindings.isEmpty()) "尚未添加后端"
    else "已绑定 " + state.bindings.size + " 个 · " + state.bindings.joinToString("、") { it.type.label }

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
            Column {
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
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable(onClick = onOpenBackends),
                    headlineContent = { Text("同步后端") },
                    supportingContent = {
                        Text(boundSummary, style = MaterialTheme.typography.bodySmall)
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                )
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
