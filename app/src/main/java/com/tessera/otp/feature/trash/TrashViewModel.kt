package com.tessera.otp.feature.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.data.repo.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrashViewModel(private val repository: AccountRepository) : ViewModel() {

    private val _items = MutableStateFlow<List<TrashItem>>(emptyList())
    val items: StateFlow<List<TrashItem>> = _items.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeTrash().collect { list ->
                val now = System.currentTimeMillis()
                _items.value = list.map { account ->
                    val deletedAt = account.entity.deletedAt ?: now
                    TrashItem(
                        id = account.id,
                        issuer = account.issuer.ifBlank { "未命名" },
                        accountName = account.accountName,
                        deletedAt = deletedAt,
                        remainingDays = 0
                    )
                }
            }
        }
    }

    fun restore(id: String) {
        viewModelScope.launch { repository.restore(id) }
    }

    fun deleteForever(id: String) {
        viewModelScope.launch { repository.hardDelete(id) }
    }

    fun emptyTrash() {
        viewModelScope.launch { repository.emptyTrash() }
    }

    /** 清理超过保留期的条目（打开回收站时调用）。 */
    fun purge(retentionDays: Int) {
        viewModelScope.launch { repository.purgeExpired(retentionDays) }
    }
}
