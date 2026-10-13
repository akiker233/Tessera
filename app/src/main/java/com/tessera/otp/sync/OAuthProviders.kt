package com.tessera.otp.sync

import android.content.Context
import com.tessera.otp.BuildConfig
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import java.util.Collections
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Drive 后端，与 2FAS 使用**同款技术栈**：
 *  - 依赖 com.google.apis:google-api-services-drive（v3）+ com.google.api-client:google-api-client-android；
 *  - GoogleAccountCredential 限定 DRIVE_APPDATA 范围，不申请完整 Drive 权限；
 *  - 备份写入 appDataFolder，文件名 tessera-backup-1.json；
 *  - 先 files.list 找同名文件：找到则 files.update，否则 files.create（parents = appDataFolder）。
 *
 * 参考实现：2fas-android-6.0.3/data/cloud/src/main/java/com/twofasapp/data/cloud/googledrive/GoogleDriveImpl.kt
 *
 * 需要你配置：在 Google Cloud Console 建 Android OAuth 客户端（登记 applicationId + SHA-1），
 * 应用内用 GoogleDriveAuth（Play Services）授权 drive.appdata，账号名经设置项传进来。
 * 注意：Android 客户端**没有也不需要**客户端 ID/密钥，运行时靠登记过的包名 + SHA-1 识别应用。
 */
class GoogleDriveProvider(
    private val context: Context,
    /** 令牌提供者：每次调用取一个有效 access token（由 GoogleDriveAuth 静默续期）。 */
    private val tokenProvider: suspend () -> String?,
    private val fileName: String = BACKUP_FILE_NAME
) : SyncProvider {

    override val displayName = "Google Drive"

    /**
     * 注意：不再使用 `GoogleAccountCredential`。
     * 它的接口要求传入账号名并使用 GoogleSignIn 授权，而 GoogleSignIn 已从
     * play-services-auth 22.0.0 移除；改为直接注入 Bearer 令牌（同样只申请 drive.appdata）。
     */
    private suspend fun drive(): Drive {
        val token = tokenProvider()
            ?: throw SyncException("尚未授权 Google Drive：请在「云同步」中点『登录 Google 账号』（范围 drive.appdata）")
        val initializer = HttpRequestInitializer { request ->
            request.headers.set("Authorization", "Bearer " + token)
        }
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName("Tessera")
            .build()
    }

    private fun findFileId(drive: Drive): String? {
        val result = drive.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '" + fileName + "' and trashed = false")
            .setFields("files(id,name,modifiedTime)")
            .execute()
        return result.files?.firstOrNull()?.id
    }

    override suspend fun pull(): RemoteFile? = withContext(Dispatchers.IO) {
        try {
            val drive = drive()
            val id = findFileId(drive) ?: return@withContext null
            val text = drive.files().get(id).executeMediaAsInputStream()
                .use { stream -> String(stream.readBytes(), Charsets.UTF_8) }
            RemoteFile(content = text, etag = id)
        } catch (t: Throwable) {
            throw SyncException(describe(t))
        }
    }

    override suspend fun push(content: String, expectedEtag: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val drive = drive()
            val media = ByteArrayContent.fromString("application/json", content)
            val existing = findFileId(drive)
            if (existing == null) {
                val metadata = DriveFile()
                    .setName(fileName)
                    .setMimeType("application/json")
                    .setParents(listOf("appDataFolder"))
                drive.files().create(metadata, media).execute()
            } else {
                val target = if (expectedEtag.isNullOrBlank()) existing else expectedEtag
                drive.files().update(target, DriveFile().setName(fileName), media).execute()
            }
            true
        } catch (t: Throwable) {
            throw SyncException(describe(t))
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is GoogleJsonResponseException -> {
            when (t.details?.code) {
                401 -> "Google Drive 认证失败（401）：请重新登录"
                403 -> "Google Drive 权限不足（403）：确认授权范围为 drive.appdata"
                404 -> "云端备份文件不存在（404）"
                else -> "Google Drive 调用失败：" + (t.details?.message ?: t.message)
            }
        }
        else -> t.message ?: t::class.java.simpleName
    }

    companion object {
        /** 仅 appdata 范围，与 2FAS 一致。 */
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

        /** 备份文件名（对齐 2FAS 的版本化命名）。 */
        const val BACKUP_FILE_NAME = "tessera-backup-1.json"

        /** TODO(用户配置)：Google Cloud Console 建好 Android OAuth 客户端后填入。 */

    }
}

/**
 * OneDrive 后端（Microsoft Graph，appRoot 私有目录）。
 *
 * 认证说明（需要在 Azure 门户配置后替换占位常量）：
 *  1. 注册应用，重定向 URI 用自定义 scheme（例如 msal<clientId>://auth）；
 *  2. 把客户端 ID 写到项目根目录 local.properties 的 `ONEDRIVE_CLIENT_ID=`（经 BuildConfig 注入 [ONEDRIVE_CLIENT_ID]，不入库）；
 *  3. 用 MSAL 走交互式登录拿 accessToken，再调用 [setAccessToken]。
 */
class OneDriveProvider(private var accessToken: String?) : SyncProvider {

    override val displayName = "OneDrive"

    fun setAccessToken(token: String?) { accessToken = token }

    private fun requireToken(): String =
        accessToken ?: throw SyncException("尚未授权 OneDrive（需要先完成登录并注入 accessToken）")

    /** 统一由常量拼出条目 URL：读和写必须指向同一个文件，否则会出现"写得进、读不出"。 */
    private fun itemUrl(): String =
        "https://graph.microsoft.com/v1.0/me/drive/special/approot:/" + BACKUP_FILE_NAME

    private fun contentUrl(): String = itemUrl() + ":/content"

    override suspend fun pull(): RemoteFile? {
        // 第一步：取元数据（eTag 用于推送时的 If-Match，downloadUrl 用于下载）
        val metaRequest = Request.Builder()
            .url(itemUrl())
            .header("Authorization", "Bearer " + requireToken())
            .header("Accept", "application/json")
            .get()
            .build()
        val metaResponse = Http.execute(metaRequest)
        var etag: String? = null
        var downloadUrl: String? = null
        metaResponse.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            etag = it.header("ETag") ?: it.header("eTag")
            val body = it.body?.string().orEmpty()
            // Graph 有时把 & 转义成 \u0026，取出来后要还原，否则下载 URL 不可用
            downloadUrl = extractField(body, "@microsoft.graph.downloadUrl").replace("\\u0026", "&")
        }
        // 第二步：按 downloadUrl 下载内容（该 URL 自带临时凭证，无需再带 Authorization）
        val url = downloadUrl
        if (url.isNullOrBlank()) throw SyncException("OneDrive 未返回 downloadUrl，无法下载备份")
        val contentResponse = Http.execute(Request.Builder().url(url).get().build())
        contentResponse.use {
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return RemoteFile(content = it.body?.string().orEmpty(), etag = etag)
        }
    }

    override suspend fun push(content: String, expectedEtag: String?): Boolean {
        val builder = Request.Builder()
            .url(contentUrl())
            .header("Authorization", "Bearer " + requireToken())
        if (expectedEtag != null) builder.header("If-Match", expectedEtag)
        val request = builder.put(content.toRequestBody("application/octet-stream".toMediaType())).build()
        val response = Http.execute(request)
        response.use {
            if (!it.isSuccessful) throw SyncException(Http.describe(it), it.code)
            return true
        }
    }

    /** 极简 JSON 取值（与 GitHub/Gitee 后端同一套做法，避免引入 JSON 解析依赖）。 */
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

    companion object {
        /** 备份文件名（读/写共用同一个常量，避免出现两处不一致）。 */
        const val BACKUP_FILE_NAME = "tessera-backup-1.json"

        /**
         * OneDrive（Azure）应用注册后的客户端 ID。
         *
         * 从 local.properties 的 `ONEDRIVE_CLIENT_ID` 经 BuildConfig 注入，不硬编码进源码/仓库。
         * 未配置时为空串，[com.tessera.otp.sync.OneDriveAuth.isConfigured] 会据此禁用登录入口。
         */
        val ONEDRIVE_CLIENT_ID: String = BuildConfig.ONEDRIVE_CLIENT_ID
    }
}
