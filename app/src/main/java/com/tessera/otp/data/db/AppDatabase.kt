package com.tessera.otp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tessera.otp.data.db.entity.AccountEntity
import com.tessera.otp.data.db.entity.CategoryEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * 加密数据库。
 *
 * 口令不是明文存储：由 [com.tessera.otp.data.keystore.DatabasePassphrase] 用
 * Android Keystore 里的 AES-GCM 主密钥加密后保存在 SharedPreferences，
 * 运行时解密取得 SQLCipher 的 raw key。
 */
@Database(
    entities = [AccountEntity::class, CategoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        private const val DB_NAME = "tessera.db"

        fun build(context: Context, passphrase: ByteArray): AppDatabase {
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
