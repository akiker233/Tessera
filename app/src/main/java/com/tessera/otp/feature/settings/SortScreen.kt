package com.tessera.otp.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.tessera.otp.data.icon.IconRepository
import com.tessera.otp.ui.LetterAvatar
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 排序页：上半部分拖动分类文件夹调整首页分组顺序；
 * 下半部分按分组列出验证码，拖动只调整所属分组内的顺序，不跨组移动。
 *
 * 拖动过程中只改界面本地列表，松手时才写库；因此一次拖动跨过多行也只产生一次落库。
 * 两个区块互相隔离（分类不会拖进验证码区，验证码也不会跨分组换位）。
 */
@Composable
fun SortScreen(
    state: SortUiState,
    icons: IconRepository,
    onCommitCategories: (List<String>) -> Unit,
    onCommitAccountGroup: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var categories by remember { mutableStateOf(state.categories) }
    var accountGroups by remember { mutableStateOf(state.accountGroups) }
    // 松手提交后数据库会把新顺序推回来；条目集合不变时保留本地顺序，避免列表闪回旧位置
    LaunchedEffect(state.categories) { categories = mergeOrder(categories, state.categories, SortCategory::id) }
    LaunchedEffect(state.accountGroups) { accountGroups = mergeAccountGroups(accountGroups, state.accountGroups) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var autoScrolling by remember { mutableStateOf(false) }

    fun swapCategory(draggedId: String, targetId: String): Boolean {
        val from = categories.indexOfFirst { it.id == draggedId }
        val to = categories.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0) return false
        categories = categories.toMutableList().apply { add(to, removeAt(from)) }
        return true
    }

    fun swapAccount(draggedId: String, targetId: String, groupKey: String): Boolean {
        val groupIndex = accountGroups.indexOfFirst { it.key == groupKey }
        if (groupIndex < 0) return false
        val list = accountGroups[groupIndex].accounts
        val from = list.indexOfFirst { it.id == draggedId }
        val to = list.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0) return false
        val reordered = list.toMutableList().apply { add(to, removeAt(from)) }
        accountGroups = accountGroups.toMutableList().apply {
            this[groupIndex] = this[groupIndex].copy(accounts = reordered)
        }
        return true
    }

    fun finishDrag(isCategory: Boolean, key: String, groupKey: String?) {
        if (draggingKey != key) return
        draggingKey = null
        dragOffset = 0f
        if (isCategory) {
            onCommitCategories(categories.map { it.id })
        } else {
            val ids = accountGroups.firstOrNull { it.key == groupKey }?.accounts?.map { it.id }.orEmpty()
            if (ids.isNotEmpty()) onCommitAccountGroup(ids)
        }
    }

    fun dragModifier(
        key: String,
        prefix: String,
        isCategory: Boolean,
        groupKey: String? = null
    ): Modifier = Modifier.pointerInput(key) {
        detectDragGestures(
            onDragStart = {
                draggingKey = key
                dragOffset = 0f
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (draggingKey != key) return@detectDragGestures
                dragOffset += dragAmount.y

                val layout = listState.layoutInfo
                val info = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return@detectDragGestures
                // 被拖动行的视觉中心；layoutInfo 里的 offset 不含 graphicsLayer 位移，所以要手动加上
                val center = info.offset + info.size / 2f + dragOffset
                val candidate = layout.visibleItemsInfo
                    .asSequence()
                    .filter { item -> item.key != key && (item.key as? String)?.startsWith(prefix) == true }
                    .minByOrNull { abs(it.offset + it.size / 2f - center) }
                if (candidate != null) {
                    val targetCenter = candidate.offset + candidate.size / 2f
                    // 中心重叠过半才交换，避免轻微抖动就换位
                    if (abs(targetCenter - center) < (info.size + candidate.size) / 4f) {
                        val targetKey = candidate.key as String
                        val swapped = if (isCategory) {
                            swapCategory(key.removePrefix(prefix), targetKey.removePrefix(prefix))
                        } else {
                            swapAccount(key.removePrefix(prefix), targetKey.removePrefix(prefix), groupKey.orEmpty())
                        }
                        if (swapped) {
                            // 交换后列表中该行的基准位置变了，补偿位移才能让它继续跟着手指
                            dragOffset += if (candidate.offset > info.offset) -candidate.size else candidate.size
                        }
                    }
                }

                // 拖到视口边缘时自动滚动，否则长列表末尾的条目根本拖不过去
                val edge = 64.dp.toPx()
                val visualTop = info.offset + dragOffset
                val visualBottom = visualTop + info.size
                val scrollDelta = when {
                    visualBottom > layout.viewportEndOffset - edge -> visualBottom - (layout.viewportEndOffset - edge)
                    visualTop < layout.viewportStartOffset + edge -> visualTop - (layout.viewportStartOffset + edge)
                    else -> 0f
                }
                if (scrollDelta != 0f && !autoScrolling) {
                    autoScrolling = true
                    scope.launch {
                        try {
                            dragOffset += listState.scrollBy(scrollDelta.coerceIn(-edge, edge))
                        } finally {
                            autoScrolling = false
                        }
                    }
                }
            },
            onDragEnd = { finishDrag(isCategory, key, groupKey) },
            onDragCancel = { finishDrag(isCategory, key, groupKey) }
        )
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
            Text("排序", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "hint") {
                Text(
                    text = "按住每行右侧的手柄上下拖动，松手即保存。分类调整首页分组顺序；验证码只在所属分组内换位。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            item(key = "header:categories") {
                Text(
                    text = "分类文件夹",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )
            }
            if (categories.isEmpty()) {
                item(key = "empty:categories") {
                    Text(
                        text = "还没有分类。可在设置 → 分类管理中新建。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            } else {
                items(categories, key = { "cat:" + it.id }) { category ->
                    val key = "cat:" + category.id
                    val dragging = draggingKey == key
                    SortRow(
                        title = category.name,
                        subtitle = "首页分组顺序 · 未分类固定最后",
                        leading = {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color(category.colorArgb))
                            )
                        },
                        handleModifier = dragModifier(key, "cat:", isCategory = true),
                        modifier = Modifier
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                if (dragging) {
                                    translationY = dragOffset
                                    shadowElevation = 12.dp.toPx()
                                }
                            }
                    )
                }
            }

            item(key = "header:accounts") {
                Text(
                    text = "验证码",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp)
                )
            }
            if (accountGroups.isEmpty()) {
                item(key = "empty:accounts") {
                    Text(
                        text = "还没有验证码。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            } else {
                accountGroups.forEach { group ->
                    item(key = "group:" + group.key) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (group.colorArgb != null) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(group.colorArgb))
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = group.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = group.accounts.size.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val prefix = "acc:" + group.key + ":"
                    items(group.accounts, key = { prefix + it.id }) { account ->
                        val key = prefix + account.id
                        val dragging = draggingKey == key
                        SortRow(
                            title = account.issuer,
                            subtitle = account.accountName.takeIf { it.isNotBlank() },
                            leading = {
                                val path = remember(account.id, account.iconKey, account.issuer) {
                                    icons.assetPath(account.issuer, account.iconKey, preferLight = false)
                                }
                                if (path != null) {
                                    AsyncImage(
                                        model = "file:///android_asset/" + path,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Fit
                                    )
                                } else {
                                    LetterAvatar(seed = account.issuer, size = 32.dp)
                                }
                            },
                            handleModifier = dragModifier(
                                key = key,
                                prefix = prefix,
                                isCategory = false,
                                groupKey = group.key
                            ),
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) {
                                        translationY = dragOffset
                                        shadowElevation = 12.dp.toPx()
                                    }
                                }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortRow(
    title: String,
    subtitle: String?,
    leading: @Composable () -> Unit,
    handleModifier: Modifier,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .then(handleModifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "拖动排序：" + title,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 外部数据刷新时保留本地拖出的顺序：分组集合不变时只更新字段、沿用当前组内顺序；
 * 分组增删或成员变化才整体采用新数据。
 */
private fun mergeAccountGroups(
    current: List<SortAccountGroup>,
    incoming: List<SortAccountGroup>
): List<SortAccountGroup> {
    if (current.map { it.key } != incoming.map { it.key }) return incoming
    val currentByKey = current.associateBy { it.key }
    return incoming.map { next ->
        val previous = currentByKey[next.key] ?: return@map next
        val previousIds = previous.accounts.map { it.id }
        val incomingIds = next.accounts.map { it.id }
        if (previousIds.size != incomingIds.size || previousIds.toSet() != incomingIds.toSet()) {
            next
        } else {
            val byId = next.accounts.associateBy { it.id }
            next.copy(accounts = previousIds.mapNotNull(byId::get))
        }
    }
}

/**
 * 外部数据刷新时保留本地拖出的顺序：只有条目集合变化才重置，
 * 集合一致时按 id 更新字段、沿用当前顺序。
 */
private fun <T> mergeOrder(current: List<T>, incoming: List<T>, idOf: (T) -> String): List<T> {
    if (current.isEmpty()) return incoming
    if (current.map(idOf) != incoming.map(idOf)) return incoming
    val byId = incoming.associateBy(idOf)
    return current.mapNotNull { byId[idOf(it)] }
}
