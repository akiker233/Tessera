package com.tessera.otp.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import android.widget.Toast
import com.tessera.otp.data.db.entity.CategoryEntity
import com.tessera.otp.data.icon.IconRepository
import com.tessera.otp.data.prefs.ListDensity
import com.tessera.otp.ui.LetterAvatar
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    icons: IconRepository,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onToggleVisibility: () -> Unit,
    onItemClick: (HomeItem) -> Unit,
    onMoveToTrash: (String) -> Unit,
    /** 是否正在手动同步：驱动下拉刷新的转圈指示器。 */
    syncing: Boolean = false,
    /** 上一次同步结果文案，同步结束后用 Toast 反馈。 */
    syncStatus: String = "",
    /** 下拉到阈值后触发一次手动同步。 */
    onRefresh: () -> Unit = {},
    density: ListDensity = ListDensity.DEFAULT,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp)
) {
    val context = LocalContext.current
    var menuItemId by remember { mutableStateOf<String?>(null) }
    // 只有「用户下拉触发」的同步才显示指示器、弹结果提示；
    // 冷启动与后台（账号改动 / 周期任务）触发的同步必须完全静默。
    var pulled by remember { mutableStateOf(false) }
    // 本地同步常常 0.3 秒就结束，指示器一闪而过等于没有反馈；这里保证它至少显示
    // SYNC_INDICATOR_MIN_MS，然后再收起、弹结果提示。
    var indicatorHeld by remember { mutableStateOf(false) }
    var indicatorShownAt by remember { mutableStateOf(0L) }
    // 下拉那一瞬间 running 还没翻成 true，不能据此就认定「同步已结束」
    var sawRunning by remember { mutableStateOf(false) }
    // 首页按分类分组时的收起状态（默认全部展开，只记录被收起的组 key）
    var collapsedGroups by remember { mutableStateOf(setOf<String>()) }
    val latestStatus by rememberUpdatedState(syncStatus)
    LaunchedEffect(pulled, syncing) {
        if (!pulled) return@LaunchedEffect
        if (syncing) {
            sawRunning = true
            if (!indicatorHeld) {
                indicatorHeld = true
                indicatorShownAt = System.currentTimeMillis()
            }
        } else if (sawRunning) {
            val remain = SYNC_INDICATOR_MIN_MS - (System.currentTimeMillis() - indicatorShownAt)
            if (remain > 0) delay(remain)
            indicatorHeld = false
            sawRunning = false
            pulled = false
            if (latestStatus.isNotBlank()) {
                Toast.makeText(context, latestStatus, Toast.LENGTH_SHORT).show()
            }
        } else {
            // 下拉后一段时间同步都没开始（例如还没设置同步口令）：放弃标记，别让指示器挂住
            delay(SYNC_NO_START_TIMEOUT_MS)
            if (!sawRunning) pulled = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("验证码") },
                actions = {
                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            imageVector = if (state.flags.codesVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = if (state.flags.codesVisible) "隐藏验证码" else "显示验证码"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "清除搜索")
                        }
                    }
                },
                placeholder = { Text("搜索服务或账号") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )

            if (state.categories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.selectedCategoryId == null,
                        onClick = { onCategorySelected(null) },
                        label = { Text("全部") }
                    )
                    state.categories.forEach { category ->
                        FilterChip(
                            selected = state.selectedCategoryId == category.id,
                            onClick = { onCategorySelected(category.id) },
                            label = { Text(category.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(category.colorArgb))
                                )
                            }
                        )
                    }
                }
            }

            // 下拉刷新：滑到阈值就手动同步一次（拉取 -> 合并 -> 推送），指示器沿用 Material3 的动画
            PullToRefreshBox(
                // 指示器只服务用户下拉：冷启动/后台同步在这里是静默的
                isRefreshing = indicatorHeld || (syncing && pulled),
                onRefresh = {
                    pulled = true
                    onRefresh()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (state.loading) {
                    // 空态/加载态也用 LazyColumn 包一层：下拉手势需要可滚动的子节点才能被识别
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                } else if (state.items.isEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (state.query.isEmpty()) "还没有验证码，去导入或手动添加" else "没有匹配的结果",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // 「全部」且未搜索时按分类折叠分组；选中某分类或搜索时为平铺结果
                    val groups = if (state.selectedCategoryId == null && state.query.isBlank()) {
                        buildCategoryGroups(state.items, state.categories)
                    } else {
                        emptyList()
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (groups.isEmpty()) {
                            items(state.items, key = { it.id }) { item ->
                                AccountRow(
                                    item = item,
                                    codesVisible = state.flags.codesVisible,
                                    density = density,
                                    icons = icons,
                                    onClick = { onItemClick(item) },
                                    onLongPress = { menuItemId = it.id },
                                    onMoveToTrash = { onMoveToTrash(item.id) },
                                    // 同步后账号有增删时，列表项淡入/位移，避免生硬跳变
                                    modifier = Modifier.animateItem()
                                )
                            }
                        } else {
                            groups.forEach { group ->
                                val collapsed = group.key in collapsedGroups
                                item(key = "group:" + group.key) {
                                    GroupHeader(
                                        group = group,
                                        collapsed = collapsed,
                                        onToggle = {
                                            collapsedGroups =
                                                if (collapsed) collapsedGroups - group.key else collapsedGroups + group.key
                                        }
                                    )
                                }
                                if (!collapsed) {
                                    items(group.items, key = { it.id }) { item ->
                                        AccountRow(
                                            item = item,
                                            codesVisible = state.flags.codesVisible,
                                            density = density,
                                            icons = icons,
                                            onClick = { onItemClick(item) },
                                            onLongPress = { menuItemId = it.id },
                                            onMoveToTrash = { onMoveToTrash(item.id) },
                                            modifier = Modifier.animateItem()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // 长按卡片后的操作菜单（复制 / 编辑 / 删除）
    // 只保存 id，并每次重组从 state.items 里取最新条目：
    // 这样菜单里的验证码/剩余秒数会随每秒 tick 一起刷新，而不是冻结在长按那一刻。
    val menuTarget = menuItemId?.let { id -> state.items.firstOrNull { it.id == id } }
    if (menuTarget != null) {
        AccountMenuSheet(
            item = menuTarget,
            codesVisible = state.flags.codesVisible,
            onDismiss = { menuItemId = null },
            onCopy = {
                if (menuTarget.secretBroken) {
                    Toast.makeText(context, "该条目密钥无法解密，请重新输入密钥", Toast.LENGTH_LONG).show()
                } else {
                    copyCode(context, menuTarget.groupedCode)
                }
                menuItemId = null
            },
            onEdit = { menuItemId = null; onItemClick(menuTarget) },
            onDelete = { menuItemId = null; onMoveToTrash(menuTarget.id) }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(
    item: HomeItem,
    codesVisible: Boolean,
    density: ListDensity,
    icons: IconRepository,
    onClick: () -> Unit,
    onLongPress: (HomeItem) -> Unit,
    onMoveToTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val path = remember(item.id, item.iconKey, item.issuer) {
        icons.assetPath(item.issuer, item.iconKey, preferLight = false)
    }
    // 最后 5 秒视为即将过期：验证码转红、圆环转红并显示下一令牌
    val expiring = item.secondsRemaining <= 5

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (item.secretBroken) {
                        Toast.makeText(context, "该条目密钥无法解密，请点开重新输入密钥", Toast.LENGTH_LONG).show()
                    } else {
                        copyCode(context, item.groupedCode)
                    }
                },
                onLongClick = { onLongPress(item) }
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(density.rowPadding.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (path != null) {
                AsyncImage(
                    model = "file:///android_asset/" + path,
                    contentDescription = null,
                    modifier = Modifier
                        .size(density.iconSize.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                // 未匹配到图标：用首字母头像兜底（点击详情页可自定义图标）
                LetterAvatar(seed = item.issuer, size = density.iconSize.dp)
            }
            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.issuer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.accountName.isNotBlank()) {
                    Text(
                        text = item.accountName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                // 验证码与下一个令牌并排：后者在右侧、字号更小，避免在窄屏上折行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.secretBroken) {
                        // 旧格式同步包带来的条目：本机解不开密钥，明确告知，而不是摆一个错的验证码
                        Text(
                            text = "密钥不可用 · 点开重新输入",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        GroupedCode(
                            text = if (codesVisible) item.groupedCode else "••• •••",
                            fontSize = density.codeSize.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (expiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            gap = (density.codeSize / 3).dp
                        )
                    }
                    // 横向展开 + 淡入（在这个 Row 内，RowScope 的 AnimatedVisibility 可正常解析）
                    AnimatedVisibility(
                        visible = !item.secretBroken && codesVisible && item.nextGroupedCode != null,
                        enter = fadeIn(tween(220)) + expandHorizontally(tween(220)),
                        exit = fadeOut(tween(220)) + shrinkHorizontally(tween(220))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(6.dp))
                            GroupedCode(
                                text = item.nextGroupedCode.orEmpty(),
                                fontSize = (density.codeSize - 8).sp,
                                fontWeight = FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                gap = ((density.codeSize - 8) / 3).dp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // 右侧圆环倒计时：环内显示剩余秒数，进度按剩余比例收缩
            if (!item.isHotp && !item.secretBroken) {
                // 圆环进度用帧同步时钟（withFrameNanos）驱动，按**墙钟**计算剩余比例，
                // 与 TOTP 时间窗/上方秒数保持一致；状态只在绘制阶段读取，不触发整行重组。
                val ringProgress = remember { mutableFloatStateOf(1f) }
                LaunchedEffect(item.id, item.period) {
                    val periodMs = item.period * 1000L
                    while (true) {
                        withFrameNanos {
                            // 与出码使用同一个时钟（网络校准后），保证环与验证码同步
                            val nowMs = com.tessera.otp.data.time.NetworkTime.nowMillis()
                            val elapsed = nowMs % periodMs
                            ringProgress.floatValue = 1f - elapsed.toFloat() / periodMs.toFloat()
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { ringProgress.floatValue },
                        modifier = Modifier.size(38.dp),
                        color = if (expiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                    Text(
                        text = item.secondsRemaining.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (expiring) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

}

/** 首页分组：一个分类（或「未分类」）对应的条目集合。 */
private data class HomeGroup(
    val key: String,
    val name: String,
    val colorArgb: Int?,
    val items: List<HomeItem>
)

/** 「未分类」分组的固定 key。 */
private const val UNGROUPED_KEY = "ungrouped"

/**
 * 把条目按分类归组，顺序跟随分类自身的排序，未分类（含指向已删除分类的孤儿条目）放最后。
 *
 * 一个分类都没有时返回空列表——首页保持原来的平铺列表，不给没建过分类的用户平白加一层目录。
 */
private fun buildCategoryGroups(items: List<HomeItem>, categories: List<CategoryEntity>): List<HomeGroup> {
    if (categories.isEmpty()) return emptyList()
    val byCategory = items.groupBy { it.categoryId }
    val knownIds = categories.map { it.id }.toSet()
    val groups = categories.mapNotNull { category ->
        val list = byCategory[category.id].orEmpty()
        if (list.isEmpty()) null else HomeGroup(category.id, category.name, category.colorArgb, list)
    }.toMutableList()
    val uncategorized = items.filter { it.categoryId == null || it.categoryId !in knownIds }
    if (uncategorized.isNotEmpty()) {
        groups.add(HomeGroup(UNGROUPED_KEY, "未分类", null, uncategorized))
    }
    return groups
}

/** 分组标题：文件夹图标 + 名称 + 数量 + 展开/收起箭头，整行可点。 */
@Composable
private fun GroupHeader(group: HomeGroup, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            tint = group.colorArgb?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = group.name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = group.items.size.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (collapsed) "展开" else "收起",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 手动同步指示器的最短显示时长：太短的同步也要让用户看到反馈。 */
private const val SYNC_INDICATOR_MIN_MS = 600L

/** 下拉后等「同步真的开始」的宽限：超时说明这次下拉不会触发同步，直接收尾。 */
private const val SYNC_NO_START_TIMEOUT_MS = 1_500L

/** 复制验证码到剪贴板（去掉分组空格）并提示。 */
private fun copyCode(context: android.content.Context, groupedCode: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("验证码", groupedCode.replace(" ", "")))
    Toast.makeText(context, "已复制验证码", Toast.LENGTH_SHORT).show()
}
/**
 * 渲染分组验证码（如 "193 868"）。
 *
 * 等宽字体里一个空格要占满一个字宽，两段之间会显得过空；
 * 这里把两段拆开、用可控间距拼接，间距随字号缩放（约 1/3 字号）。
 */
@Composable
fun GroupedCode(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: androidx.compose.ui.graphics.Color,
    fontWeight: FontWeight,
    gap: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val full = text
    val parts = text.split(" ")
    if (parts.size != 2) {
        Text(
            text = text,
            modifier = modifier,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            color = color
        )
        return
    }
    // 视觉上分段显示（缩小两段间距），但对无障碍/读屏必须合成一个节点：
    // 否则验证码会被拆成两个数字节点，读屏读出来是「318、640」两段。
    Row(
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(full) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = parts[0],
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            color = color
        )
        Spacer(Modifier.width(gap))
        Text(
            text = parts[1],
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            color = color
        )
    }
}
