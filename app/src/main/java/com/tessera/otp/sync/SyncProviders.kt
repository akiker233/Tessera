package com.tessera.otp.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** 拉取结果：文本内容 + 版本标记（用于乐观并发）。 */
data class RemoteFile(val content: String, val etag: String?)

/** 同步后端抽象。实现必须线程安全（OkHttpClient 本身线程安全）。 */
interface SyncProvider {
    val displayName: String

    /** 读取云端同步包；不存在时返回 null。 */
    suspend fun pull(): RemoteFile?

    /** 写入云端同步包；expectedEtag 非空时要求服务端版本未变化（不支持的服务忽略）。 */
    suspend fun push(content: String, expectedEtag: String?): Boolean

    /** 删除云端同步包（只删远端，不影响本机数据）；云端本就不存在时同样成功（幂等）。 */
    suspend fun delete(): Boolean
}

class SyncException(message: String, val code: Int? = null) : Exception(message)

/** 共享的 HTTP 客户端与错误处理。 */
internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun execute(request: Request): Response = withContext(Dispatchers.IO) {
        try {
            Http.client.newCall(request).execute()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw SyncException(describe(t))
        }
    }

    /** 把 HTTP 状态码翻译成用户能看懂的错误。 */
    fun describe(response: Response): String = when (response.code) {
        401 -> "认证失败（401）：请检查令牌或密码"
        403 -> "无权限（403）：请检查令牌的作用域"
        404 -> "远端路径不存在（404）"
        409, 412 -> "远端已被其他设备修改（" + response.code + "）：请先拉取再推送"
        429 -> "请求过于频繁（429）：请稍后重试"
        in 500..599 -> "远端服务异常（" + response.code + "）"
        else -> "HTTP " + response.code + " " + response.message
    }

    /**
     * 把底层网络异常翻译成用户能看懂的错误。
     *
     * 直接在 UI 上抛出 `Trust anchor for certification path not found` 之类的 Java 原始信息
     * 用户无法行动，这里统一换成可操作的提示（自建 WebDAV 常见的就是证书与明文两类问题）。
     */
    fun describe(t: Throwable): String = when (t) {
        is SyncException -> t.message ?: "同步失败"
        is javax.net.ssl.SSLPeerUnverifiedException,
        is javax.net.ssl.SSLHandshakeException ->
            "TLS 证书校验失败：请在系统设置里安装该服务器的证书，或改用 http 地址"
        is java.net.UnknownHostException -> "无法解析主机名：" + (t.message ?: "请检查地址")
        is java.net.ConnectException -> "无法连接服务器：" + (t.message ?: "连接被拒绝")
        is java.net.SocketTimeoutException -> "连接超时：请检查地址与网络"
        else -> t.message ?: t::class.java.simpleName
    }
}

/**
 * WebDAV 后端。
 *
 * 只用 GET/PUT 两个动词：GET 取文件与 ETag，PUT 写入（带 If-Match 做乐观并发）。
 * 自签证书/明文 HTTP 由 network_security_config.xml 的用户配置决定，这里不做特殊处理。
 */
class WebDavProvider(
    private val baseUrl: String,
    private val user: String,
    private val password: String,
    private val fileName: String = "tessera.vault"
) : SyncProvider {

    override val displayName = "WebDAV"

    private fun fileUrl(): String {
        val base = baseUrl.trim().trimEnd('/')
        return base + "/" + fileName
    }

    private fun builder(): Request.Builder {
        val request = Request.Builder()
        if (user.isNotBlank()) {
            request.header("Authorization", Credentials.basic(user, password))
        }
        return request
    }

    override suspend fun pull(): RemoteFile? {
        val response = Http.execute(builder().url(fileUrl()).get().build())
        response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return RemoteFile(content = it.body?.string().orEmpty(), etag = it.header("ETag"))
        }
    }

    override suspend fun push(content: String, expectedEtag: String?): Boolean {
        val request = builder().url(fileUrl())
        if (expectedEtag != null) request.header("If-Match", expectedEtag)
        val response = Http.execute(request.put(content.toRequestBody(Http.jsonType)).build())
        response.use {
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return true
        }
    }

    override suspend fun delete(): Boolean {
        val response = Http.execute(builder().url(fileUrl()).delete().build())
        response.use {
            if (it.code == 404) return true
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return true
        }
    }
}

/**
 * GitHub Gist 后端。
 *
 * 使用 Gist API（单文件 tessera.vault）：
 *  - 无 Gist ID 时创建新私有 Gist；创建返回的 id 会写回设置，后续复用。
 *  - 有 Gist ID 时 PATCH 更新文件内容。
 */
class GistProvider(
    private val token: String,
    private val gistId: String?,
    private val fileName: String = "tessera.vault",
    private val onGistCreated: (String) -> Unit = {}
) : SyncProvider {

    override val displayName = "GitHub Gist"

    override suspend fun pull(): RemoteFile? {
        val id = gistId ?: return null
        val request = Request.Builder()
            .url("https://api.github.com/gists/" + id)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .get()
            .build()
        val response = Http.execute(request)
        response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            val body = it.body?.string().orEmpty()
            val etag = it.header("ETag")
            return RemoteFile(extractGistFile(body), etag)
        }
    }

    override suspend fun push(content: String, expectedEtag: String?): Boolean {
        val body = buildString {
            append("{\"files\":{\"")
            append(fileName)
            append("\":{\"content\":")
            append(jsonString(content))
            append("}}}")
        }
        val url = if (gistId == null) "https://api.github.com/gists" else "https://api.github.com/gists/" + gistId
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .method(if (gistId == null) "POST" else "PATCH", body.toRequestBody(Http.jsonType))
            .build()
        val response = Http.execute(request)
        response.use {
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            if (gistId == null) {
                val newId = extractGistId(it.body?.string().orEmpty())
                if (newId != null) onGistCreated(newId)
            }
            return true
        }
    }

    override suspend fun delete(): Boolean {
        // 尚未创建过 Gist（无 gistId）时云端本就没有数据，直接视为成功
        val id = gistId ?: return true
        val request = Request.Builder()
            .url("https://api.github.com/gists/" + id)
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer " + token)
            .delete()
            .build()
        val response = Http.execute(request)
        response.use {
            if (it.code == 404) return true
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return true
        }
    }

    /** 极简 JSON 取值：定位 "content":" 后按 JSON 字符串规则反转义。 */
    private fun extractGistFile(json: String): String {
        val key = "\"" + fileName + "\""
        val index = json.indexOf(key)
        if (index < 0) return ""
        val contentIndex = json.indexOf("\"content\"", index)
        if (contentIndex < 0) return ""
        val start = json.indexOf('\"', json.indexOf(':', contentIndex))
        if (start < 0) return ""
        return readJsonString(json, start)
    }

    private fun extractGistId(json: String): String? {
        val index = json.indexOf("\"id\"")
        if (index < 0) return null
        val start = json.indexOf('\"', json.indexOf(':', index))
        if (start < 0) return null
        return readJsonString(json, start)
    }

    private fun readJsonString(json: String, quoteIndex: Int): String {
        val sb = StringBuilder()
        var i = quoteIndex + 1
        while (i < json.length) {
            val ch = json[i]
            when {
                ch == '\\' && i + 1 < json.length -> {
                    when (json[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            if (i + 5 < json.length) {
                                val hex = json.substring(i + 2, i + 6).toIntOrNull(16)
                                if (hex != null) sb.append(hex.toChar())
                                i += 4
                            }
                        }
                        else -> sb.append(json[i + 1])
                    }
                    i += 2
                }
                ch == '"' -> return sb.toString()
                else -> { sb.append(ch); i++ }
            }
        }
        return sb.toString()
    }

    /** 把任意文本编码成 JSON 字符串字面量。 */
    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.append("\"").toString()
    }
}

/**
 * GitHub 仓库后端（Contents API）。
 *
 * PUT 时带 sha 参数（由上一次 GET 返回），实现"版本不匹配则拒绝覆盖"的乐观并发。
 */
class GitHubRepoProvider(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val path: String = "tessera.vault",
    private val branch: String = "main"
) : SyncProvider {

    override val displayName = "GitHub 仓库"

    private fun apiUrl(): String =
        "https://api.github.com/repos/" + owner + "/" + repo + "/contents/" + path

    private fun request(): Request.Builder = Request.Builder()
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer " + token)
        .header("User-Agent", "Tessera-Android")

    override suspend fun pull(): RemoteFile? {
        val response = Http.execute(request().url(apiUrl() + "?ref=" + branch).get().build())
        response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            val body = it.body?.string().orEmpty()
            val encoded = extractField(body, "content")
            val sha = extractField(body, "sha")
            val decoded = runCatching {
                String(android.util.Base64.decode(encoded.replace("\n", ""), android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrElse { throw SyncException("远端文件 Base64 解码失败") }
            return RemoteFile(decoded, sha)
        }
    }

    override suspend fun push(content: String, expectedEtag: String?): Boolean {
        val encoded = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val body = buildString {
            append("{\"message\":\"update tessera vault\",\"branch\":")
            append(quote(branch))
            append(",\"content\":")
            append(quote(encoded))
            if (expectedEtag != null) {
                append(",\"sha\":")
                append(quote(expectedEtag))
            }
            append("}")
        }
        val response = Http.execute(request().url(apiUrl()).put(body.toRequestBody(Http.jsonType)).build())
        response.use {
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return true
        }
    }

    override suspend fun delete(): Boolean {
        // Contents API 删除必须带当前 sha：先 GET 一次；文件不存在时 pull 返回 null → 幂等成功
        val sha = pull()?.etag ?: return true
        val body = buildString {
            append("{\"message\":\"delete tessera vault\",\"branch\":")
            append(quote(branch))
            append(",\"sha\":")
            append(quote(sha))
            append("}")
        }
        val response = Http.execute(request().url(apiUrl()).delete(body.toRequestBody(Http.jsonType)).build())
        response.use {
            if (it.code == 404) return true
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return true
        }
    }

    private fun extractField(json: String, field: String): String {
        val index = json.indexOf("\"" + field + "\"")
        if (index < 0) return ""
        val colon = json.indexOf(':', index)
        val start = json.indexOf('\"', colon)
        if (start < 0) return ""
        val end = json.indexOf('\"', start + 1)
        if (end < 0) return ""
        return json.substring(start + 1, end)
    }

    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
