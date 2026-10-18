package com.tessera.otp.feature.detail

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.tessera.otp.data.db.entity.CategoryEntity
import com.tessera.otp.data.icon.IconRepository
import com.tessera.otp.domain.OtpAlgorithm
import com.tessera.otp.ui.LetterAvatar

/**
 * 添加/编辑账号的表单状态。
 *
 * 编辑已有条目时 secret 留空表示「保持原密钥不变」，避免把解密后的密钥回填到输入框。
 */
data class DetailForm(
    val id: String? = null,
    val issuer: String = "",
    val accountName: String = "",
    val secret: String = "",
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val isSteam: Boolean = false,
    val isHotp: Boolean = false,
    val counter: Long = 0L,
    val categoryId: String? = null,
    val iconKey: String? = null,
    val note: String = ""
)

/**
 * 手动添加/编辑账号，布局对齐 2FAS 的「将服务与 2FAS 配对」：
 * 服务名称（右侧图标选择）、密钥（可显隐）、可折叠的「其他设置」，
 * 以及「高级令牌设置」（Type / 算法 / 刷新时间 / 位数，右侧上下箭头调节）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    form: DetailForm,
    categories: List<CategoryEntity>,
    icons: IconRepository,
    isNew: Boolean,
    onFormChange: (DetailForm) -> Unit,
    onCategoryAdd: (String) -> Unit,
    onCategoryDelete: (CategoryEntity) -> Unit,
    onPickIcon: () -> Unit,
    onMoveToTrash: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    var secretVisible by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    // 高级令牌设置默认收起，点标题展开
    var tokenSettingsExpanded by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    // 待确认删除的分类（非空时弹确认框）
    var pendingCategoryDelete by remember { mutableStateOf<CategoryEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (isNew) "将服务与 凭牌 配对" else "编辑账号",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = form.issuer,
            onValueChange = { onFormChange(form.copy(issuer = it)) },
            label = { Text("服务名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                // 当前图标：优先用表单里选中的自定义图标，其次按服务名称匹配内置图标；
                // 都没命中就用首字母头像兜底（与首页列表一致），而不是一个无信息的通用图标。
                val currentIcon = icons.assetPath(form.issuer, form.iconKey, preferLight = false)
                val iconLabel = if (form.iconKey == null) "选择图标" else "当前图标: " + form.iconKey
                IconButton(onClick = onPickIcon) {
                    if (currentIcon != null) {
                        AsyncImage(
                            model = "file:///android_asset/" + currentIcon,
                            contentDescription = iconLabel,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(28.dp)
                        )
                    } else {
                        LetterAvatar(
                            seed = form.issuer,
                            size = 28.dp,
                            modifier = Modifier.semantics { contentDescription = iconLabel },
                            textStyle = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        )


        OutlinedTextField(
            value = form.secret,
            onValueChange = { input -> onFormChange(form.copy(secret = input.trim())) },
            label = { Text("密钥") },
            singleLine = true,
            visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { secretVisible = !secretVisible }) {
                    Icon(
                        if (secretVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (secretVisible) "隐藏密钥" else "显示密钥"
                    )
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("其他设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (advancedExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (advancedExpanded) "收起" else "展开"
            )
        }

        if (advancedExpanded) {
            OutlinedTextField(
                value = form.accountName,
                onValueChange = { onFormChange(form.copy(accountName = it)) },
                label = { Text("其他信息（账号、邮箱等）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.note,
                onValueChange = { onFormChange(form.copy(note = it)) },
                label = { Text("备注") },
                modifier = Modifier.fillMaxWidth()
            )
            Text("分类", style = MaterialTheme.typography.labelLarge)
            // 分类多时换行排列；普通 Row 会把最后的 chip 挤成竖排文字
            // 并把它撑成整行高度，留下大片空白
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = form.categoryId == null,
                    onClick = { onFormChange(form.copy(categoryId = null)) },
                    label = { Text("未分类") }
                )
                categories.forEach { category ->
                    val selected = form.categoryId == category.id
                    FilterChip(
                        selected = selected,
                        onClick = { onFormChange(form.copy(categoryId = category.id)) },
                        label = { Text(category.name) },
                        // 选中的分类右侧带叉：点击删除该分类
                        trailingIcon = if (selected) {
                            {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "删除分类 " + category.name,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize)
                                        .clickable { pendingCategoryDelete = category }
                                )
                            }
                        } else {
                            null
                        }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newCategory,
                    onValueChange = { newCategory = it },
                    label = { Text("新建分类") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    if (newCategory.isNotBlank()) {
                        onCategoryAdd(newCategory.trim())
                        newCategory = ""
                    }
                }) { Text("添加") }
            }
            pendingCategoryDelete?.let { target ->
                AlertDialog(
                    onDismissRequest = { pendingCategoryDelete = null },
                    title = { Text("删除分类") },
                    text = { Text("确定删除分类「" + target.name + "」吗？该分类下的账号会变为「未分类」。") },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingCategoryDelete = null
                            onCategoryDelete(target)
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingCategoryDelete = null }) { Text("取消") }
                    }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = form.isSteam, onCheckedChange = { onFormChange(form.copy(isSteam = it)) })
                Text("  Steam 字母表（5 位）")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { tokenSettingsExpanded = !tokenSettingsExpanded }
                .padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("高级令牌设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (tokenSettingsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (tokenSettingsExpanded) "收起" else "展开"
            )
        }

        if (tokenSettingsExpanded) {
            Text(
                text = "不建议更改默认令牌设置。只有在 2FA 提供商明确要求时才修改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        TextButton(
            onClick = {
                // 重置为默认令牌参数（TOTP / SHA1 / 30 秒 / 6 位）
                onFormChange(form.copy(algorithm = OtpAlgorithm.SHA1, digits = 6, period = 30, isHotp = false, isSteam = false))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("重置为默认") }

        SpinnerRow(
            label = "Type",
            value = if (form.isHotp) "HOTP" else "TOTP",
            onUp = { onFormChange(form.copy(isHotp = !form.isHotp)) },
            onDown = { onFormChange(form.copy(isHotp = !form.isHotp)) }
        )
        SpinnerRow(
            label = "算法",
            value = form.algorithm.name,
            onUp = { onFormChange(form.copy(algorithm = nextAlgorithm(form.algorithm, 1))) },
            onDown = { onFormChange(form.copy(algorithm = nextAlgorithm(form.algorithm, -1))) }
        )
        SpinnerRow(
            label = "刷新时间",
            value = form.period.toString() + " 秒",
            onUp = { onFormChange(form.copy(period = (form.period + 5).coerceAtMost(300))) },
            onDown = { onFormChange(form.copy(period = (form.period - 5).coerceAtLeast(5))) }
        )
        SpinnerRow(
            label = "位数",
            value = form.digits.toString(),
            onUp = { onFormChange(form.copy(digits = (form.digits + 1).coerceAtMost(10))) },
            onDown = { onFormChange(form.copy(digits = (form.digits - 1).coerceAtLeast(4))) }
        )
        }

        Spacer(Modifier.height(4.dp))
        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (isNew) "添加" else "保存")
        }
        if (!isNew) {
            // 红色胶囊（与上方"保存"同形，仅换配色）——危险操作用填充红
            Button(
                onClick = onMoveToTrash,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text("删除")
            }
        }
    }

}

/** 右侧带上下箭头调节器的一行（对齐 2FAS 的 spinner 交互）。 */
@Composable
private fun SpinnerRow(label: String, value: String, onUp: () -> Unit, onDown: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Column {
            IconButton(onClick = onUp, modifier = Modifier.height(24.dp)) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = label + " 增加")
            }
            IconButton(onClick = onDown, modifier = Modifier.height(24.dp)) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = label + " 减少")
            }
        }
    }
}

/** 算法循环：SHA1 -> SHA256 -> SHA512。 */
private fun nextAlgorithm(current: OtpAlgorithm, delta: Int): OtpAlgorithm {
    val values = OtpAlgorithm.entries
    val index = values.indexOf(current)
    val next = (index + delta + values.size) % values.size
    return values[next]
}
