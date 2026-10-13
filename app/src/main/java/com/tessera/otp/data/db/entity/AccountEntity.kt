package com.tessera.otp.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * 验证码条目。
 *
 * 注意：`secretEnc` 只存**密文**（由 VaultCipher 用 Keystore 主密钥做 AES-GCM 加密），
 * 任何日志/导出/云端同步都不应出现明文密钥。
 */
@Entity(
    tableName = "accounts",
    indices = [
        Index("issuer"),
        Index("categoryId"),
        Index("deletedAt"),
        Index(value = ["issuer", "accountName", "secretEnc"], unique = false)
    ]
)
data class AccountEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val issuer: String = "",
    val accountName: String = "",
    /** Base64(密文) —— 由 Keystore 主密钥加密后的 TOTP 密钥。 */
    @ColumnInfo(name = "secretEnc") val secretEnc: String = "",
    /** SHA1 / SHA256 / SHA512 */
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
    val isSteam: Boolean = false,
    val isHotp: Boolean = false,
    val counter: Long = 0L,
    val categoryId: String? = null,
    val sortOrder: Int = 0,
    /** 归一化后的图标键；为空时运行时按 issuer 自动匹配。 */
    val iconKey: String? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 非空即处于回收站。 */
    val deletedAt: Long? = null,
    /** 同步修订号，用于条目级冲突解决（大者胜）。 */
    val syncRev: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt != null
}
