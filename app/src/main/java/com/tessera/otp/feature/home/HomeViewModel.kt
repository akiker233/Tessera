package com.tessera.otp.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.data.db.entity.CategoryEntity
import com.tessera.otp.data.prefs.SettingsRepository
import com.tessera.otp.data.repo.Account
import com.tessera.otp.data.repo.AccountRepository
import com.tessera.otp.domain.CodeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 首页显示开关。 */
data class DisplayFlags(
    val codesVisible: Boolean = true,
    val nextTokenThresholdSeconds: Int = 5,
    val dynamicColor: Boolean = true
)

/** 列表项渲染模型：验证码在 ViewModel 算好，Compose 只负责画。 */
data class HomeItem(
    val id: String,
    val issuer: String,
    val accountName: String,
    val groupedCode: String,
    val nextGroupedCode: String?,
    val secondsRemaining: Int,
    val period: Int,
    val iconKey: String?,
    val categoryId: String?,
    val isHotp: Boolean,
    /** 密钥无法解密（旧格式同步进来的条目）：卡片不再出码，改为提示重新输入。 */
    val secretBroken: Boolean = false
)

data class HomeUiState(
    val items: List<HomeItem> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val selectedCategoryId: String? = null,
    val query: String = "",
    val flags: DisplayFlags = DisplayFlags(),
    /** 已收起的分组 key（分类 id 或「未分类」），来自设置、跨重启保持。 */
    val collapsedGroupKeys: Set<String> = emptySet(),
    /** 「未分类」的位置锚点：null = 末尾（从未拖过），"" = 最前，其余为前一个分类 id。 */
    val ungroupedGroupAnchor: String? = null,
    val loading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val repository: AccountRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val categories = MutableStateFlow<List<CategoryEntity>>(emptyList())
    private val collapseMutex = Mutex()

    /** 每秒 tick，驱动倒计时与"下一令牌"。 */
    private val ticker = flow {
        while (true) {
            // 用网络校准后的时间出码，避免系统时间不准导致验证码错误
            emit(com.tessera.otp.data.time.NetworkTime.nowSeconds())
            delay(1000L)
        }
    }

    private val accounts = query
        .map { it.trim() }
        .flatMapLatest { q ->
            if (q.isEmpty()) repository.observeActive() else repository.search(q)
        }
        // 分类切换只在内存里过滤：重订阅 observeActive() 会把整库条目重新解密一遍，
        // 每条密钥都要走一次 Keystore，点一下分类就能卡住主线程。
        .combine(selectedCategory) { list, category ->
            if (category == null) list else list.filter { it.entity.categoryId == category }
        }

    val uiState: StateFlow<HomeUiState> = combine(
        accounts,
        settings.settings,
        categories,
        ticker
    ) { list, appSettings, categoryList, epoch ->
        HomeUiState(
            items = list.map { account ->
                val remaining = account.secondsRemaining(epoch)
                val showNext = !account.isHotp && account.usable &&
                    remaining <= appSettings.nextTokenThresholdSeconds
                HomeItem(
                    id = account.id,
                    issuer = account.issuer.ifBlank { "未命名" },
                    accountName = account.accountName,
                    groupedCode = CodeFormatter.grouped(account.code(epoch)),
                    nextGroupedCode = if (showNext) CodeFormatter.grouped(account.nextCode(epoch)) else null,
                    secondsRemaining = remaining,
                    period = account.entity.period,
                    iconKey = account.entity.iconKey,
                    categoryId = account.entity.categoryId,
                    isHotp = account.isHotp,
                    secretBroken = !account.usable
                )
            },
            categories = categoryList,
            selectedCategoryId = selectedCategory.value,
            query = query.value,
            flags = DisplayFlags(
                codesVisible = appSettings.codesVisible,
                nextTokenThresholdSeconds = appSettings.nextTokenThresholdSeconds,
                dynamicColor = appSettings.dynamicColor
            ),
            collapsedGroupKeys = appSettings.collapsedGroups,
            ungroupedGroupAnchor = appSettings.ungroupedGroupAnchor,
            loading = false
        )
    }
        // 出码（HMAC）与列表映射都放到后台：分类切换和每秒 tick 都不再占用主线程
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            repository.observeCategories().collect { list ->
                categories.value = list
                // 选中的分类被删除后回到「全部」，否则列表会一直空着
                if (selectedCategory.value != null && list.none { it.id == selectedCategory.value }) {
                    selectedCategory.value = null
                }
            }
        }
    }

    fun setQuery(value: String) { query.value = value }

    fun selectCategory(id: String?) { selectedCategory.value = id }

    fun toggleVisibility() {
        viewModelScope.launch {
            settings.setCodesVisible(!uiState.value.flags.codesVisible)
        }
    }

    fun moveToTrash(id: String) {
        viewModelScope.launch { repository.moveToTrash(id) }
    }

    /**
     * 记住某个分组的展开/收起状态。
     *
     * 串行化读取-写入：连续点两个分组时，若都基于同一份旧集合写回，后一次会把前一次覆盖掉。
     */
    fun toggleGroupCollapsed(key: String) {
        viewModelScope.launch {
            collapseMutex.withLock {
                val current = settings.settings.first().collapsedGroups
                settings.setCollapsedGroups(if (key in current) current - key else current + key)
            }
        }
    }

    /**
     * 首页拖动文件夹后提交新的分组顺序（含「未分类」）。
     *
     * 分类顺序仍按 id 写回 sortOrder，与排序页共用；「未分类」不属于分类表，
     * 单独记一个锚点（紧邻其前的分类）。空分类不显示但保持原位。
     */
    fun commitGroupOrder(orderedKeys: List<String>) {
        if (orderedKeys.isEmpty()) return
        viewModelScope.launch {
            val all = repository.categories()
            val byId = all.associateBy { it.id }
            val currentIds = all.map { it.id }
            val merged = mergeCategoryOrder(currentIds, orderedKeys)
            if (merged != currentIds) {
                repository.reorderCategories(merged.mapNotNull(byId::get))
            }
            // 「未分类」不可见时顺序里没有它，不能拿默认值把锚点冲掉
            if (UNGROUPED_KEY in orderedKeys) {
                settings.setUngroupedGroupAnchor(ungroupedAnchorOf(orderedKeys))
            }
        }
    }
}
