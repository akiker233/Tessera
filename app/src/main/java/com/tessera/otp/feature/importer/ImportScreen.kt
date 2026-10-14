package com.tessera.otp.feature.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 导入页：选文件 -> 解析预览 -> 确认写入；另提供从图片识别二维码的入口。
 *
 * 所有文件读取与图片识别都在 IO 线程执行，UI 只负责展示预览与失败原因。
 */
@Composable
fun ImportScreen(
    state: ImportUiState,
    onFileContent: (String, String) -> Unit,
    onScanned: (String) -> Unit,
    /** 图片识别失败时的可读原因（避免静默无反应）。 */
    onScanFailure: (String) -> Unit,
    /** Tessera 加密备份：用户输入的密码。 */
    onPassword: (String) -> Unit,
    onConfirm: () -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = queryName(context, uri) ?: "backup.json"
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) onFileContent(name, "") else onFileContent(name, text)
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = decodeQrFromImage(context, uri)
            val raw = result.getOrNull()
            if (raw.isNullOrBlank()) {
                // 以前失败时传空串，onQrText 直接 return —— 用户只看到"点了没反应"
                onScanFailure(scanErrorMessage(result.exceptionOrNull()))
            } else {
                onScanned(raw)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("导入其他验证器", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "支持 Tessera 专属备份（可加密，含分类）、2FAS、Stratum、Aegis、andOTP、Raivo OTP、LastPass 的导出文件，以及 Google Authenticator 迁移二维码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("选择文件") }
            OutlinedButton(onClick = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("从图片识别") }
        }

        if (state.encrypted && state.canDecrypt) {
            TesseraPasswordDialog(
                message = state.message,
                busy = state.busy,
                onSubmit = onPassword,
                onDismiss = onReset
            )
        } else {
            state.message?.let { message ->
                AlertDialog(
                    onDismissRequest = onReset,
                    title = { Text(if (state.encrypted) "备份已加密" else "导入提示") },
                    text = { Text(message) },
                    confirmButton = { TextButton(onClick = onReset) { Text("知道了") } }
                )
            }
        }
        state.fileName?.let { name ->
            val detected = state.detected?.label ?: "未识别"
            Text("文件：" + name + "（" + detected + "）", style = MaterialTheme.typography.bodySmall)
        }

        if (state.preview.isNotEmpty()) {
            val categoryHint = if (state.categories.isNotEmpty()) "、分类 " + state.categories.size + " 个" else ""
            Text(
                "将导入 " + state.preview.size + " 条" + categoryHint + "（已存在同名条目会自动跳过）",
                style = MaterialTheme.typography.labelLarge
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(state.preview) { account ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                account.issuer.ifBlank { account.accountName.ifBlank { "未命名" } },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = account.accountName + " · " + account.algorithm + " · " +
                                    account.digits + " 位 · " + account.period + " 秒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) { Text("确认导入") }
                OutlinedButton(onClick = onReset) { Text("取消") }
            }
        }

        if (state.issues.isNotEmpty()) {
            Text("未导入 " + state.issues.size + " 条：", style = MaterialTheme.typography.labelLarge)
            state.issues.take(8).forEach { issue ->
                Text("· " + issue.label + "：" + issue.reason, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Tessera 加密备份的密码输入对话框；密码错误会带出新的提示文案，可重试。 */
@Composable
private fun TesseraPasswordDialog(
    message: String?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("请输入备份密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message ?: "这是一份加密的 Tessera 备份，请输入导出时设置的密码。")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty() && !busy
            ) { Text("解密") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 读取 content:// 的显示文件名。 */
private fun queryName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()
/**
 * 从图片 URI 用 ML Kit 识别二维码。
 *
 * 抽成共享函数的原因：导入页的「从图片识别」与扫码区的「从相册选择二维码图片」
 * 需要完全一致的解码与错误语义，避免两处各写一份、其中一处静默失败。
 * 失败时返回带可读原因的 Result（例如"图片中没有识别到二维码"）。
 */
internal suspend fun decodeQrFromImage(context: android.content.Context, uri: android.net.Uri): Result<String> =
    withContext(Dispatchers.IO) {
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val barcodes = BarcodeScanning.getClient().process(image).await()
            barcodes.firstNotNullOfOrNull { barcode: Barcode -> barcode.rawValue }
                ?: throw IllegalStateException("图片中没有识别到二维码")
        }
    }

/**
 * 把 ML Kit 的英文异常翻成用户能懂的中文。
 * 最常见的是"首次使用需下载识别模块"（非捆绑模型），必须明确告知可重试，
 * 否则用户只会看到一句英文、或者（修复前）什么都没有。
 */
internal fun scanErrorMessage(t: Throwable?): String {
    val raw = t?.message ?: t?.let { it::class.java.simpleName } ?: "未能从这张图片中识别出二维码"
    return when {
        raw.contains("barcode module", ignoreCase = true) ->
            "二维码识别模块正在下载（首次使用需联网下载几 MB），请稍后重试"
        raw.contains("No barcode", ignoreCase = true) -> "图片中没有识别到二维码"
        raw.contains("没有识别到二维码") -> raw
        else -> "识别失败：" + raw
    }
}

