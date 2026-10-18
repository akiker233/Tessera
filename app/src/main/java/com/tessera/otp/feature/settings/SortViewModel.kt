package com.tessera.otp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.data.repo.Account
import com.tessera.otp.data.repo.AccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 排序页的一个分类行。 */
data class SortCategory(val id: String, val name: String, val colorArgb: Int)

/** 排序页的一个验证码行。 */
data class SortAccount(
    val id: String,
    val issuer: String,
    val accountName: String,
    val iconKey: String?
)

/** 一个分组（分类 / 未分类 / 全部分类时的平铺列表）及其组内验证码顺序。 */
data class SortAccountGroup(
    val key: String,
    val name: String,
    val colorArgb: Int?,
    val accounts: List<SortAccount>
)

data class SortUiState(
    val categories: List<SortCategory> = emptyList(),
    val accountGroups: List<SortAccountGroup> = emptyList(),
    val loading: Boolean = true
)

/**
 * 排序页：分类文件夹顺序 + 各分类内的验证码顺序。
 *
 * 顺序只在拖动松手时落库，拖动过程中改的是界面本地列表，
 * 既减少写库次数，也避免每跨过一行就触发一次同步。
 */
class SortViewModel(private val repository: AccountRepository) : ViewModel() {

    val state: StateFlow<SortUiState> =
        combine(repository.observeCategories(), repository.observeActive()) { categories, accounts ->
            val row: (Account) -> SortAccount = {
                SortAccount(
                    id = it.id,
                    issuer = it.issuer.ifBlank { "未命名" },
                    accountName = it.accountName,
                    iconKey = it.entity.iconKey
                )
            }
            val knownIds = categories.map { it.id }.toSet()
            val groups = categories.mapNotNull { category ->
                val list = accounts.filter { it.entity.categoryId == category.id }
                if (list.isEmpty()) null
                else SortAccountGroup(category.id, category.name, category.colorArgb, list.map(row))
            }.toMutableList()
            val ungrouped = accounts.filter {
                it.entity.categoryId == null || it.entity.categoryId !in knownIds
            }
            if (ungrouped.isNotEmpty()) {
                groups += SortAccountGroup(UNGROUPED_GROUP_KEY, "未分类", null, ungrouped.map(row))
            }
            // 一个分类都没有时首页是平铺列表，排序页也给一个整体分组，行为与旧版一致
            if (groups.isEmpty() && accounts.isNotEmpty()) {
                groups += SortAccountGroup(FLAT_GROUP_KEY, "全部", null, accounts.map(row))
            }
            SortUiState(
                categories = categories.map { SortCategory(it.id, it.name, it.colorArgb) },
                accountGroups = groups,
                loading = false
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortUiState())

    fun commitCategories(orderedIds: List<String>) {
        viewModelScope.launch {
            val byId = repository.categories().associateBy { it.id }
            repository.reorderCategories(orderedIds.mapNotNull(byId::get))
        }
    }

    fun commitAccountGroup(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderAccountsWithinGroup(orderedIds) }
    }

    private companion object {
        /** 未分类（含指向已删除分类的孤儿条目）的分组 key。 */
        const val UNGROUPED_GROUP_KEY = "ungrouped"
        /** 没有任何分类时，全部验证码归入一个平铺分组。 */
        const val FLAT_GROUP_KEY = "all"
    }
}
