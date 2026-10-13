package com.tessera.otp.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** 合并规则测试：覆盖新增、双方修改、墓碑删除与稳定性。 */
class MergeTest {

    private fun entry(id: String, rev: Long, updatedAt: Long, deletedAt: Long? = null) =
        VaultEntry(id = id, issuer = "issuer", accountName = "acct", secretBase32 = "cipher", syncRev = rev, updatedAt = updatedAt, deletedAt = deletedAt)

    @Test
    fun `仅单侧存在的条目会被收录`() {
        val local = listOf(entry("a", 1, 100))
        val remote = listOf(entry("b", 1, 100))
        val result = Merge.merge(local, remote)
        assertEquals(2, result.merged.size)
        assertEquals(1, result.addedLocally)
        assertEquals(1, result.addedRemotely)
    }

    @Test
    fun `同一条目 rev 大者胜`() {
        val local = listOf(entry("a", 3, 100))
        val remote = listOf(entry("a", 5, 100))
        val result = Merge.merge(local, remote)
        assertEquals(5L, result.merged.single().syncRev)
        assertEquals(1, result.remoteWins)
        assertEquals(0, result.localWins)
    }

    @Test
    fun `rev 相同则 updatedAt 新者胜`() {
        val local = listOf(entry("a", 2, 500))
        val remote = listOf(entry("a", 2, 900))
        assertEquals(900L, Merge.merge(local, remote).merged.single().updatedAt)
    }

    @Test
    fun `完全相同则保留本地`() {
        val local = listOf(entry("a", 2, 500))
        val remote = listOf(entry("a", 2, 500))
        val result = Merge.merge(local, remote)
        assertEquals(1, result.localWins)
        assertEquals(0, result.remoteWins)
    }

    @Test
    fun `墓碑删除参与合并`() {
        // 本地仍存在，远端已删除（rev 更大）
        val local = listOf(entry("a", 1, 100))
        val remote = listOf(entry("a", 2, 200, deletedAt = 150))
        val merged = Merge.merge(local, remote).merged.single()
        assertEquals(150L, merged.deletedAt)
    }

    @Test
    fun `合并结果顺序稳定`() {
        val local = listOf(entry("b", 1, 1), entry("a", 1, 1))
        val remote = listOf(entry("c", 1, 1))
        val first = Merge.merge(local, remote).merged.map { it.id }
        val second = Merge.merge(remote, local).merged.map { it.id }
        assertEquals(listOf("a", "b", "c"), first)
        assertEquals(first, second)
    }
}
