package com.tessera.otp.feature.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.data.repo.AccountRepository
import com.tessera.otp.domain.Base32
import com.tessera.otp.importer.ImportIssue
import com.tessera.otp.importer.ImportResult
import com.tessera.otp.importer.ImportSource
import com.tessera.otp.importer.Importers
import com.tessera.otp.importer.ParsedAccount
import com.tessera.otp.importer.ParsedCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImportUiState(
    val fileName: String? = null,
    val detected: ImportSource? = null,
    val preview: List<ParsedAccount> = emptyList(),
    /** 备份携带的分类（仅 Tessera 备份有）。 */
    val categories: List<ParsedCategory> = emptyList(),
    val issues: List<ImportIssue> = emptyList(),
    val encrypted: Boolean = false,
    /** 该加密备份可用密码解密（目前仅 Tessera）。 */
    val canDecrypt: Boolean = false,
    val imported: Int = 0,
    val message: String? = null,
    val busy: Boolean = false
)

/**
 * 导入流程：解析 -> 预览 -> 落库。
 *
 * 解析与落库分离，是为了在真正写入前让用户看到「将新增多少条、哪些条目失败」。
 * 已存在的同一 (issuer, accountName) 会被跳过，避免重复导入产生两份相同验证码。
 * Tessera 加密备份会先要求密码，解密后连同分类一起预览。
 */
class ImportViewModel(private val repository: AccountRepository) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** 最近一次解析的原文，供「输入密码后重新解密」使用。 */
    private var lastContent: String? = null

    /** 解析文本内容（来自文件选择或扫码结果）。 */
    fun parseContent(fileName: String, content: String) {
        lastContent = content
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            applyParsed(fileName, Importers.detectAndParse(fileName, content))
        }
    }

    /** 用密码解密并解析 Tessera 加密备份。 */
    fun submitPassword(password: String) {
        val content = lastContent ?: return
        val fileName = _state.value.fileName ?: "backup.tessera"
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            applyParsed(fileName, Importers.parseTesseraWithPassword(content, password))
        }
    }

    /** 指定来源解析（用户手动选择时）。 */
    fun parseWithSource(source: ImportSource, content: String) {
        lastContent = content
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val result = Importers.parseWith(source, content)
            _state.value = _state.value.copy(
                detected = source,
                preview = result.accounts,
                categories = result.categories,
                issues = result.issues,
                busy = false
            )
        }
    }

    private fun applyParsed(fileName: String, result: ImportResult) {
        val decryptable = result.encrypted && result.detectedSource == ImportSource.TESSERA
        _state.value = ImportUiState(
            fileName = fileName,
            detected = result.detectedSource,
            preview = result.accounts,
            categories = result.categories,
            issues = result.issues,
            encrypted = result.encrypted,
            canDecrypt = decryptable,
            message = when {
                decryptable -> result.issues.firstOrNull()?.reason ?: "备份已加密，请输入密码"
                result.encrypted -> "该备份已加密，需要口令（当前版本未支持离线解密）"
                result.accounts.isEmpty() && result.issues.isNotEmpty() -> "没有可导入的条目"
                else -> null
            },
            busy = false
        )
    }

    /** 确认导入预览中的条目（含 Tessera 备份里的分类）。 */
    fun confirmImport() {
        val preview = _state.value.preview
        if (preview.isEmpty()) return
        val parsedCategories = _state.value.categories
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            // 先把备份携带的分类准备好：按名字匹配已有分类，缺失则新建，拿到 名字 -> id
            val categoryIdByName = HashMap<String, String>()
            repository.categories().forEach { categoryIdByName[it.name] = it.id }
            for (parsed in parsedCategories) {
                val name = parsed.name.trim()
                if (name.isEmpty() || categoryIdByName.containsKey(name)) continue
                val created = repository.addCategory(name, parsed.colorArgb)
                categoryIdByName[name] = created.id
            }

            val existing = repository.allEntities()
            val seen = existing.map { it.issuer + "|" + it.accountName }.toMutableSet()
            val toInsert = mutableListOf<Pair<AccountEntity, ByteArray>>()
            val failed = mutableListOf<ImportIssue>()

            for (account in preview) {
                val key = account.issuer + "|" + account.accountName
                if (key in seen) {
                    failed += ImportIssue(account.issuer.ifBlank { account.accountName }, "已存在，跳过")
                    continue
                }
                val secret = runCatching { Base32.decode(account.secretBase32) }.getOrNull()
                if (secret == null || secret.isEmpty()) {
                    failed += ImportIssue(account.issuer.ifBlank { account.accountName }, "密钥不是合法 Base32")
                    continue
                }
                seen += key
                toInsert += AccountEntity(
                    issuer = account.issuer,
                    accountName = account.accountName,
                    algorithm = account.algorithm,
                    digits = account.digits,
                    period = account.period,
                    isSteam = account.isSteam,
                    categoryId = account.categoryName?.trim()?.let { categoryIdByName[it] }
                ) to secret
            }

            if (toInsert.isNotEmpty()) repository.upsertAll(toInsert)
            _state.value = _state.value.copy(
                busy = false,
                imported = toInsert.size,
                preview = emptyList(),
                categories = emptyList(),
                issues = _state.value.issues + failed,
                message = "已导入 " + toInsert.size + " 条" + if (failed.isNotEmpty()) "，" + failed.size + " 条被跳过" else ""
            )
        }
    }

    /** 界面层识别失败时回传原因，复用导入提示弹窗展示。 */
    fun reportScanFailure(reason: String) {
        _state.value = _state.value.copy(message = reason)
    }

    /**
     * 扫码 / 相册识别出的二维码：解析后直接落库（配对流程不需要预览确认页），
     * 返回密钥合法的条目（新增或已存在的同一条），供「即将完成」页展示实时令牌。
     */
    suspend fun importScanned(content: String): List<ParsedAccount> {
        val result = Importers.detectAndParse("扫描结果", content)
        if (result.accounts.isEmpty()) return emptyList()

        val seen = repository.allEntities().map { it.issuer + "|" + it.accountName }.toMutableSet()
        val toInsert = mutableListOf<Pair<AccountEntity, ByteArray>>()
        val valid = mutableListOf<ParsedAccount>()
        for (account in result.accounts) {
            val secret = runCatching { Base32.decode(account.secretBase32) }.getOrNull()
            if (secret == null || secret.isEmpty()) continue
            valid += account
            val key = account.issuer + "|" + account.accountName
            if (key in seen) continue
            seen += key
            toInsert += AccountEntity(
                issuer = account.issuer,
                accountName = account.accountName,
                algorithm = account.algorithm,
                digits = account.digits,
                period = account.period,
                isSteam = account.isSteam
            ) to secret
        }
        if (toInsert.isNotEmpty()) repository.upsertAll(toInsert)
        return valid
    }

    fun reset() { _state.value = ImportUiState() }
}
