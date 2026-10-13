package com.tessera.otp.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tessera.otp.data.keystore.SecretCipher
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
 * OneDrive（微软账号）登录：OAuth 2.0 授权码 + PKCE。
 *
 * 为什么用 PKCE 而不是 client secret：Android 应用属于 OAuth 2.0 "公共客户端"，
 * 任何打包进 APK 的 secret 都等于公开；PKCE 由 AppAuth 生成 code_verifier/challenge 完成校验。
 *
 * 令牌存储：access/refresh token 都用 [SecretCipher]（Android Keystore 主密钥）加密后落盘，
 * 明文令牌不写入磁盘。access token 约 1 小时过期，这里用 refresh token 自动续期。
 *
 * 重定向：redirect URI 为 `tessera://auth`，需在 Azure 应用注册里登记成同一个值；
 * AppAuth 自带的 RedirectUriReceiverActivity 会接住重定向（scheme 由 Gradle 的
 * manifestPlaceholders["appAuthRedirectScheme"] 提供）。
 */
object OneDriveAuth {

    private const val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0"
    private const val REDIRECT_URI = "tessera://auth"
    private const val SCOPE = "Files.ReadWrite.AppFolder offline_access"

    private const val PREFS = "tessera_onedrive"
    private const val KEY_ACCESS = "access_enc"
    private const val KEY_REFRESH = "refresh_enc"
    private const val KEY_EXPIRES = "expires_at"

    private val cipher = SecretCipher()

    /** 最近一次令牌请求失败的原因（用于界面提示）。 */
    @Volatile private var lastTokenError: String? = null

    /** 客户端 ID 是否已配置（未配置时不要发起登录）。 */
    fun isConfigured(): Boolean {
        val id = OneDriveProvider.ONEDRIVE_CLIENT_ID
        return id.isNotBlank() && !id.startsWith("REPLACE_WITH")
    }

    fun isConnected(context: Context): Boolean = prefs(context).contains(KEY_REFRESH)

    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
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
            .setScope(SCOPE)
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

    /** 处理浏览器回跳：换取并保存令牌。成功返回 null，失败返回可展示的中文原因。 */
    suspend fun handleRedirect(context: Context, intent: Intent): String? {
        val exception = AuthorizationException.fromIntent(intent)
        val response = AuthorizationResponse.fromIntent(intent)
        if (response == null) {
            return exception?.errorDescription ?: exception?.error ?: "登录未完成（可能被用户取消）"
        }
        val tokens = requestTokens(context, response.createTokenExchangeRequest())
            ?: return lastTokenError ?: "换取令牌失败；请确认 Azure 里登记的重定向 URI 是 tessera://auth"
        return if (tokens.accessToken != null) null else "令牌响应中没有 access_token"
    }

    /**
     * 取一个可用的 access token：未过期直接用缓存，否则用 refresh token 续期。
     * 未登录或续期失败返回 null（调用方据此给出中文提示）。
     */
    suspend fun accessToken(context: Context): String? {
        if (!isConfigured()) return null
        val p = prefs(context)
        val expiresAt = p.getLong(KEY_EXPIRES, 0L)
        val cached = decrypt(p.getString(KEY_ACCESS, null))
        // 预留 60 秒余量，避免"刚拿到就过期"
        if (cached != null && expiresAt > System.currentTimeMillis() + 60_000L) return cached
        val refresh = decrypt(p.getString(KEY_REFRESH, null)) ?: return null
        val request = TokenRequest.Builder(configuration(), OneDriveProvider.ONEDRIVE_CLIENT_ID)
            .setGrantType("refresh_token")
            .setRefreshToken(refresh)
            .setScope(SCOPE)
            .build()
        return requestTokens(context, request)?.accessToken
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
                            persist(context, response)
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

    private fun persist(context: Context, response: TokenResponse) {
        val editor = prefs(context).edit()
        response.accessToken?.let { editor.putString(KEY_ACCESS, cipher.encrypt(it.toByteArray(Charsets.UTF_8))) }
        response.refreshToken?.let { editor.putString(KEY_REFRESH, cipher.encrypt(it.toByteArray(Charsets.UTF_8))) }
        response.accessTokenExpirationTime?.let { editor.putLong(KEY_EXPIRES, it) }
        editor.apply()
    }

    private fun decrypt(encoded: String?): String? = encoded?.let {
        runCatching { String(cipher.decrypt(it), Charsets.UTF_8) }.getOrNull()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
