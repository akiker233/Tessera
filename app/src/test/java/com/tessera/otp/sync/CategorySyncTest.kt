package com.tessera.otp.sync

import com.tessera.otp.data.db.entity.CategoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类云同步测试。
 *
 * 守两件事：
 *  1. 分类要随包往返，删除（墓碑）要能在设备间传播；
 *  2. 两台设备各自新建的同名分类会让 name 唯一索引互相覆盖（静默丢数据），
 *     必须去重成一条并把失败方条目的引用重映射到保留方。
 */
class CategorySyncTest {

    private fun category(id: String, name: String, updatedAt: Long = 1L, deletedAt: Long? = null) =
        VaultCategory(id = id, name = name, updatedAt = updatedAt, deletedAt = deletedAt)

    @Test
    fun `快照携带分类`() {
        val entity = CategoryEntity(id = "c1", name = "工作", updatedAt = 9L)
        val snapshot = SyncCodec.toSnapshot(emptyList(), listOf(entity), now = 3L)
        assertEquals(SyncCodec.CURRENT_VERSION, snapshot.version)
        val cat = snapshot.categories.single()
        assertEquals("c1", cat.id)
        assertEquals("工作", cat.name)
    }

    @Test
    fun `整包加解密后分类不丢`() {
        val entity = CategoryEntity(
            id = "c1",
            name = "工作",
            colorArgb = 0xFF112233.toInt(),
            sortOrder = 2,
            updatedAt = 5L
        )
        val payload = SyncCodec.encode(SyncCodec.toSnapshot(emptyList(), listOf(entity), now = 1L), "pw")
        val cat = SyncCodec.decode(payload, "pw").categories.single()
        assertEquals("工作", cat.name)
        assertEquals(0xFF112233.toInt(), cat.colorArgb)
        assertEquals(2, cat.sortOrder)
    }

    @Test
    fun `仅单侧存在的分类被收录`() {
        val out = Merge.mergeCategories(listOf(category("a", "A")), listOf(category("b", "B")))
        assertEquals(listOf("a", "b"), out.merged.map { it.id })
        assertTrue(out.idRemap.isEmpty())
    }

    @Test
    fun `同 id 分类 updatedAt 新者胜`() {
        val out = Merge.mergeCategories(listOf(category("a", "旧", 1L)), listOf(category("a", "新", 5L)))
        assertEquals("新", out.merged.single().name)
    }

    @Test
    fun `删除传播：时间相同时墓碑优先`() {
        val out = Merge.mergeCategories(
            listOf(category("a", "工作", updatedAt = 3L)),
            listOf(category("a", "工作", updatedAt = 3L, deletedAt = 3L))
        )
        assertTrue(out.merged.single().isDeleted)
    }

    @Test
    fun `同名分类去重：保留新者并把失败方转墓碑`() {
        val out = Merge.mergeCategories(
            listOf(category("a", "工作", updatedAt = 9L)),
            listOf(category("b", "工作", updatedAt = 4L)),
            now = 100L
        )
        assertEquals(listOf("a"), out.merged.filter { !it.isDeleted }.map { it.id })
        assertEquals(mapOf("b" to "a"), out.idRemap)

        val tomb = out.merged.single { it.id == "b" }
        assertTrue(tomb.isDeleted)
        assertEquals(CategoryEntity.tombstoneName("b"), tomb.name)
    }

    @Test
    fun `同名去重结果与输入顺序无关`() {
        val x = category("a", "工作", updatedAt = 9L)
        val y = category("b", "工作", updatedAt = 4L)
        val first = Merge.mergeCategories(listOf(x), listOf(y), now = 100L)
        val second = Merge.mergeCategories(listOf(y), listOf(x), now = 100L)
        assertEquals("a", first.merged.single { !it.isDeleted }.id)
        assertEquals("a", second.merged.single { !it.isDeleted }.id)
        assertEquals(first.idRemap, second.idRemap)
    }

    @Test
    fun `分类实体与可移植分类可无损互转`() {
        val entity = CategoryEntity(
            id = "c1",
            name = "工作",
            colorArgb = 0xFF445566.toInt(),
            sortOrder = 3,
            updatedAt = 8L,
            deletedAt = 7L
        )
        assertEquals(entity, VaultCategory.from(entity).toEntity())
    }
}
