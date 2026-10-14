package com.tessera.otp.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        private const val DB_NAME = "tessera.db"

        /**
         * v1 -> v2：分类表新增 deletedAt（墓碑列，供云同步传播删除）。
         *
         * 必须用真实迁移：账号与分类同库，`fallbackToDestructiveMigration` 一旦触发会把
         * 用户的真实 2FA 数据整库清空。加一列对旧数据无副作用（旧行 deletedAt 为 NULL）。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN deletedAt INTEGER")
            }
        }

        fun build(context: Context, passphrase: ByteArray): AppDatabase {
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
