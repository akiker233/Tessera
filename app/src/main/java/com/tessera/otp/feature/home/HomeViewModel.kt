package com.tessera.otp.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.data.db.entity.CategoryEntity
import com.tessera.otp.data.prefs.SettingsRepository
import com.tessera.otp.data.repo.Account
import com.tessera.otp.data.repo.AccountRepository
import com.tessera.otp.domain.CodeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /** 每秒 tick，驱动倒计时与"下一令牌"。 */
    private val ticker = flow {
        while (true) {
            // 用网络校准后的时间出码，避免系统时间不准导致验证码错误
            emit(com.tessera.otp.data.time.NetworkTime.nowSeconds())
            delay(1000L)
        }
    }

    private val accounts = combine(query, selectedCategory) { q, category -> q.trim() to category }
        .flatMapLatest { pair ->
            val q = pair.first
            val category = pair.second
            val source = if (q.isEmpty()) repository.observeActive() else repository.search(q)
            if (category == null) source else source.map { list -> list.filter { it.entity.categoryId == category } }
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
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

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
}
