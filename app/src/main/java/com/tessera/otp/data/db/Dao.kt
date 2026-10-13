package com.tessera.otp.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.data.db.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    /** 未删除条目（按分类/排序）。 */
    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL ORDER BY sortOrder ASC, issuer COLLATE NOCASE ASC")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL ORDER BY sortOrder ASC, issuer COLLATE NOCASE ASC")
    suspend fun active(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL AND id = :id")
    suspend fun byId(id: String): AccountEntity?

    /** 搜索：issuer / 账号名 模糊匹配（调用方做 200ms 防抖）。 */
    @Query(
        "SELECT * FROM accounts WHERE deletedAt IS NULL AND (issuer LIKE '%' || :q || '%' OR accountName LIKE '%' || :q || '%') " +
            "ORDER BY sortOrder ASC, issuer COLLATE NOCASE ASC"
    )
    fun search(q: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE deletedAt IS NULL AND categoryId = :categoryId ORDER BY sortOrder ASC")
    fun byCategory(categoryId: String): Flow<List<AccountEntity>>

    @Query("SELECT COUNT(*) FROM accounts WHERE deletedAt IS NULL")
    suspend fun activeCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(accounts: List<AccountEntity>)

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun hardDelete(account: AccountEntity)

    /**
     * 按 id 彻底删除。
     *
     * 不能复用 [byId]：那条查询带 `deletedAt IS NULL`，对回收站里的条目永远返回 null，
     * 导致「彻底删除」静默失效（软删除的条目根本查不到）。
     */
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun hardDeleteById(id: String)

    /** 移入回收站（软删除）。 */
    @Query("UPDATE accounts SET deletedAt = :now, updatedAt = :now, syncRev = syncRev + 1 WHERE id = :id")
    suspend fun moveToTrash(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE accounts SET deletedAt = NULL, updatedAt = :now, syncRev = syncRev + 1 WHERE id = :id")
    suspend fun restore(id: String, now: Long = System.currentTimeMillis())

    /** 删除分类时把该分类下的条目（含回收站）改回未分类。 */
    @Query("UPDATE accounts SET categoryId = NULL, updatedAt = :now, syncRev = syncRev + 1 WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: String, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM accounts WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun observeTrash(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun trashOlderThan(cutoff: Long): List<AccountEntity>

    @Query("DELETE FROM accounts WHERE deletedAt IS NOT NULL")
    suspend fun emptyTrash()

    /** 同步用：取出全部条目（含墓碑）。 */
    @Query("SELECT * FROM accounts")
    suspend fun all(): List<AccountEntity>

    @Query("UPDATE accounts SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    suspend fun all(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("SELECT COUNT(*) FROM accounts WHERE categoryId = :categoryId AND deletedAt IS NULL")
    suspend fun accountCount(categoryId: String): Int
}
