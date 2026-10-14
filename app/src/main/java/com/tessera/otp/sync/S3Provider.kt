package com.tessera.otp.sync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URI
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 对象存储（S3 兼容 API）后端。
 *
 * 只用到 GetObject / PutObject / DeleteObject 三个操作，签名走 AWS Signature V4，
 * 因此 AWS S3、Cloudflare R2、Backblaze B2、MinIO、阿里云 OSS、腾讯云 COS 等
 * 任何实现了 S3 兼容接口的服务都能用（参考 <https://doc.oplist.org/guide/drivers/s3>）。
 *
 * 与 WebDAV 的差异：PutObject 的 `If-Match` 条件写并非所有兼容实现都支持，
 * 贸然带上会被判 412/501，因此这里忽略 [push] 的 expectedEtag，采用后写覆盖先写。
 */
class S3Provider(
    endpoint: String,
    private val region: String = "us-east-1",
    bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    private val sessionToken: String = "",
    prefix: String = "",
    fileName: String = "tessera.vault",
    forcePathStyle: Boolean = false,
    override val displayName: String = "S3"
) : SyncProvider {

    private val target = s3RequestTarget(endpoint, bucket, objectKey(prefix, fileName), forcePathStyle)

    override suspend fun pull(): RemoteFile? {
        val response = Http.execute(request("GET", EMPTY_PAYLOAD))
        response.use {
            if (it.code == 404) return null
            if (!it.isSuccessful) throw SyncException(describe(it), it.code)
            return RemoteFile(content = it.body?.string().orEmpty(), etag = it.header("ETag"))
        }
    }

    override suspend fun push(content: String, expectedEtag: String?): Boolean {
        val response = Http.execute(request("PUT", content.toByteArray(Charsets.UTF_8)))
        response.use {
            if (!it.isSuccessful) throw SyncException(describe(it), it.code)
            return true
        }
    }

    override suspend fun delete(): Boolean {
        val response = Http.execute(request("DELETE", EMPTY_PAYLOAD))
        response.use {
            // DeleteObject 本身幂等（对象不存在也返回 204），个别实现会回 404，同样视为成功
            if (it.code == 404) return true
            if (!it.isSuccessful) throw SyncException(describe(it), it.code)
            return true
        }
    }

    /** 构造并签名一个请求；Host 由 OkHttp 从 URL 推导，必须与签名时用的 Host 完全一致。 */
    private fun request(method: String, payload: ByteArray): Request {
        val amzDate = AwsV4.amzDate()
        val payloadHash = AwsV4.sha256Hex(payload)
        val headers = linkedMapOf(
            "host" to target.host,
            "x-amz-content-sha256" to payloadHash,
            "x-amz-date" to amzDate
        )
        if (sessionToken.isNotBlank()) headers["x-amz-security-token"] = sessionToken
        val authorization = AwsV4.authorization(
            method = method,
            canonicalUri = target.canonicalUri,
            headers = headers,
            region = region.ifBlank { "us-east-1" },
            accessKey = accessKey,
            secretKey = secretKey,
            amzDate = amzDate
        )
        val builder = Request.Builder()
            .url(target.url)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
        if (sessionToken.isNotBlank()) builder.header("x-amz-security-token", sessionToken)
        return when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.put(payload.toRequestBody(OCTET_STREAM))
        }.build()
    }

    /**
     * S3 的 400/403 几乎都是配置问题（区域不对、密钥不对、桶名不对、风格用错），
     * 通用的"请检查令牌作用域"文案在这里会把人带偏。
     */
    private fun describe(response: Response): String = when (response.code) {
        400 -> "请求被拒绝（400）：多为区域或签名不匹配，请检查 Endpoint / 区域 / 密钥是否对应"
        403 -> "无权限（403）：请检查 Access Key 与 Secret Key，以及该密钥是否有此 Bucket 的读写权限"
        else -> Http.describe(response)
    }

    private companion object {
        val EMPTY_PAYLOAD = ByteArray(0)
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

/** 一次 S3 请求的落点：实际 URL、参与签名的规范化路径、以及签名用的 Host 头。 */
internal data class S3RequestTarget(val url: String, val canonicalUri: String, val host: String)

/**
 * 拼出 S3 对象地址。
 *
 * 默认虚拟主机风格 `https://<bucket>.<endpoint-host>/<key>`（AWS / R2 / OSS 等）；
 * MinIO 一类必须走路径风格 `https://<endpoint>/<bucket>/<key>` 时把 [forcePathStyle] 打开。
 */
internal fun s3RequestTarget(
    endpoint: String,
    bucket: String,
    key: String,
    forcePathStyle: Boolean
): S3RequestTarget {
    val trimmed = endpoint.trim().trimEnd('/')
    val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "https://" + trimmed
    }
    val uri = runCatching { URI(normalized) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
        ?: throw SyncException("S3 地址无法解析（应形如 https://s3.us-east-1.amazonaws.com）：" + endpoint)
    val host = uri.host?.takeIf { it.isNotBlank() }
        ?: throw SyncException("S3 地址缺少主机名：" + endpoint)
    // 签名里的 Host 必须和 OkHttp 实际发出的一致：默认端口不能带上
    val port = uri.port
    val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
    val authority = if (port <= 0 || defaultPort) host else host + ":" + port

    val encodedKey = encodeS3Path(key)
    val encodedBucket = uriEncode(bucket)
    return if (forcePathStyle) {
        S3RequestTarget(
            url = scheme + "://" + authority + "/" + encodedBucket + "/" + encodedKey,
            canonicalUri = "/" + encodedBucket + "/" + encodedKey,
            host = authority
        )
    } else {
        S3RequestTarget(
            url = scheme + "://" + encodedBucket + "." + authority + "/" + encodedKey,
            canonicalUri = "/" + encodedKey,
            host = encodedBucket + "." + authority
        )
    }
}

/** 云端对象键：可选前缀目录 + 文件名。 */
internal fun objectKey(prefix: String, fileName: String): String {
    val dir = prefix.trim().trim('/')
    val name = fileName.trim().ifBlank { "tessera.vault" }
    return if (dir.isBlank()) name else dir + "/" + name
}

/** 逐段 URI 编码对象键，分隔用的 `/` 保留。 */
private fun encodeS3Path(key: String): String =
    key.split('/').filter { it.isNotEmpty() }.joinToString("/") { uriEncode(it) }

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
private const val HEX = "0123456789ABCDEF"

/** RFC 3986 百分号编码：除 `A-Za-z0-9-_.~` 外一律编码，`/` 一律编码（调用方按需保留）。 */
internal fun uriEncode(value: String): String {
    val sb = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val ch = code.toChar()
        if (ch in UNRESERVED) {
            sb.append(ch)
        } else {
            sb.append('%').append(HEX[code ushr 4]).append(HEX[code and 0xF])
        }
    }
    return sb.toString()
}

/**
 * AWS Signature V4（仅 S3 用到的部分：无查询串、固定服务名 s3）。
 *
 * 纯计算、不依赖 Android，方便单测按已知向量核对签名。
 */
internal object AwsV4 {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    /** 签名与载荷散列一律小写十六进制（AWS 规范如此，服务端比对区分大小写）。 */
    private const val HEX_LOWER = "0123456789abcdef"
    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** `20130524T000000Z` 形式的 UTC 时间戳。 */
    fun amzDate(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): String =
        TIMESTAMP.format(now.withZoneSameInstant(ZoneOffset.UTC))

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * 计算 Authorization 头。
     *
     * [headers] 必须包含 `host`、`x-amz-content-sha256`、`x-amz-date`（键一律小写），
     * 会话令牌等附加头一并参与签名。
     */
    fun authorization(
        method: String,
        canonicalUri: String,
        headers: Map<String, String>,
        region: String,
        accessKey: String,
        secretKey: String,
        amzDate: String,
        canonicalQuery: String = ""
    ): String {
        val signedNames = headers.keys.map { it.lowercase() }.sorted()
        val canonicalHeaders = signedNames.joinToString("") { name ->
            name + ":" + headers.getValue(name).trim() + "\n"
        }
        val signedHeaders = signedNames.joinToString(";")
        val payloadHash = headers.getValue("x-amz-content-sha256")
        val canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n" +
            canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash
        val scope = amzDate.substring(0, 8) + "/" + region + "/" + SERVICE + "/aws4_request"
        val stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" +
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        val signature = hex(hmac(signingKey(secretKey, amzDate.substring(0, 8), region), stringToSign.toByteArray(Charsets.UTF_8)))
        return ALGORITHM + " Credential=" + accessKey + "/" + scope +
            ", SignedHeaders=" + signedHeaders + ", Signature=" + signature
    }

    /** 派生签名密钥：HMAC 逐层套日期 / 区域 / 服务 / 固定后缀。 */
    private fun signingKey(secretKey: String, date: String, region: String): ByteArray {
        val dateKey = hmac(("AWS4" + secretKey).toByteArray(Charsets.UTF_8), date.toByteArray(Charsets.UTF_8))
        val regionKey = hmac(dateKey, region.toByteArray(Charsets.UTF_8))
        val serviceKey = hmac(regionKey, SERVICE.toByteArray(Charsets.UTF_8))
        return hmac(serviceKey, "aws4_request".toByteArray(Charsets.UTF_8))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            sb.append(HEX_LOWER[code ushr 4]).append(HEX_LOWER[code and 0xF])
        }
        return sb.toString()
    }
}
