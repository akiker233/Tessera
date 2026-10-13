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
    val updatedAt: Long = System.currentTimeMillis()
);
