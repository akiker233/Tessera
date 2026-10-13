package com.tessera.otp.sync

import okhttp3.FormBody
import okhttp3.Request

/**
 * Gitee（码云）后端，走 Gitee OpenAPI v5 的仓库文件接口。
 *
 * 与 GitHub 后端的差异（这也是不能直接复用 GitHubRepoProvider 的原因）：
 *  - 端点前缀：https://gitee.com/api/v5/repos/{owner}/{repo}/contents/{path}
 *  - 鉴权：私人令牌以 access_token 作为查询/表单参数，而不是 Authorization 头
 *  - 读取：GET ?access_token=&ref= 返回 JSON，content 为 base64（可能带换行），sha 为当前版本
 *  - 写入：POST 表单，content=base64、message、branch；**更新时必须带 sha**，不带则新建
 */
class GiteeProvider(
    private val token: String,
    private val owner: String,
    private val repo: String,
    private val path: String = "tessera.vault",
    private val branch: String = "master"
) : SyncProvider {

    override val displayName = "Gitee"

    private fun apiUrl(): String =
        "https://gitee.com/api/v5/repos/" + owner + "/" + repo + "/contents/" + path

    override suspend fun pull(): RemoteFile? {
        val url = apiUrl() + "?access_token=" + token + "&ref=" + branch
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val response = Http.execute(request)
        response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            val body = it.body?.string().orEmpty()
            val encoded = extractField(body, "content")
            val sha = extractField(body, "sha")
            val cleaned = encoded.replace("\\n", "").replace("\n", "").replace("\r", "")
            val decoded = runCatching {
                String(android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrElse { throw SyncException("Gitee 文件 Base64 解码失败") }
            return RemoteFile(content = decoded, etag = sha)
        }
    }

    override suspend fun push(content: String, expectedEtag: String?): Boolean {
        val encoded = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val builder = FormBody.Builder()
            .add("access_token", token)
            .add("content", encoded)
            .add("message", "update tessera vault")
            .add("branch", branch)
        if (!expectedEtag.isNullOrBlank()) builder.add("sha", expectedEtag)
        val request = Request.Builder().url(apiUrl()).post(builder.build()).build()
        val response = Http.execute(request)
        response.use {
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return true
        }
    }

    /** 极简 JSON 取值（与 GitHub 后端同一套做法，避免引入 JSON 解析依赖）。 */
    private fun extractField(json: String, field: String): String {
        val index = json.indexOf("\"" + field + "\"")
        if (index < 0) return ""
        val colon = json.indexOf(':', index)
        val start = json.indexOf('"', colon)
        if (start < 0) return ""
        val end = json.indexOf('"', start + 1)
        if (end < 0) return ""
        return json.substring(start + 1, end)
    }
}
