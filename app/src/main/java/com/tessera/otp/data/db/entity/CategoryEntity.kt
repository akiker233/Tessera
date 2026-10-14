package com.tessera.otp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** 分类（用于首页分组与筛选）。 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val colorArgb: Int = 0xFF3B5BDB.toInt(),
    val sortOrder: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * 非空即已删除（墓碑）。
     *
     * 分类不像账号有「回收站」，墓碑只用于同步：删除必须能被其它设备看到，
     * 否则别的设备下次同步会把已删分类再「复活」回来。
     * 删除时会同时把 name 改成含 id 的唯一串，避免墓碑与「同名新建分类」撞唯一索引。
     */
    val deletedAt: Long? = null
) {
    val isDeleted: Boolean get() = deletedAt != null

    companion object {
        /** 墓碑分类名前缀：删除时把 name 改成它 + id，避免与「同名新建分类」撞唯一索引。 */
        const val TOMBSTONE_PREFIX = "__deleted__"

        fun tombstoneName(id: String): String = TOMBSTONE_PREFIX + id
    }
}
