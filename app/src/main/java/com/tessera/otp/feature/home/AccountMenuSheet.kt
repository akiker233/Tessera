package com.tessera.otp.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 长按账号卡片后弹出的操作菜单：复制令牌 / 编辑 / 删除。
 *
 * 刻意独立成文件：状态全部由参数传入，不依赖 HomeScreen 的内部变量，
 * 这样即使出错也只影响本文件，便于单独编译定位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountMenuSheet(
    item: HomeItem,
    codesVisible: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.issuer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (item.accountName.isNotBlank()) {
                        Text(
                            text = item.accountName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    GroupedCode(
                        text = if (codesVisible) item.groupedCode else "••• •••",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        gap = 9.dp
                    )
                }
                Text(
                    text = item.secondsRemaining.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (item.secondsRemaining <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        ListItem(
            modifier = Modifier.clickable(onClick = onCopy),
            leadingContent = { Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            headlineContent = { Text("复制令牌") }
        )
        ListItem(
            modifier = Modifier.clickable(onClick = onEdit),
            leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            headlineContent = { Text("编辑") }
        )
        ListItem(
            modifier = Modifier.clickable(onClick = onDelete),
            leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            headlineContent = { Text("删除") }
        )
        Spacer(Modifier.height(24.dp))
    }
}
