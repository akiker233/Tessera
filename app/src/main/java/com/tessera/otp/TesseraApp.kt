package com.tessera.otp

import android.app.Application
import android.util.Log
import kotlinx.coroutines.launch
import com.tessera.otp.di.AppContainer

/**
 * 应用入口。
 *
 * sqlcipher-android 4.6+ 的原生库由 net.zetetic.database.sqlcipher 内部静态初始化自动加载；
 * 这里再显式 loadLibrary 一次，只为了更早暴露缺库问题（例如 ABI 裁剪错误）。
 */
class TesseraApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // 网络校时：先恢复上次偏移，再在后台静默重新校准（失败即退化为系统时间）
        com.tessera.otp.data.time.NetworkTime.init(this)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.tessera.otp.data.time.NetworkTime.sync()
        }
        container.workScheduler = { interval -> com.tessera.otp.sync.SyncWorker.schedule(this, interval) }
        // 周期自动同步：按用户设置注册（默认关闭时不排任务）
        container.schedulePeriodicSync(this)
        try {
            System.loadLibrary("sqlcipher")
        } catch (t: UnsatisfiedLinkError) {
            Log.e(TAG, "SQLCipher 原生库加载失败", t)
        }
    }

    companion object {
        private const val TAG = "TesseraApp"
    }
}
