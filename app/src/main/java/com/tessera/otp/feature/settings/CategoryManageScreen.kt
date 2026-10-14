package com.tessera.otp.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tessera.otp.data.db.entity.CategoryEntity

/** 分类可选的配色（与首页 chip 圆点共用同一批颜色）。 */
private val CATEGORY_COLORS = listOf(
    0xFF3B5BDB.toInt(), 0xFF0B7285.toInt(), 0xFF2B8A3E.toInt(), 0xFFE8590C.toInt(), 0xFFC2255C.toInt(),
    0xFF7048E8.toInt(), 0xFF1098AD.toInt(), 0xFF37B24D.toInt(), 0xFFF59F00.toInt(), 0xFFE03131.toInt()
)

/**
 * 分类（文件夹）管理页：新建 / 重命名 / 改色 / 删除 / 上下排序，并可进入「选择账号」归类。
 *
 * 结构对齐其它二级设置页（自绘返回栏 + 可滚动内容），入口在设置根页。
 */
@Composable
fun CategoryManageScreen(
    state: CategoryManageState,
    onAdd: (String) -> Boolean,
    onEdit: (CategoryEntity, String, Int) -> Boolean,
    onDelete: (CategoryEntity) -> Unit,
    onMove: (CategoryEntity, Int) -> Unit,
    onManageAccounts: (CategoryEntity) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<CategoryEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

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
            Text("分类管理", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; message = null },
                    label = { Text("新建分类") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (onAdd(newName)) {
                        newName = ""
                        message = null
                    } else {
                        message = "名称为空或已存在同名分类"
                    }
                }) { Text("添加") }
            }

            message?.let { text ->
                Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (state.rows.isEmpty()) {
                Text(
                    text = "还没有分类。新建后即可在账号里归类，并在首页按文件夹查看。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.rows, key = { _, row -> row.entity.id }) { index, row ->
                        CategoryRowCard(
                            row = row,
                            isFirst = index == 0,
                            isLast = index == state.rows.lastIndex,
                            onMoveUp = { onMove(row.entity, -1) },
                            onMoveDown = { onMove(row.entity, 1) },
                            onManageAccounts = { onManageAccounts(row.entity) },
                            onEdit = { editing = row.entity },
                            onDelete = { pendingDelete = row.entity }
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        EditCategoryDialog(
            target = target,
            onDismiss = { editing = null },
            onConfirm = { name, color ->
                if (onEdit(target, name, color)) {
                    editing = null
                    message = null
                } else {
                    message = "名称不能为空或与其它分类重名"
                }
            }
        )
    }

    pendingDelete?.let { target ->
        val count = state.rows.firstOrNull { it.entity.id == target.id }?.count ?: 0
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除分类") },
            text = {
                Text(
                    if (count > 0) "确定删除「" + target.name + "」吗？该分类下的 " + count + " 个账号会变为「未分类」。"
                    else "确定删除「" + target.name + "」吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun CategoryRowCard(
    row: CategoryRow,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onManageAccounts: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 圆点：点一下打开编辑（改名/改色）
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(row.entity.colorArgb))
                    .clickable(onClick = onEdit)
            )
            Spacer(Modifier.width(12.dp))
            // 名称/账号数区域：点一下进入「选择账号」归类
            Column(Modifier.weight(1f).clickable(onClick = onManageAccounts)) {
                Text(row.entity.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = row.count.toString() + " 个账号 · 点此归类",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onManageAccounts) {
                Icon(Icons.Filled.PersonAdd, contentDescription = "选择账号加入 " + row.entity.name)
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑 " + row.entity.name)
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除 " + row.entity.name,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** 编辑弹窗：改名 + 从固定色板里选颜色。 */
@Composable
private fun EditCategoryDialog(
    target: CategoryEntity,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    var name by remember(target.id) { mutableStateOf(target.name) }
    var color by remember(target.id) { mutableStateOf(target.colorArgb) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑分类") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("颜色", style = MaterialTheme.typography.labelLarge)
                CATEGORY_COLORS.chunked(5).forEach { rowColors ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowColors.forEach { argb ->
                            ColorDot(
                                argb = argb,
                                selected = argb == color,
                                onClick = { color = argb }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, color) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (selected) 34.dp else 30.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            // 选中态：内嵌一圈底色作为描边，无需额外 draw 逻辑
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
            )
        }
    }
}
