package com.tessera.otp.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.data.db.entity.CategoryEntity
import com.tessera.otp.data.repo.AccountRepository
import com.tessera.otp.domain.Base32
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailUiState(
    val form: DetailForm = DetailForm(),
    val categories: List<CategoryEntity> = emptyList(),
    val isNew: Boolean = true,
    val saved: Boolean = false,
    val error: String? = null,
    val existingSecret: ByteArray? = null
)

/**
 * 详情页 ViewModel。
 *
 * 编辑已有条目时【不回填密钥明文】：表单里的 secret 留空即表示沿用原密钥，
 * 这样密钥不会重新出现在输入框、截图或输入法候选里。
 */
class AccountDetailViewModel(
    private val repository: AccountRepository,
    private val accountId: String?
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState(isNew = accountId == null))
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val categories = repository.categories()
            _state.value = _state.value.copy(categories = categories)
            val id = accountId
            if (id != null) {
                val account = repository.byId(id)
                if (account != null) {
                    _state.value = _state.value.copy(
                        isNew = false,
                        existingSecret = account.secret,
                        form = DetailForm(
                            id = account.id,
                            issuer = account.issuer,
                            accountName = account.accountName,
                            // 预填当前密钥（Base32）：默认掩码显示，点眼睛可查看，也可直接修改
                            secret = Base32.encode(account.secret),
                            algorithm = account.spec().algorithm,
                            digits = account.entity.digits,
                            period = account.entity.period,
                            isSteam = account.entity.isSteam,
                            isHotp = account.entity.isHotp,
                            counter = account.entity.counter,
                            categoryId = account.entity.categoryId,
                            iconKey = account.entity.iconKey,
                            note = account.entity.note.orEmpty()
                        )
                    )
                }
            }
        }
    }

    fun update(form: DetailForm) { _state.value = _state.value.copy(form = form, error = null) }

    fun addCategory(name: String) {
        viewModelScope.launch {
            repository.addCategory(name, DEFAULT_CATEGORY_COLOR)
            _state.value = _state.value.copy(categories = repository.categories())
        }
    }

    /** 删除分类：该分类下的账号会变为未分类；当前表单若正用该分类则同步清空选择。 */
    fun deleteCategory(category: CategoryEntity) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            val current = _state.value
            val form = if (current.form.categoryId == category.id) {
                current.form.copy(categoryId = null)
            } else {
                current.form
            }
            _state.value = current.copy(form = form, categories = repository.categories())
        }
    }

    fun save() {
        val form = _state.value.form
        if (form.issuer.isBlank() && form.accountName.isBlank()) {
            _state.value = _state.value.copy(error = "请至少填写服务名称或账号")
            return
        }

        // 新条目必须提供密钥；编辑时留空则沿用原密钥
        val secretBytes = when {
            form.secret.isNotBlank() -> runCatching { Base32.decode(form.secret) }.getOrNull()
            else -> _state.value.existingSecret
        }
        if (secretBytes == null || secretBytes.isEmpty()) {
            _state.value = _state.value.copy(error = "密钥无效，请检查 Base32 内容")
            return
        }

        viewModelScope.launch {
            val entity = AccountEntity(
                id = form.id ?: java.util.UUID.randomUUID().toString(),
                issuer = form.issuer.trim(),
                accountName = form.accountName.trim(),
                algorithm = form.algorithm.name,
                digits = form.digits,
                period = form.period,
                isSteam = form.isSteam,
                isHotp = form.isHotp,
                counter = form.counter,
                categoryId = form.categoryId,
                iconKey = form.iconKey,
                note = form.note.ifBlank { null },
                syncRev = 0L
            )
            // 编辑时保留原 id 与 createdAt
            val existing = form.id?.let { repository.byId(it) }
            val merged = if (existing != null) {
                entity.copy(createdAt = existing.entity.createdAt, syncRev = existing.entity.syncRev, deletedAt = existing.entity.deletedAt)
            } else {
                entity
            }
            repository.upsert(merged, secretBytes)
            _state.value = _state.value.copy(saved = true)
        }
    }

    fun moveToTrash() {
        val id = _state.value.form.id ?: return
        viewModelScope.launch {
            repository.moveToTrash(id)
            _state.value = _state.value.copy(saved = true)
        }
    }

    companion object {
        private val DEFAULT_CATEGORY_COLOR = 0xFF3B5BDB.toInt()
    }
}
