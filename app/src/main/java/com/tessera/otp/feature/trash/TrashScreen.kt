package com.tessera.otp.feature.trash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 回收站条目展示模型。 */
data class TrashItem(
    val id: String,
    val issuer: String,
    val accountName: String,
    val deletedAt: Long,
    val remainingDays: Int
)

@Composable
fun TrashScreen(
    items: List<TrashItem>,
    retentionDays: Int,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onEmpty: () -> Unit,
    onBack: () -> Unit
) {
    // 系统返回键也回到上一页（之前只能靠底部导航离开，没有返回入口）
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("回收站", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) {
                TextButton(onClick = onEmpty) { Text("清空") }
            }
        }
        Text(
            text = if (retentionDays > 0) "移入回收站的条目会在 " + retentionDays + " 天后自动清除" else "条目将永久保留在回收站",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("回收站是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items, key = { it.id }) { item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.issuer, style = MaterialTheme.typography.titleMedium)
                        if (item.accountName.isNotBlank()) {
                            Text(
                                item.accountName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "删除于 " + formatDate(item.deletedAt) +
                                if (item.remainingDays in 1..3650) "，还剩 " + item.remainingDays + " 天" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onRestore(item.id) }) { Text("还原") }
                            TextButton(onClick = { onDeleteForever(item.id) }) { Text("彻底删除") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
