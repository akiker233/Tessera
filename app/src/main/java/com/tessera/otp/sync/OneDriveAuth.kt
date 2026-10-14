package com.tessera.otp.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.tessera.otp.data.keystore.SecretCipher
import com.tessera.otp.data.prefs.LEGACY_ACCOUNT_ID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume

/**
 * OneDrive（微软账号）登录：OAuth 2.0 授权码 + PKCE。**支持同时连接多个微软账号**。
 *
 * 为什么用 PKCE 而不是 client secret：Android 应用属于 OAuth 2.0 "公共客户端"，
 * 任何打包进 APK 的 secret 都等于公开；PKCE 由 AppAuth 生成 code_verifier/challenge 完成校验。
 *
 * 令牌存储：access/refresh token 都用 [SecretCipher]（Android Keystore 主密钥）加密后落盘，
 * 明文令牌不写入磁盘。access token 约 1 小时过期，这里用 refresh token 自动续期。
 * 多账号时每个账号独立一套令牌，键名形如 `账号 id:refresh_enc`。
 *
 * 账号 id：登录成功后从 id_token 里取 `preferred_username`（邮箱）——微软不会让应用预先枚举
 * 账号，只能在拿到令牌后才知道"这是谁"。旧版只支持单账号、也没存过邮箱，
 * 迁移出来的那条统一叫 [LEGACY_ACCOUNT_ID]。
 *
 * 重定向：redirect URI 为 `tessera://auth`，需在 Azure 应用注册里登记成同一个值；
 * AppAuth 自带的 RedirectUriReceiverActivity 会接住重定向（scheme 由 Gradle 的
 * manifestPlaceholders["appAuthRedirectScheme"] 提供）。
 */
object OneDriveAuth {

    private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private const val REDIRECT_URI = "tessera://auth"

    /**
     * 授权时申请的范围：在原有文件范围上加 `openid profile email`，
     * 这样令牌响应里才会带回 id_token，从而知道登录的是哪个账号。
     */
    private const val AUTHORIZE_SCOPE = "Files.ReadWrite.AppFolder offline_access openid profile email"

    private const val PREFS = "tessera_onedrive"

    /** 旧版单账号的键（无账号前缀），首次读取时迁移到 `legacy:` 前缀下。 */
    private const val LEGACY_ACCESS = "access_enc"
    private const val LEGACY_REFRESH = "refresh_enc"
    private const val LEGACY_EXPIRES = "expires_at"

    private const val SUFFIX_ACCESS = ":access_enc"
    private const val SUFFIX_REFRESH = ":refresh_enc"
    private const val SUFFIX_EXPIRES = ":expires_at"

    private val cipher = SecretCipher()

    /** 最近一次令牌请求失败的原因（用于界面提示）。 */
    @Volatile private var lastTokenError: String? = null

    /** 最近一次登录解析出的账号 id（邮箱）；未解析出来时为 null。 */
    @Volatile private var lastSignedInAccount: String? = null

    /** 客户端 ID 是否已配置（未配置时不要发起登录）。 */
    fun isConfigured(): Boolean {
        val id = OneDriveProvider.ONEDRIVE_CLIENT_ID
        return id.isNotBlank() && !id.startsWith("REPLACE_WITH")
    }

    /** 已连接的微软账号 id（邮箱）列表；顺序稳定，便于界面展示。 */
    fun accountIds(context: Context): List<String> {
        migrateLegacy(context)
        return prefs(context).all.keys
            .filter { it.endsWith(SUFFIX_REFRESH) }
            .map { it.removeSuffix(SUFFIX_REFRESH) }
            .sorted()
    }

    fun isConnected(context: Context, accountId: String): Boolean =
        prefs(context).contains(accountId + SUFFIX_REFRESH)

    /** 断开某个账号：删掉它的令牌。云端备份文件不动，重新登录即可恢复。 */
    fun signOut(context: Context, accountId: String) {
        prefs(context).edit()
            .remove(accountId + SUFFIX_ACCESS)
            .remove(accountId + SUFFIX_REFRESH)
            .remove(accountId + SUFFIX_EXPIRES)
            .apply()
    }

    private fun configuration() = AuthorizationServiceConfiguration(
        Uri.parse(AUTHORITY + "/authorize"),
        Uri.parse(AUTHORITY + "/token")
    )

    /**
     * 发起登录：打开系统浏览器（CustomTabs），结果通过 completionIntent 交回宿主 Activity。
     * 需要用 FLAG_MUTABLE，因为 AppAuth 要往这个 PendingIntent 里塞响应数据。
     */
    fun signIn(context: Context, completionIntent: Intent, cancelIntent: Intent) {
        val service = AuthorizationService(context)
        val request = AuthorizationRequest.Builder(
            configuration(),
            OneDriveProvider.ONEDRIVE_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI)
        )
            .setScope(AUTHORIZE_SCOPE)
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
            .build()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        service.performAuthorizationRequest(
            request,
            PendingIntent.getActivity(context, 100, completionIntent, flags),
            PendingIntent.getActivity(context, 101, cancelIntent, flags)
        )
    }

    /** 判断某个 Intent 是否是 AppAuth 的回调（用于冷启动场景）。 */
    fun looksLikeAuthResponse(intent: Intent?): Boolean =
        intent?.hasExtra(AuthorizationResponse.EXTRA_RESPONSE) == true

    /**
     * 处理浏览器回跳：换取并保存令牌。成功返回 null，失败返回可展示的中文原因。
     * 保存后可用 [lastSignedInAccount] 取到本次登录解析出的账号 id。
     */
    suspend fun handleRedirect(context: Context, intent: Intent): String? {
        lastSignedInAccount = null
        val exception = AuthorizationException.fromIntent(intent)
        val response = AuthorizationResponse.fromIntent(intent)
        if (response == null) {
            return exception?.errorDescription ?: exception?.error ?: "登录未完成（可能被用户取消）"
        }
        val tokens = requestTokens(context, response.createTokenExchangeRequest())
            ?: return lastTokenError ?: "换取令牌失败；请确认 Azure 里登记的重定向 URI 是 tessera://auth"
        if (tokens.accessToken == null) return "令牌响应中没有 access_token"
        val accountId = accountIdFrom(tokens.idToken) ?: LEGACY_ACCOUNT_ID
        lastSignedInAccount = accountId
        persist(context, accountId, tokens)
        // 旧版那条"无名"记录被这次具名登录取代：否则同一份云端备份会被同步两遍
        if (accountId != LEGACY_ACCOUNT_ID) signOut(context, LEGACY_ACCOUNT_ID)
        return null
    }

    /** 本次登录解析出的账号 id（邮箱）；需在 [handleRedirect] 成功后读取。 */
    fun lastSignedInAccount(): String? = lastSignedInAccount

    /**
     * 取某个账号可用的 access token：未过期直接用缓存，否则用 refresh token 续期。
     * 未登录或续期失败返回 null（调用方据此给出中文提示）。
     */
    suspend fun accessToken(context: Context, accountId: String): String? {
        if (!isConfigured()) return null
        migrateLegacy(context)
        val p = prefs(context)
        val expiresAt = p.getLong(accountId + SUFFIX_EXPIRES, 0L)
        val cached = decrypt(p.getString(accountId + SUFFIX_ACCESS, null))
        // 预留 60 秒余量，避免"刚拿到就过期"
        if (cached != null && expiresAt > System.currentTimeMillis() + 60_000L) return cached
        val refresh = decrypt(p.getString(accountId + SUFFIX_REFRESH, null)) ?: return null
        val request = TokenRequest.Builder(configuration(), OneDriveProvider.ONEDRIVE_CLIENT_ID)
            .setGrantType("refresh_token")
            .setRefreshToken(refresh)
            // 故意不传 scope：不传即"沿用当初授权时的范围"。
            // 老账号当初没授权 openid/email，若这里传了新范围会被判 invalid_grant。
            .build()
        return requestTokens(context, request)?.also { persist(context, accountId, it) }?.accessToken
    }

    /** 用 AppAuth 发起令牌请求（同时用于「授权码换令牌」和「refresh 续期」）。 */
    private suspend fun requestTokens(context: Context, request: TokenRequest): TokenResponse? =
        withContext(Dispatchers.IO) {
            val service = AuthorizationService(context)
            try {
                suspendCancellableCoroutine { continuation ->
                    service.performTokenRequest(request) { response, ex ->
                        if (response != null) {
                            lastTokenError = null
                            continuation.resume(response)
                        } else {
                            lastTokenError = ex?.errorDescription ?: ex?.error ?: ex?.message
                            continuation.resume(null)
                        }
                    }
                }
            } finally {
                service.dispose()
            }
        }

    private fun persist(context: Context, accountId: String, response: TokenResponse) {
        val editor = prefs(context).edit()
        response.accessToken?.let { editor.putString(accountId + SUFFIX_ACCESS, cipher.encrypt(it.toByteArray(Charsets.UTF_8))) }
        response.refreshToken?.let { editor.putString(accountId + SUFFIX_REFRESH, cipher.encrypt(it.toByteArray(Charsets.UTF_8))) }
        response.accessTokenExpirationTime?.let { editor.putLong(accountId + SUFFIX_EXPIRES, it) }
        editor.apply()
    }

    /**
     * 把旧版无账号前缀的令牌迁到 [LEGACY_ACCOUNT_ID] 名下（幂等）。
     * 只搬一次：搬完就删掉旧键，之后调用直接返回。
     */
    private fun migrateLegacy(context: Context) {
        val p = prefs(context)
        if (!p.contains(LEGACY_REFRESH) && !p.contains(LEGACY_ACCESS)) return
        val editor = p.edit()
        p.getString(LEGACY_ACCESS, null)?.let { editor.putString(LEGACY_ACCOUNT_ID + SUFFIX_ACCESS, it) }
        p.getString(LEGACY_REFRESH, null)?.let { editor.putString(LEGACY_ACCOUNT_ID + SUFFIX_REFRESH, it) }
        if (p.contains(LEGACY_EXPIRES)) editor.putLong(LEGACY_ACCOUNT_ID + SUFFIX_EXPIRES, p.getLong(LEGACY_EXPIRES, 0L))
        editor.remove(LEGACY_ACCESS).remove(LEGACY_REFRESH).remove(LEGACY_EXPIRES)
        editor.apply()
    }

    /** 从 id_token 的载荷里取账号标识：优先邮箱，其次主体 id（两者都稳定）。 */
    private fun accountIdFrom(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        val padded = payload.padEnd((payload.length + 3) / 4 * 4, '=')
        val json = runCatching {
            String(Base64.decode(padded, Base64.URL_SAFE), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return listOf("preferred_username", "email", "upn", "sub")
            .firstNotNullOfOrNull { field -> extractField(json, field)?.takeIf { it.isNotBlank() } }
    }

    /** 极简 JSON 取值（与 OneDriveProvider 里同一套做法，避免引入 JSON 解析依赖）。 */
    private fun extractField(json: String, field: String): String? {
        val index = json.indexOf("\"" + field + "\"")
        if (index < 0) return null
        val colon = json.indexOf(':', index)
        val start = json.indexOf('"', colon)
        if (start < 0) return null
        val end = json.indexOf('"', start + 1)
        if (end < 0) return null
        return json.substring(start + 1, end)
    }

    private fun decrypt(encoded: String?): String? = encoded?.let {
        runCatching { String(cipher.decrypt(it), Charsets.UTF_8) }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
