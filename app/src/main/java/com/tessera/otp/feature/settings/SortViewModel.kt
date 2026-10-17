package com.tessera.otp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val categoryId: String?,
    val categoryName: String?
)

data class SortUiState(
    val categories: List<SortCategory> = emptyList(),
    val accounts: List<SortAccount> = emptyList(),
    val loading: Boolean = true
)

/**
 * 排序页：分类文件夹与验证码的显示顺序。
 *
 * 顺序只在拖动松手时落库，拖动过程中改的是界面本地列表，
 * 既减少写库次数，也避免每跨过一行就触发一次同步。
 */
class SortViewModel(private val repository: AccountRepository) : ViewModel() {

    val state: StateFlow<SortUiState> =
        combine(repository.observeCategories(), repository.observeActive()) { categories, accounts ->
            val categoryNames = categories.associate { it.id to it.name }
            SortUiState(
                categories = categories.map { SortCategory(it.id, it.name, it.colorArgb) },
                accounts = accounts.map {
                    SortAccount(
                        id = it.id,
                        issuer = it.issuer.ifBlank { "未命名" },
                        accountName = it.accountName,
                        categoryId = it.entity.categoryId,
                        categoryName = it.entity.categoryId?.let(categoryNames::get)
                    )
                },
                loading = false
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SortUiState())

    fun commitCategories(orderedIds: List<String>) {
        viewModelScope.launch {
            val byId = repository.categories().associateBy { it.id }
            repository.reorderCategories(orderedIds.mapNotNull(byId::get))
        }
    }

    fun commitAccounts(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderAccounts(orderedIds) }
    }
}
