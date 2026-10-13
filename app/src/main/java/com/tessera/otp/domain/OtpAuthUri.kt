package com.tessera.otp.domain

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.Base64

/**
 * 一次 otpauth URI 解析的结果。
 *
 * @property issuer 发行方；URI 中确实没有发行方信息时为 null。
 * @property accountName 账号名（已去掉 label 中的发行方前缀）。
 * @property secretBase32 Base32 文本形式的密钥，保持原文大小写。
 * @property algorithm 哈希算法，缺省 SHA1。
 * @property digits 验证码位数，缺省 6；Steam 固定规整为 5。
 * @property period 时间窗秒数，缺省 30。
 * @property isSteam 是否为 Steam Guard 令牌。
 * @property type 令牌类型，取值为 totp 或 hotp。
 */
data class OtpAuthData(
    val issuer: String?,
    val accountName: String,
    val secretBase32: String,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val period: Int,
    val isSteam: Boolean,
    val type: String,
)

/**
 * Google Authenticator 迁移包（otpauth-migration）中的一条令牌记录。
 *
 * @property issuer 发行方，可能为空字符串。
 * @property accountName 账号名，对应 protobuf 的 name 字段。
 * @property secretBase32 Base32 文本形式的密钥。
 * @property algorithm 哈希算法。
 * @property digits 验证码位数。
 * @property type 令牌类型，取值为 totp 或 hotp。
 */
data class MigrationEntry(
    val issuer: String,
    val accountName: String,
    val secretBase32: String,
    val algorithm: OtpAlgorithm,
    val digits: Int,
    val type: String,
)

/**
 * otpauth URI（Key Uri Format）解析器。
 *
 * 只用 java.net.URI 做结构解析，query 部分手工拆分，因此不依赖 android.net.Uri，
 * 可以直接在 JVM 单元测试里运行。
 *
 * 容错策略：
 * - scheme 大小写不敏感；
 * - 当 java.net.URI 拒绝解析（例如 label 里出现未编码的空格）时退回手工拆分；
 * - 缺少 algorithm / digits / period 参数时按 SHA1 / 6 / 30 处理；
 * - query 中没有 issuer 时，从 label 的 发行方:账号 形式中切分；
 * - issuer 或 label 中出现 steam（大小写不敏感）时按 Steam 处理，位数规整为 5。
 */
object OtpAuthUriParser {

    private const val SCHEME = "otpauth"
    private const val TYPE_TOTP = "totp"
    private const val TYPE_HOTP = "hotp"
    private const val DEFAULT_DIGITS = 6
    private const val DEFAULT_PERIOD = 30
    private const val STEAM_DIGITS = 5
    private const val STEAM_KEYWORD = "steam"

    /**
     * 解析 otpauth URI。
     *
     * @return 解析结果；不是 otpauth scheme、type 不是 totp/hotp、或缺少 secret 参数时返回 null。
     */
    fun parse(uri: String): OtpAuthData? {
        val raw = uri.trim()
        val schemeEnd = raw.indexOf("://")
        if (schemeEnd <= 0) return null
        if (!raw.substring(0, schemeEnd).equals(SCHEME, ignoreCase = true)) return null

        val parsed: URI? = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            null
        }

        val type: String
        val rawPath: String
        val rawQuery: String
        // 注意：java.net.URI 的 host 是 Java getter，Kotlin 无法对它做 smart cast，必须先取到局部变量。
        val parsedHost = parsed?.host
        if (parsed != null && parsedHost != null) {
            type = parsedHost
            rawPath = parsed.rawPath.orEmpty()
            rawQuery = parsed.rawQuery.orEmpty()
        } else {
            val body = raw.substring(schemeEnd + 3).substringBefore('#')
            val queryStart = body.indexOf('?')
            val pathPart = if (queryStart < 0) body else body.substring(0, queryStart)
            rawQuery = if (queryStart < 0) "" else body.substring(queryStart + 1)
            val slash = pathPart.indexOf('/')
            type = if (slash < 0) pathPart else pathPart.substring(0, slash)
            rawPath = if (slash < 0) "" else pathPart.substring(slash)
        }

        val normalizedType = type.trim().lowercase()
        if (normalizedType != TYPE_TOTP && normalizedType != TYPE_HOTP) return null

        val label = percentDecode(rawPath.trimStart('/'), plusAsSpace = false).trim()
        val params = parseQuery(rawQuery)

        val secret = params["secret"]?.trim().orEmpty()
        if (secret.isEmpty()) return null

        var issuer = params["issuer"]?.trim()?.takeIf { it.isNotEmpty() }
        var accountName = label
        val separator = label.indexOf(':')
        if (separator > 0) {
            val labelIssuer = label.substring(0, separator).trim()
            val labelAccount = label.substring(separator + 1).trim()
            if (issuer == null) {
                if (labelIssuer.isNotEmpty()) issuer = labelIssuer
                accountName = labelAccount
            } else if (labelIssuer.equals(issuer, ignoreCase = true)) {
                accountName = labelAccount
            }
        }

        val isSteam = label.contains(STEAM_KEYWORD, ignoreCase = true) ||
            issuer?.contains(STEAM_KEYWORD, ignoreCase = true) == true

        val digits = if (isSteam) {
            STEAM_DIGITS
        } else {
            params["digits"]?.trim()?.toIntOrNull()?.takeIf { it in 1..10 } ?: DEFAULT_DIGITS
        }
        val period = params["period"]?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_PERIOD

        return OtpAuthData(
            issuer = issuer,
            accountName = accountName,
            secretBase32 = secret,
            algorithm = parseAlgorithm(params["algorithm"]),
            digits = digits,
            period = period,
            isSteam = isSteam,
            type = normalizedType,
        )
    }

    private fun parseAlgorithm(raw: String?): OtpAlgorithm = when (raw?.trim()?.uppercase()) {
        "SHA1", "SHA-1" -> OtpAlgorithm.SHA1
        "SHA256", "SHA-256" -> OtpAlgorithm.SHA256
        "SHA512", "SHA-512" -> OtpAlgorithm.SHA512
        else -> OtpAlgorithm.SHA1
    }

    /** 手工解析 query 字符串；键名统一小写，重复键以第一次出现的为准。 */
    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val separator = pair.indexOf('=')
            val rawKey = if (separator < 0) pair else pair.substring(0, separator)
            val rawValue = if (separator < 0) "" else pair.substring(separator + 1)
            val key = percentDecode(rawKey, plusAsSpace = true).trim().lowercase()
            if (key.isEmpty() || result.containsKey(key)) continue
            result[key] = percentDecode(rawValue, plusAsSpace = true)
        }
        return result
    }

    /**
     * 手工百分号解码。
     *
     * @param plusAsSpace 是否把加号当作空格（application/x-www-form-urlencoded，仅用于 query）。
     */
    private fun percentDecode(input: String, plusAsSpace: Boolean): String {
        val result = StringBuilder(input.length)
        val pending = ByteArrayOutputStream()
        fun flushPending() {
            if (pending.size() > 0) {
                result.append(String(pending.toByteArray(), Charsets.UTF_8))
                pending.reset()
            }
        }
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            if (ch == '%' && index + 2 < input.length) {
                val high = Character.digit(input[index + 1], 16)
                val low = Character.digit(input[index + 2], 16)
                if (high >= 0 && low >= 0) {
                    pending.write((high shl 4) or low)
                    index += 3
                    continue
                }
            }
            flushPending()
            if (plusAsSpace && ch == '+') {
                result.append(' ')
            } else {
                result.append(ch)
            }
            index++
        }
        flushPending()
        return result.toString()
    }
}

/**
 * Google Authenticator 迁移包解析器（otpauth-migration-offline.proto 的 wire format）。
 *
 * 手工实现 protobuf 的 varint 与长度前缀解析，不引入任何 protobuf 依赖；未知字段按 wire type 跳过。
 *
 * 字段定义：
 * - 外层 message：字段 1 = repeated OtpMigrationPayload；
 * - payload：1 = secret(bytes)、2 = name(string)、3 = issuer(string)、
 *   4 = algorithm(0 未指定 / 1 SHA1 / 2 SHA256 / 3 SHA512 / 4 MD5)、
 *   5 = digits(0 未指定 / 1 六位 / 2 八位)、6 = type(0 未指定 / 1 hotp / 2 totp)。
 *
 * 说明：OtpAlgorithm 不提供 MD5，因此 algorithm = 4 会降级为 SHA1。
 */
object GoogleMigrationParser {

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED_64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_FIXED_32 = 5

    private const val FIELD_OTP_PARAMETERS = 1

    private const val PAYLOAD_SECRET = 1
    private const val PAYLOAD_NAME = 2
    private const val PAYLOAD_ISSUER = 3
    private const val PAYLOAD_ALGORITHM = 4
    private const val PAYLOAD_DIGITS = 5
    private const val PAYLOAD_TYPE = 6

    private const val DEFAULT_DIGITS = 6
    private const val TYPE_HOTP = "hotp"
    private const val TYPE_TOTP = "totp"

    /**
     * 解析迁移包。
     *
     * @param dataParam otpauth-migration URI 中 data 参数的值（Base64，允许 URL-safe、缺少填充或夹杂换行）。
     * @return 解析出的令牌列表；没有任何 payload 时返回空列表。
     * @throws IllegalArgumentException Base64 非法或 protobuf 结构损坏。
     */
    fun parse(dataParam: String): List<MigrationEntry> {
        val bytes = decodeBase64(dataParam)
        val reader = Reader(bytes)
        val entries = mutableListOf<MigrationEntry>()
        while (!reader.isAtEnd) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            when (val wireType = (tag and 0x07L).toInt()) {
                WIRE_VARINT -> reader.readVarint()
                WIRE_FIXED_64 -> reader.skip(8)
                WIRE_LENGTH_DELIMITED -> {
                    val length = reader.readVarint().toLength()
                    val chunk = reader.readBytes(length)
                    if (fieldNumber == FIELD_OTP_PARAMETERS) {
                        entries += parsePayload(chunk)
                    }
                }
                WIRE_FIXED_32 -> reader.skip(4)
                else -> throw IllegalArgumentException("不支持的 protobuf wire type: " + wireType)
            }
        }
        return entries
    }

    private fun parsePayload(bytes: ByteArray): MigrationEntry {
        val reader = Reader(bytes)
        var secret = ByteArray(0)
        var name = ""
        var issuer = ""
        var algorithm = OtpAlgorithm.SHA1
        var digits = DEFAULT_DIGITS
        var type = TYPE_TOTP

        while (!reader.isAtEnd) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            when (val wireType = (tag and 0x07L).toInt()) {
                WIRE_VARINT -> {
                    val value = reader.readVarint().toInt()
                    when (fieldNumber) {
                        PAYLOAD_ALGORITHM -> algorithm = algorithmOf(value)
                        PAYLOAD_DIGITS -> digits = digitsOf(value)
                        PAYLOAD_TYPE -> type = typeOf(value)
                        else -> Unit
                    }
                }
                WIRE_LENGTH_DELIMITED -> {
                    val length = reader.readVarint().toLength()
                    val chunk = reader.readBytes(length)
                    when (fieldNumber) {
                        PAYLOAD_SECRET -> secret = chunk
                        PAYLOAD_NAME -> name = String(chunk, Charsets.UTF_8)
                        PAYLOAD_ISSUER -> issuer = String(chunk, Charsets.UTF_8)
                        else -> Unit
                    }
                }
                WIRE_FIXED_64 -> reader.skip(8)
                WIRE_FIXED_32 -> reader.skip(4)
                else -> throw IllegalArgumentException("不支持的 protobuf wire type: " + wireType)
            }
        }

        return MigrationEntry(
            issuer = issuer,
            accountName = name,
            secretBase32 = Base32.encode(secret),
            algorithm = algorithm,
            digits = digits,
            type = type,
        )
    }

    private fun algorithmOf(value: Int): OtpAlgorithm = when (value) {
        2 -> OtpAlgorithm.SHA256
        3 -> OtpAlgorithm.SHA512
        else -> OtpAlgorithm.SHA1
    }

    private fun digitsOf(value: Int): Int = when (value) {
        2 -> 8
        else -> DEFAULT_DIGITS
    }

    private fun typeOf(value: Int): String = when (value) {
        1 -> TYPE_HOTP
        else -> TYPE_TOTP
    }

    /**
     * Base64 解码：先补齐填充，再依次尝试 URL-safe 与标准字母表。
     *
     * @throws IllegalArgumentException 数据为空或所有解码尝试都失败。
     */
    private fun decodeBase64(dataParam: String): ByteArray {
        val candidates = listOf(
            dataParam.filterNot { it.isWhitespace() },
            dataParam.filter { it == ' ' || !it.isWhitespace() }.replace(' ', '+'),
        ).filter { it.isNotEmpty() }.distinct()
        if (candidates.isEmpty()) {
            throw IllegalArgumentException("迁移数据为空")
        }

        var lastFailure: IllegalArgumentException? = null
        for (candidate in candidates) {
            val padded = candidate + "=".repeat((4 - candidate.length % 4) % 4)
            for (decoder in listOf(Base64.getUrlDecoder(), Base64.getDecoder())) {
                try {
                    return decoder.decode(padded)
                } catch (e: IllegalArgumentException) {
                    lastFailure = e
                }
            }
        }
        throw IllegalArgumentException("非法的 Base64 迁移数据", lastFailure)
    }

    private fun Long.toLength(): Int {
        if (this < 0 || this > Int.MAX_VALUE) {
            throw IllegalArgumentException("protobuf 长度字段非法: " + this)
        }
        return toInt()
    }

    /** 顺序读取字节数组的极简游标。 */
    private class Reader(private val bytes: ByteArray, var index: Int = 0) {

        val isAtEnd: Boolean
            get() = index >= bytes.size

        /** 读取一个 varint（最多 10 字节）。 */
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (index < bytes.size) {
                val byte = bytes[index].toInt() and 0xFF
                index++
                result = result or ((byte and 0x7F).toLong() shl shift)
                if ((byte and 0x80) == 0) return result
                shift += 7
                if (shift >= 64) throw IllegalArgumentException("protobuf varint 过长")
            }
            throw IllegalArgumentException("protobuf varint 被截断")
        }

        /** 读取 length 个字节。 */
        fun readBytes(length: Int): ByteArray {
            if (length < 0 || index + length > bytes.size) {
                throw IllegalArgumentException("protobuf 数据被截断")
            }
            val chunk = bytes.copyOfRange(index, index + length)
            index += length
            return chunk
        }

        /** 跳过 count 个字节（用于 fixed32 / fixed64 等未知字段）。 */
        fun skip(count: Int) {
            if (count < 0 || index + count > bytes.size) {
                throw IllegalArgumentException("protobuf 数据被截断")
            }
            index += count
        }
    }
}
