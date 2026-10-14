package com.tessera.otp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.data.db.entity.CategoryEntity
import com.tessera.otp.data.repo.AccountRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 管理页的一行：分类本身 + 该分类下的账号数量。 */
data class CategoryRow(val entity: CategoryEntity, val count: Int)

/** 归类可选的一条账号。 */
data class CategoryAccount(val id: String, val issuer: String, val accountName: String, val categoryId: String?)

data class CategoryManageState(
    val rows: List<CategoryRow> = emptyList(),
    val accounts: List<CategoryAccount> = emptyList()
)

/**
 * 分类（文件夹）管理：新建 / 重命名 / 改色 / 删除 / 排序，以及把账号多选归入某分类。
 *
 * 分类表的 name 有唯一索引且 DAO 用 REPLACE 插入——重名会**静默顶掉**已有分类，
 * 因此增改都在 ViewModel 里先查重，重复直接返回 false 由界面提示。
 */
class CategoryManageViewModel(private val repository: AccountRepository) : ViewModel() {

    val state: StateFlow<CategoryManageState> =
        combine(repository.observeCategories(), repository.observeActive()) { categories, accounts ->
            val counts = accounts.groupingBy { it.entity.categoryId }.eachCount()
            CategoryManageState(
                rows = categories.map { CategoryRow(it, counts[it.id] ?: 0) },
                accounts = accounts.map {
                    CategoryAccount(it.id, it.issuer, it.accountName, it.entity.categoryId)
                }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryManageState())

    /** 新建分类；名称为空或与现有分类重名时返回 false。 */
    fun add(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (state.value.rows.any { it.entity.name.equals(trimmed, ignoreCase = true) }) return false
        viewModelScope.launch { repository.addCategory(trimmed, DEFAULT_CATEGORY_COLOR) }
        return true
    }

    /** 重命名 + 改色；名称非法/重名时返回 false。 */
    fun edit(entity: CategoryEntity, name: String, colorArgb: Int): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (state.value.rows.any { it.entity.id != entity.id && it.entity.name.equals(trimmed, ignoreCase = true) }) return false
        viewModelScope.launch { repository.upsertCategory(entity.copy(name = trimmed, colorArgb = colorArgb)) }
        return true
    }

    fun delete(entity: CategoryEntity) {
        viewModelScope.launch { repository.deleteCategory(entity) }
    }

    /** 把勾选的账号归入该分类；未勾选但原本属于该分类的会被移出。 */
    fun assign(category: CategoryEntity, selectedIds: Set<String>) {
        viewModelScope.launch { repository.assignCategory(category.id, selectedIds) }
    }

    /** 上移/下移：delta = -1 上移，+1 下移；越界则忽略。 */
    fun move(entity: CategoryEntity, delta: Int) {
        val entities = state.value.rows.map { it.entity }
        val index = entities.indexOfFirst { it.id == entity.id }
        if (index < 0) return
        val target = index + delta
        if (target < 0 || target >= entities.size) return
        val reordered = entities.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        viewModelScope.launch { repository.reorderCategories(reordered) }
    }

    companion object {
        val DEFAULT_CATEGORY_COLOR = 0xFF3B5BDB.toInt()
    }
}
