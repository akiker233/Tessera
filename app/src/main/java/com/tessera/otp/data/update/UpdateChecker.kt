package com.tessera.otp.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** GitHub Release 里的一个可下载文件。 */
data class ReleaseAsset(val name: String, val downloadUrl: String)

/**
 * 发现新版本时的信息。
 *
 * [downloadUrl] 为 null 表示 Release 里没有与设备 ABI 匹配的 APK，
 * 此时只能打开 [releaseUrl] 让用户手动挑包。
 */
data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    val downloadUrl: String?,
    val notes: String
)

/** 检查更新失败；[code] 是 HTTP 状态码（网络异常时为 null）。 */
class UpdateException(message: String, val code: Int? = null) : Exception(message)

/**
 * 检查 GitHub 上是否有新版本。
 *
 * 只依赖公开的 Releases API，不需要令牌；未登录请求有每小时 60 次的限流，
 * 手动检查的频率远低于此。云端看不到任何本机数据。
 */
class UpdateChecker(
    private val currentVersion: String,
    private val abis: List<String>,
    private val client: OkHttpClient = defaultClient,
    private val apiUrl: String = DEFAULT_API_URL
) {

    /** @return 有新版本时返回发布信息；已是最新版本时返回 null。 */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Tessera-Android/" + currentVersion)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val body = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateException(
                        when (response.code) {
                            403, 429 -> "GitHub 接口访问受限（" + response.code + "），请稍后再试"
                            in 500..599 -> "GitHub 服务异常（" + response.code + "），请稍后再试"
                            else -> "检查更新失败（HTTP " + response.code + "）"
                        },
                        response.code
                    )
                }
                response.body.string()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: UpdateException) {
            throw e
        } catch (t: Throwable) {
            throw UpdateException(describeNetworkError(t))
        }
        if (body.isBlank()) throw UpdateException("GitHub 返回了空响应")
        parseLatestRelease(body, currentVersion, abis)
    }

    companion object {
        const val DEFAULT_API_URL = "https://api.github.com/repos/akiker233/Tessera/releases/latest"

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * 解析 Releases API 的响应。
 *
 * @return 比 [currentVersion] 新时返回发布信息，否则返回 null
 */
internal fun parseLatestRelease(body: String, currentVersion: String, abis: List<String>): UpdateInfo? {
    val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: throw UpdateException("GitHub 返回了无法解析的数据")
    val tag = root.stringField("tag_name")?.trim()
        ?: throw UpdateException("GitHub 响应里没有版本号")
    val latest = tag.removePrefix("v").removePrefix("V")
    if (!VERSION_PATTERN.matches(latest)) {
        throw UpdateException("无法识别最新版本号：" + tag)
    }
    if (compareVersions(latest, currentVersion) <= 0) return null

    val assets = runCatching {
        root["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
            val asset = element.jsonObject
            val name = asset.stringField("name") ?: return@mapNotNull null
            val url = asset.stringField("browser_download_url") ?: return@mapNotNull null
            ReleaseAsset(name, url)
        }
    }.getOrDefault(emptyList())

    return UpdateInfo(
        latestVersion = latest,
        releaseUrl = root.stringField("html_url") ?: "https://github.com/akiker233/Tessera/releases",
        downloadUrl = pickAsset(assets, abis)?.downloadUrl,
        notes = root.stringField("body").orEmpty()
    )
}

/** 仅接受与设备 ABI 精确匹配的包或 universal 包：装错架构的 APK 会因缺少原生库而崩溃。 */
internal fun pickAsset(assets: List<ReleaseAsset>, abis: List<String>): ReleaseAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    for (abi in abis) {
        apks.firstOrNull { it.name.endsWith("-" + abi + ".apk", ignoreCase = true) }?.let { return it }
    }
    return apks.firstOrNull { it.name.endsWith("-universal.apk", ignoreCase = true) }
}

/**
 * 版本号比较：按点分段比数字，忽略前缀 v 与 -suffix/+build 后缀。
 * 返回负数表示 a < b，0 表示相同，正数表示 a > b。
 */
internal fun compareVersions(a: String, b: String): Int {
    fun parts(value: String): List<Int> = value.trim()
        .removePrefix("v").removePrefix("V")
        .substringBefore('-').substringBefore('+')
        .split('.')
        .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    val left = parts(a)
    val right = parts(b)
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

private val VERSION_PATTERN = Regex("""\d+(\.\d+)*([-+].*)?""")

private fun JsonObject.stringField(key: String): String? =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun describeNetworkError(t: Throwable): String = when (t) {
    is java.net.UnknownHostException -> "无法连接 GitHub，请检查网络或代理后重试"
    is java.net.ConnectException, is java.net.SocketTimeoutException -> "连接 GitHub 超时，请检查网络后重试"
    else -> t.message ?: t::class.java.simpleName
}
