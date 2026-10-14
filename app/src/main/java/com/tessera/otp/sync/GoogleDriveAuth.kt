package com.tessera.otp.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.tessera.otp.data.prefs.LEGACY_ACCOUNT_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Google Drive 授权（GMS **AuthorizationClient**）。**支持同时连接多个 Google 账号**。
 *
 * 为什么不是 GoogleSignIn：`com.google.android.gms.auth.api.signin.GoogleSignIn`
 * 在 play-services-auth **22.0.0 中已被移除**（只剩 SignInAccount 等），
 * 因此这里使用身份服务新的 Authorization API。
 *
 * 多账号：授权请求里带上 [Account]（`AuthorizationRequest.Builder.setAccount`），
 * Play Services 就会针对"这一个"账号发令牌。账号由应用预先选定——
 * GMS 不会把已授权的账号列给应用，所以"绑定了哪些账号"记在
 * [com.tessera.otp.data.prefs.AppSettings.googleAccounts] 里。
 *
 * 与 OneDrive 的差别：
 *  - 令牌由 Play Services 托管，**不需要**保存 refresh token；
 *  - 已授权后每次都可**静默**取到有效 access token（后台同步因此可用）；
 *  - 因此不需要也不存在"客户端 ID / 密钥"的填写（运行时靠包名 + SHA-1 识别应用）。
 *
 * 前置条件见 docs/google-drive-setup.md：控制台启用 Drive API + 建 Android OAuth 客户端
 * （登记当前 APK 的 applicationId 与签名 SHA-1，调试包为 com.tessera.otp.debug）。
 */
object GoogleDriveAuth {

    private val scope = Scope(GoogleDriveProvider.DRIVE_APPDATA_SCOPE)

    /**
     * 账号 id → GMS Account。
     *
     * 空串与 [LEGACY_ACCOUNT_ID] 都返回 null：表示"不指定账号"，
     * 由 Play Services 用它当前持有的那个授权（旧版单账号配置就是这样，迁移后仍可用）。
     */
    private fun accountOf(accountId: String?): Account? =
        if (accountId.isNullOrBlank() || accountId == LEGACY_ACCOUNT_ID) null
        else Account(accountId, "com.google")

    private fun request(accountId: String?): AuthorizationRequest {
        val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(scope))
        accountOf(accountId)?.let { builder.setAccount(it) }
        return builder.build()
    }

    /**
     * 静默获取 access token：已授权时直接返回；
     * 若需要用户交互（首次授权/被撤销）则返回 null——后台同步遇到它会给出明确提示。
     */
    suspend fun accessToken(context: Context, accountId: String?): String? = withContext(Dispatchers.IO) {
        try {
            val result = Identity.getAuthorizationClient(context).authorize(request(accountId)).await()
            if (result.hasResolution()) null else result.accessToken
        } catch (t: Throwable) {
            null
        }
    }

    /** 是否需要用户交互（用于界面判断"未连接"）。 */
    suspend fun needsAuthorization(context: Context, accountId: String?): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Identity.getAuthorizationClient(context).authorize(request(accountId)).await().hasResolution()
        }.getOrDefault(true)
    }

    /** 发起交互式授权；调用方需处理 resolution（PendingIntent）与直接成功两种情况。 */
    fun authorize(context: Context, accountId: String?): Task<AuthorizationResult> =
        Identity.getAuthorizationClient(context).authorize(request(accountId))

    /** 解析授权界面回跳的 Intent。 */
    fun resultFromIntent(context: Context, intent: Intent?): AuthorizationResult? = intent?.let {
        runCatching { Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(it) }.getOrNull()
    }

    /**
     * 系统账号选择器。用它而不是自己读 [AccountManager.getAccountsByType]，
     * 是为了**不申请 GET_ACCOUNTS 权限**：选择结果由 Activity 以
     * [AccountManager.KEY_ACCOUNT_NAME] 带回。
     */
    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)

    /** 把 GMS 异常翻译成可读中文（这类问题不提示就只能靠猜）。 */
    fun describe(t: Throwable): String = when (t) {
        is ApiException -> when (t.statusCode) {
            10 -> "开发者错误(10)：控制台登记的『包名 + SHA-1』与当前安装的 APK 不一致"
            12500 -> "授权失败(12500)：请确认已在控制台配置 OAuth 同意屏幕"
            12501 -> "已取消授权"
            7 -> "网络错误(7)：请检查网络后重试"
            else -> "授权失败(code=" + t.statusCode + ")：" + (t.message ?: "")
        }
        else -> t.message ?: t::class.java.simpleName
    }
}
