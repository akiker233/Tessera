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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    fun observeActive(): Flow<List<Account>> = accountDao.observeActive().map { list -> list.map(::decrypt) }

    fun search(query: String): Flow<List<Account>> = accountDao.search(query).map { list -> list.map(::decrypt) }

    fun observeTrash(): Flow<List<Account>> = accountDao.observeTrash().map { list -> list.map(::decrypt) }

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    suspend fun categories(): List<CategoryEntity> = categoryDao.all()

    suspend fun byId(id: String): Account? = accountDao.byId(id)?.let(::decrypt)

    suspend fun activeCount(): Int = accountDao.activeCount()

    /** 新增/更新：自动加密密钥并递增同步修订号。 */
    suspend fun upsert(account: AccountEntity, plainSecret: ByteArray) {
        val now = System.currentTimeMillis()
        val entity = account.copy(
            secretEnc = cipher.encrypt(plainSecret),
            updatedAt = now,
            syncRev = account.syncRev + 1
        )
        accountDao.upsert(entity)
        notifyLocalChange()
    }

    suspend fun upsertAll(entities: List<Pair<AccountEntity, ByteArray>>) {
        val now = System.currentTimeMillis()
        accountDao.upsertAll(
            entities.map { pair ->
                val entity = pair.first
                entity.copy(
                    secretEnc = cipher.encrypt(pair.second),
                    updatedAt = if (entity.updatedAt == 0L) now else entity.updatedAt,
                    syncRev = entity.syncRev + 1
                )
            }
        )
        notifyLocalChange()
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
        val entity = CategoryEntity(id = UUID.randomUUID().toString(), name = name, colorArgb = colorArgb)
        categoryDao.upsert(entity)
        return entity
    }

    /** 删除分类：先把该分类下的条目（含回收站）改回未分类，再删除分类本身。 */
    suspend fun deleteCategory(category: CategoryEntity) {
        accountDao.clearCategory(category.id)
        categoryDao.delete(category)
        notifyLocalChange()
    }

    suspend fun categoryAccountCount(categoryId: String): Int = categoryDao.accountCount(categoryId)

    /** 同步用：导出全量（含回收站墓碑）。 */
    suspend fun allEntities(): List<AccountEntity> = accountDao.all()

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
     * 解密单个条目。
     *
     * 解不开时**绝不能抛**：条目一旦落库，首页每个 tick 都会读它，
     * 抛出就变成「一打开就崩、还来不及删」的死循环（实测两台中招）。
     */
    private fun decrypt(entity: AccountEntity): Account {
        val secret = runCatching { cipher.decrypt(entity.secretEnc) }
            .onFailure { Log.w(TAG, "密钥解密失败（多为旧格式同步包），条目降级为不可用 id=" + entity.id, it) }
            .getOrDefault(ByteArray(0))
        return Account(entity = entity, secret = secret)
    }

    private companion object {
        const val TAG = "AccountRepository"
    }
}
