package com.tessera.otp.data.repo

import android.util.Log
import com.tessera.otp.data.db.AccountDao
import com.tessera.otp.data.db.CategoryDao
import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.data.db.entity.CategoryEntity
import com.tessera.otp.data.keystore.SecretCipher
import com.tessera.otp.domain.OtpAlgorithm
import com.tessera.otp.domain.OtpSpec
import com.tessera.otp.domain.TotpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 解密后的运行时条目（明文密钥只存在于内存）。 */
data class Account(
    val entity: AccountEntity,
    val secret: ByteArray
) {
    val id: String get() = entity.id
    val issuer: String get() = entity.issuer
    val accountName: String get() = entity.accountName
    val isHotp: Boolean get() = entity.isHotp

    fun spec(): OtpSpec = OtpSpec(
        secret = secret,
        algorithm = runCatching { OtpAlgorithm.valueOf(entity.algorithm.uppercase()) }.getOrDefault(OtpAlgorithm.SHA1),
        digits = entity.digits,
        period = entity.period,
        isSteam = entity.isSteam
    )

    /**
     * 密钥是否可用。
     *
     * 旧版本同步包里的条目密钥由**生成备份那台设备**的 Keystore 加密，本机永远解不开；
     * 这类条目仍然显示（可查看/删除/重设密钥），但不再出码。
     */
    val usable: Boolean get() = secret.isNotEmpty()

    fun code(epochSeconds: Long = System.currentTimeMillis() / 1000): String =
        if (usable) TotpEngine.generate(spec(), epochSeconds) else ""

    fun nextCode(epochSeconds: Long = System.currentTimeMillis() / 1000): String =
        if (usable) TotpEngine.nextToken(spec(), epochSeconds) else ""

    fun secondsRemaining(epochSeconds: Long = System.currentTimeMillis() / 1000): Int =
        TotpEngine.secondsRemaining(entity.period, epochSeconds)

    override fun equals(other: Any?): Boolean = other is Account && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

class AccountRepository(
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val cipher: SecretCipher
) {

    private val _localChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** 本地账号数据变更信号：每次写操作发出一次，供上层触发静默同步。 */
    val localChanges: SharedFlow<Unit> = _localChanges.asSharedFlow()

    private var muteDepth = 0
    private var changedWhileMuted = false

    /**
     * 同步写库期间静音变更信号，避免「同步写入 → 再触发同步」的自激循环。
     * 静音期间若发生真实本地改动（例如同步过程中用户编辑），解除静音后补发一次信号。
     */
    suspend fun <T> muted(block: suspend () -> T): T {
        muteDepth++
        try {
            return block()
        } finally {
            muteDepth--
            if (muteDepth == 0 && changedWhileMuted) {
                changedWhileMuted = false
                _localChanges.tryEmit(Unit)
            }
        }
    }

    private fun notifyLocalChange() {
        if (muteDepth > 0) {
            changedWhileMuted = true
        } else {
            _localChanges.tryEmit(Unit)
        }
    }

    // 每条密钥都要走 Keystore 解密，属于阻塞 IPC；放到 IO，避免列表查询回流时卡住主线程
    fun observeActive(): Flow<List<Account>> =
        accountDao.observeActive().map { list -> list.map(::decrypt) }.flowOn(Dispatchers.IO)

    fun search(query: String): Flow<List<Account>> =
        accountDao.search(query).map { list -> list.map(::decrypt) }.flowOn(Dispatchers.IO)

    fun observeTrash(): Flow<List<Account>> =
        accountDao.observeTrash().map { list -> list.map(::decrypt) }.flowOn(Dispatchers.IO)

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun categories(): List<CategoryEntity> = categoryDao.all()

    suspend fun byId(id: String): Account? = accountDao.byId(id)?.let(::decrypt)

    suspend fun activeCount(): Int = accountDao.activeCount()

    /** 新增/更新：自动加密密钥并递增同步修订号。 */
    suspend fun upsert(account: AccountEntity, plainSecret: ByteArray) {
        val now = System.currentTimeMillis()
        // 编辑表单不携带排序信息：已有条目保留原位置，新条目追加到列表末尾
        val sortOrder = accountDao.byId(account.id)?.sortOrder ?: (accountDao.maxSortOrder() + 1)
        val entity = account.copy(
            secretEnc = cipher.encrypt(plainSecret),
            sortOrder = sortOrder,
            updatedAt = now,
            syncRev = account.syncRev + 1
        )
        accountDao.upsert(entity)
        notifyLocalChange()
    }

    /** 批量新增（导入/扫码）：已有条目保留原排序，新条目依次追加到末尾。 */
    suspend fun upsertAll(entities: List<Pair<AccountEntity, ByteArray>>) {
        if (entities.isEmpty()) return
        val now = System.currentTimeMillis()
        val existingOrders = accountDao.all().associate { it.id to it.sortOrder }
        var nextOrder = (existingOrders.values.maxOrNull() ?: -1) + 1
        accountDao.upsertAll(
            entities.map { pair ->
                val entity = pair.first
                entity.copy(
                    secretEnc = cipher.encrypt(pair.second),
                    sortOrder = existingOrders[entity.id] ?: nextOrder++,
                    updatedAt = if (entity.updatedAt == 0L) now else entity.updatedAt,
                    syncRev = entity.syncRev + 1
                )
            }
        )
        notifyLocalChange()
    }

    /**
     * 按传入的 id 顺序重排账号：sortOrder 依次写为 0,1,2…
     *
     * 只写顺序发生变化的条目；每条都会递增 syncRev/updatedAt，让新顺序能同步出去。
     */
    suspend fun reorderAccounts(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val current = accountDao.active().associateBy { it.id }
        var changed = false
        orderedIds.forEachIndexed { index, id ->
            val entity = current[id] ?: return@forEachIndexed
            if (entity.sortOrder != index) {
                accountDao.updateSortOrder(id, index)
                changed = true
            }
        }
        if (changed) notifyLocalChange()
    }

    /**
     * 只调整一组账号的内部顺序：这组成员仍占据它们在全局顺序中的原有槽位，
     * 其它账号的位置不受影响。
     *
     * 首页在各分组内按全局 sortOrder 渲染，因此组成员的槽位顺序即组内顺序。
     */
    suspend fun reorderAccountsWithinGroup(orderedIds: List<String>) {
        val merged = mergeGroupOrder(accountDao.active().map { it.id }, orderedIds) ?: return
        reorderAccounts(merged)
    }

    suspend fun moveToTrash(id: String) {
        accountDao.moveToTrash(id)
        notifyLocalChange()
    }

    suspend fun restore(id: String) {
        accountDao.restore(id)
        notifyLocalChange()
    }

    suspend fun hardDelete(id: String) {
        accountDao.hardDeleteById(id)
        notifyLocalChange()
    }

    suspend fun emptyTrash() {
        accountDao.emptyTrash()
        notifyLocalChange()
    }

    /** 按保留期清理回收站，返回清理条数。 */
    suspend fun purgeExpired(retentionDays: Int): Int {
        if (retentionDays <= 0) return 0
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
        val expired = accountDao.trashOlderThan(cutoff)
        expired.forEach { accountDao.hardDelete(it) }
        if (expired.isNotEmpty()) notifyLocalChange()
        return expired.size
    }

    suspend fun addCategory(name: String, colorArgb: Int): CategoryEntity {
        // 新分类排在末尾：给一个比现有最大值更大的 sortOrder，排序才稳定（否则全为 0 会退化成按名称排）
        val nextOrder = (categoryDao.all().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val entity = CategoryEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            colorArgb = colorArgb,
            sortOrder = nextOrder
        )
        categoryDao.upsert(entity)
        return entity
    }

    /** 重命名 / 改颜色统一走 upsert（按 id 覆盖）。 */
    suspend fun upsertCategory(category: CategoryEntity) {
        categoryDao.upsert(category.copy(updatedAt = System.currentTimeMillis()))
    }

    /** 按传入顺序重排：sortOrder 依次写为 0,1,2…（observeAll 正是按 sortOrder 排序）。 */
    suspend fun reorderCategories(ordered: List<CategoryEntity>) {
        var changed = false
        ordered.forEachIndexed { index, category ->
            if (category.sortOrder != index) {
                categoryDao.upsert(category.copy(sortOrder = index, updatedAt = System.currentTimeMillis()))
                changed = true
            }
        }
        // 分类合并按 updatedAt 判定，顺序变化同样需要触发一次静默同步
        if (changed) notifyLocalChange()
    }

    /** 删除分类：先把该分类下的条目（含回收站）改回未分类，再写墓碑（软删除）。 */
    suspend fun deleteCategory(category: CategoryEntity) {
        accountDao.clearCategory(category.id)
        categoryDao.softDelete(category.id, CategoryEntity.tombstoneName(category.id))
        notifyLocalChange()
    }

    /** 单条归类：categoryId 为 null 表示移回「未分类」。 */
    suspend fun setAccountCategory(id: String, categoryId: String?) {
        accountDao.setCategory(id, categoryId)
        notifyLocalChange()
    }

    /**
     * 批量归类：把 [accountIds] 全部归入 [categoryId]，并把原本属于该分类但不在其中的条目移出。
     *
     * 「不在选中集合里的同分类条目」要移出，是为了让「勾选 = 结果」符合直觉：
     * 取消勾选即等于从该分类移除。其它分类的条目不受影响。
     */
    suspend fun assignCategory(categoryId: String, accountIds: Set<String>) {
        val current = accountDao.active()
        current.forEach { account ->
            val shouldBelong = account.id in accountIds
            if (shouldBelong && account.categoryId != categoryId) {
                accountDao.setCategory(account.id, categoryId)
            } else if (!shouldBelong && account.categoryId == categoryId) {
                accountDao.setCategory(account.id, null)
            }
        }
        notifyLocalChange()
    }

    suspend fun categoryAccountCount(categoryId: String): Int = categoryDao.accountCount(categoryId)

    /** 同步用：导出全量（含回收站墓碑）。 */
    suspend fun allEntities(): List<AccountEntity> = accountDao.all()

    /** 同步用：导出全量分类（含墓碑，删除才能被其它设备看到）。 */
    suspend fun categoriesWithDeleted(): List<CategoryEntity> = categoryDao.allIncludingDeleted()

    /** 同步用：写入合并后的分类（含墓碑）。 */
    suspend fun upsertCategories(categories: List<CategoryEntity>) {
        if (categories.isNotEmpty()) categoryDao.upsertAll(categories)
    }

    /**
     * 同步用：导出全量条目并**解密**密钥。
     *
     * 云端包必须携带可移植的密钥（外层由同步口令加密），否则换机后本机永远解不开——
     * 旧版 v1 正是把本机 Keystore 密文原样塞进包，别的设备一同步就解密失败。
     * 本机解不开的条目直接跳过：它无法参与同步，也绝不能拿空密钥覆盖云端。
     */
    suspend fun entitiesWithSecrets(): List<Pair<AccountEntity, ByteArray>> =
        accountDao.all().mapNotNull { entity ->
            val secret = runCatching { cipher.decrypt(entity.secretEnc) }.getOrNull()
            if (secret == null || secret.isEmpty()) null else entity to secret
        }

    /** 同步用：写入合并结果（用本机主密钥重新加密；不改写修订号与时间戳）。 */
    suspend fun replaceAllWithSecrets(entries: List<Pair<AccountEntity, ByteArray>>) {
        accountDao.upsertAll(entries.map { (entity, secret) -> entity.copy(secretEnc = cipher.encrypt(secret)) })
    }

    /** 旧格式（v1）同步包里的密钥是别的设备 Keystore 的密文：本机解得开才可用。 */
    fun tryDecryptSecret(encoded: String): ByteArray? =
        runCatching { cipher.decrypt(encoded) }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 密文 → 明文缓存。
     *
     * 每次列表重发（同步写库、切换搜索等）都会把整表重新解密一遍，
     * 缓存命中后只需一次查表；密文由随机 IV 保证「内容变 → 密文变」，不会读到旧明文。
     */
    private val secretCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>) = size > 256
        }
    )

    /**
     * 解密单个条目。
     *
     * 解不开时**绝不能抛**：条目一旦落库，首页每个 tick 都会读它，
     * 抛出就变成「一打开就崩、还来不及删」的死循环（实测两台中招）。
     */
    private fun decrypt(entity: AccountEntity): Account {
        val secret = secretCache.getOrPut(entity.secretEnc) {
            runCatching { cipher.decrypt(entity.secretEnc) }
                .onFailure { Log.w(TAG, "密钥解密失败（多为旧格式同步包），条目降级为不可用 id=" + entity.id, it) }
                .getOrDefault(ByteArray(0))
        }
        return Account(entity = entity, secret = secret)
    }

    /**
     * 启动预热：先把整表密钥解出来，首页首次查询就能直接命中缓存。
     *
     * 由 Application 在 IO 线程与首帧并行调用；与首页收集并发时，
     * Keystore 解密最多做两遍（同一条重复解密无害），换取首屏等待时间更短。
     */
    suspend fun warmUp() {
        accountDao.active().forEach(::decrypt)
    }

    private companion object {
        const val TAG = "AccountRepository"
    }
}

/**
 * 把 [orderedIds] 的相对顺序写回它们在 [allIds] 中占据的槽位，返回合并后的完整顺序。
 *
 * 用于组内排序：组外账号的位置保持不变，组内成员按拖出的新顺序填回原槽位。
 * [orderedIds] 含未知 id、重复 id 或少于两个成员时返回 null（调用方跳过写库）。
 */
internal fun mergeGroupOrder(allIds: List<String>, orderedIds: List<String>): List<String>? {
    if (orderedIds.size < 2) return null
    if (orderedIds.toSet().size != orderedIds.size) return null
    val members = orderedIds.toHashSet()
    if (!allIds.containsAll(members)) return null
    val iterator = orderedIds.iterator()
    return allIds.map { if (it in members) iterator.next() else it }
}
