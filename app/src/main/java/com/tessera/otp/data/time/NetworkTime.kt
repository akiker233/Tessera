package com.tessera.otp.data.time

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 网络校时结果（供设置页展示）。 */
data class TimeSyncStatus(
    val offsetMillis: Long = 0L,
    val lastSyncAtMillis: Long = 0L,
    val source: String? = null,
    val lastError: String? = null,
    val synced: Boolean = false
)

/**
 * 网络校时。
 *
 * 为什么需要：TOTP 完全依赖时间，系统时间哪怕差十几秒都会算出**错误验证码**。
 *
 * 做法：请求几个 HTTPS 站点，读响应头 `Date`（HTTP 规范要求服务器携带），
 * 用「往返时间的一半」补偿网络延迟，取 RTT 最小的一次作为校准值（多源取优）。
 *
 * 精度：HTTP Date 只有秒级精度（NTP 才有毫秒级），对 30 秒一个时间窗的 TOTP 足够；
 * 若将来要更高精度，可换成 SNTP（UDP 123）。
 *
 * 持久化：校准结果写入 SharedPreferences。下次启动先套用上次偏移，
 * 再在后台重新校准；网络不可用时自动退化为"上次偏移 / 系统时间"，不影响出码。
 */
object NetworkTime {

    private val endpoints = listOf(
        "https://www.baidu.com",
        "https://gitee.com",
        "https://www.cloudflare.com",
        "https://www.google.com/generate_204"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val httpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }

    private const val PREFS = "tessera_time"
    private const val KEY_OFFSET = "offset_ms"
    private const val KEY_SYNCED_AT = "synced_at"

    @Volatile private var offsetMillis: Long = 0L
    @Volatile private var status = TimeSyncStatus()
    private var prefs: android.content.SharedPreferences? = null

    /** 状态流：启动时的静默校准、手动校准都会推给它，设置页据此实时刷新。 */
    private val _statusFlow = MutableStateFlow(status)
    val statusFlow: StateFlow<TimeSyncStatus> = _statusFlow.asStateFlow()

    val current: TimeSyncStatus get() = status

    private fun publish(next: TimeSyncStatus) {
        status = next
        _statusFlow.value = next
    }

    /** 经网络校准后的当前时间；未校准（或校准失败）时等同系统时间。 */
    fun nowMillis(): Long = System.currentTimeMillis() + offsetMillis

    fun nowSeconds(): Long = nowMillis() / 1000L

    /** 在 Application.onCreate 调用：恢复上次偏移。 */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs?.getLong(KEY_OFFSET, 0L) ?: 0L
        val syncedAt = prefs?.getLong(KEY_SYNCED_AT, 0L) ?: 0L
        if (syncedAt > 0L) {
            offsetMillis = saved
            publish(TimeSyncStatus(offsetMillis = saved, lastSyncAtMillis = syncedAt, synced = true))
        }
    }

    /** 立即校准（多源取 RTT 最小者）。 */
    suspend fun sync(): TimeSyncStatus = withContext(Dispatchers.IO) {
        var bestOffset: Long? = null
        var bestSource: String? = null
        var bestRtt = Long.MAX_VALUE
        var lastError: String? = null

        for (url in endpoints) {
            try {
                val t0 = System.currentTimeMillis()
                client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                    val header = response.header("Date")
                    val t1 = System.currentTimeMillis()
                    if (header == null) {
                        lastError = "响应缺少 Date 头"
                    } else {
                        val serverMs = parseDate(header)
                        if (serverMs == null) {
                            lastError = "Date 头无法解析：" + header
                        } else {
                            val rtt = (t1 - t0).coerceAtLeast(0L)
                            if (rtt < bestRtt) {
                                bestRtt = rtt
                                bestOffset = serverMs + rtt / 2 - t1
                                bestSource = url
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                lastError = t.message ?: t::class.java.simpleName
            }
        }

        val chosen = bestOffset
        val result = if (chosen != null) {
            offsetMillis = chosen
            val now = System.currentTimeMillis()
            prefs?.edit()?.putLong(KEY_OFFSET, chosen)?.putLong(KEY_SYNCED_AT, now)?.apply()
            TimeSyncStatus(offsetMillis = chosen, lastSyncAtMillis = now, source = bestSource, synced = true)
        } else {
            status.copy(lastError = lastError ?: "所有时间源均不可达")
        }
        publish(result)
        result
    }

    private fun parseDate(value: String): Long? = synchronized(httpDate) {
        runCatching { httpDate.parse(value)?.time }.getOrNull()
    }
}
