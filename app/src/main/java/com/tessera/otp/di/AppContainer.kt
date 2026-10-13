package com.tessera.otp.di

import android.content.Context
import com.tessera.otp.data.db.AppDatabase
import com.tessera.otp.data.icon.IconRepository
import com.tessera.otp.data.keystore.DatabasePassphrase
import com.tessera.otp.data.keystore.SecretCipher
import com.tessera.otp.data.prefs.SettingsRepository
import com.tessera.otp.data.repo.AccountRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手写依赖容器（不引入 DI 框架，降低构建风险）。
 *
 * 数据库初始化需要访问 Keystore 与 SQLCipher 原生库，属于重操作，
 * 因此通过 accountRepository() 懒初始化并在 IO 线程执行。
 */
class AppContainer(private val context: Context) {

    /** 暴露应用 Context（同步后端的 GoogleAccountCredential 需要它）。 */
    val appContext: Context get() = context

    /** 由 Application 注入的周期同步调度器（避免容器直接依赖 WorkManager）。 */
    var workScheduler: ((com.tessera.otp.data.prefs.SyncInterval) -> Unit)? = null

    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val lockPrefs: LockPrefs by lazy { LockPrefs(context) }
    val icons: IconRepository by lazy { IconRepository(context) }
    val cipher: SecretCipher by lazy { SecretCipher() }

    @Volatile private var passphraseStore: com.tessera.otp.sync.SyncPassphraseStore? = null

    /** 同步口令存储（口令本身用 Keystore 主密钥加密）。 */
    fun syncPassphrase(): com.tessera.otp.sync.SyncPassphraseStore =
        passphraseStore ?: synchronized(this) {
            passphraseStore ?: com.tessera.otp.sync.SyncPassphraseStore(context, cipher).also { passphraseStore = it }
        }

    @Volatile private var database: AppDatabase? = null
    @Volatile private var repository: AccountRepository? = null

    private fun db(): AppDatabase = database ?: synchronized(this) {
        database ?: AppDatabase.build(context, DatabasePassphrase.get(context)).also { database = it }
    }


    /**
     * 注册周期自动同步：读取当前设置里的间隔并交给 WorkManager。
     * 在 Application.onCreate 里调用；关闭自动同步时会取消已有任务。
     */
    fun schedulePeriodicSync(context: android.content.Context) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val interval = settingInterval()
            com.tessera.otp.sync.SyncWorker.schedule(context, interval)
        }
    }

    private suspend fun settingInterval(): com.tessera.otp.data.prefs.SyncInterval =
        runCatching {
            com.tessera.otp.data.prefs.SyncInterval.valueOf(
                settings.settings.first().syncIntervalName
            )
        }.getOrDefault(com.tessera.otp.data.prefs.SyncInterval.OFF)

    suspend fun accountRepository(): AccountRepository =
        repository ?: withContext(Dispatchers.IO) {
            repository ?: AccountRepository(db().accountDao(), db().categoryDao(), cipher).also { repository = it }
        }
}
