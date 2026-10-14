package com.tessera.otp.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tessera.otp.data.db.entity.CategoryEntity

/**
 * 「选择账号」归类页：多选勾选账号后一次性归入某分类。
 *
 * 勾选集合即最终结果——未勾选但原本属于该分类的会被移出（移回「未分类」），
 * 其它分类的条目不受影响。首次渲染用库里的现状做默认勾选，用户手动改动后以本地为准。
 */
@Composable
fun CategoryAssignScreen(
    category: CategoryEntity,
    accounts: List<CategoryAccount>,
    categories: List<CategoryEntity>,
    onConfirm: (Set<String>) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var touched by remember(category.id) { mutableStateOf<Set<String>?>(null) }
    val initial = remember(category.id) {
        accounts.filter { it.categoryId == category.id }.map { it.id }.toSet()
    }
    val selected = touched ?: initial
    var query by remember { mutableStateOf("") }

    val nameOf = remember(categories) { categories.associate { it.id to it.name } }
    val filtered = accounts
        .filter {
            query.isBlank() ||
                it.issuer.contains(query, ignoreCase = true) ||
                it.accountName.contains(query, ignoreCase = true)
        }
        .sortedWith(compareBy({ it.issuer.lowercase() }, { it.accountName.lowercase() }))

    fun toggle(id: String, checked: Boolean) {
        val base = touched ?: initial
        touched = if (checked) base + id else base - id
    }

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
            Column(Modifier.weight(1f)) {
                Text("选择账号", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "归入「" + category.name + "」· 已选 " + selected.size + " 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { touched = accounts.map { it.id }.toSet() }) { Text("全选") }
            TextButton(onClick = { touched = emptySet() }) { Text("清空") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索服务或账号") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        if (accounts.isEmpty()) {
            Text(
                text = "还没有账号。先在首页添加验证码，再回来归类。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(filtered, key = { it.id }) { account ->
                    val checked = account.id in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { toggle(account.id, !checked) }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle(account.id, it) })
                        Column(Modifier.weight(1f)) {
                            Text(
                                account.issuer.ifBlank { account.accountName.ifBlank { "未命名" } },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            val other = account.categoryId
                                ?.takeIf { it != category.id }
                                ?.let { nameOf[it] }
                            if (account.accountName.isNotBlank() || other != null) {
                                Text(
                                    text = buildString {
                                        if (account.accountName.isNotBlank()) append(account.accountName)
                                        if (other != null) {
                                            if (isNotEmpty()) append(" · ")
                                            append("当前在「").append(other).append("」")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }

            Button(
                onClick = { onConfirm(selected); onBack() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) { Text("保存归类（" + selected.size + " 个）") }
        }
    }
}
