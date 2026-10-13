package com.tessera.otp.domain

import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OTP 使用的 HMAC 哈希算法。
 *
 * @property jcaName 对应的 JCA（Java Cryptography Architecture）算法名，用于构造 [javax.crypto.Mac]。
 */
enum class OtpAlgorithm(val jcaName: String) {
    /** HMAC-SHA1，兼容性最好，也是绝大多数 otpauth URI 的缺省值。 */
    SHA1("HmacSHA1"),

    /** HMAC-SHA256。 */
    SHA256("HmacSHA256"),

    /** HMAC-SHA512。 */
    SHA512("HmacSHA512"),
}

/**
 * 一次 OTP 计算所需的全部参数。
 *
 * 因为 [secret] 是 [ByteArray]（引用相等），本类显式重写了 [equals] / [hashCode]（按字节内容比较），
 * 以及 [toString]（不输出密钥内容）。
 *
 * 注意：[secret] 不做防御性拷贝，调用方在传入后不应再修改该数组。
 */
data class OtpSpec(
    val secret: ByteArray,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val isSteam: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OtpSpec) return false
        return secret.contentEquals(other.secret) &&
            algorithm == other.algorithm &&
            digits == other.digits &&
            period == other.period &&
            isSteam == other.isSteam
    }

    override fun hashCode(): Int {
        var result = secret.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + digits
        result = 31 * result + period
        result = 31 * result + isSteam.hashCode()
        return result
    }

    /** 覆盖 data class 自动生成的实现，避免密钥以明文出现在日志里。 */
    override fun toString(): String =
        "OtpSpec(secret=***, algorithm=" + algorithm +
            ", digits=" + digits + ", period=" + period + ", isSteam=" + isSteam + ")"
}

/**
 * OTP 密钥（原始字节）的轻量包装。
 *
 * 说明：本类刻意不使用 value class —— 实测 Kotlin 2.3.21 对 `@JvmInline value class` 中
 * 重写 equals/hashCode 直接报错（Member name equals is reserved for future releases），
 * 而按字节内容比较是本类必须的语义，故保留普通类。
 *
 * 注意：[bytes] 不做防御性拷贝。
 */
class Secret(val bytes: ByteArray) {

    override fun equals(other: Any?): Boolean {
        if (other !is Secret) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "Secret(***)"

    companion object {
        /** 由 Base32 文本（otpauth URI 的 secret 参数）构造。 */
        fun fromBase32(encoded: String): Secret = Secret(Base32.decode(encoded))
    }
}

/**
 * RFC 4648 Base32 编解码。
 *
 * 解码时忽略大小写、空白字符、连字符与等于号填充，遇到字母表之外的字符抛
 * [IllegalArgumentException]；编码输出大写且不带等于号填充，与 otpauth 生态的惯例一致。
 */
object Base32 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * 将 Base32 文本解码为字节数组。
     *
     * @throws IllegalArgumentException 文本中包含 Base32 字母表之外的字符。
     */
    fun decode(input: String): ByteArray {
        val output = ByteArrayOutputStream(input.length * 5 / 8 + 1)
        var buffer = 0
        var bitsLeft = 0
        for (raw in input) {
            if (raw.isWhitespace() || raw == '-' || raw == '=') continue
            val ch = raw.uppercaseChar()
            val value = ALPHABET.indexOf(ch)
            if (value < 0) {
                throw IllegalArgumentException("非法 Base32 字符: " + raw)
            }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return output.toByteArray()
    }

    /** 将字节数组编码为 Base32 文本（大写、不带等于号填充）。 */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val builder = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                builder.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            builder.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return builder.toString()
    }
}

/**
 * HOTP（RFC 4226）/ TOTP（RFC 6238）验证码生成器，以及 Steam Guard 变体。
 *
 * 纯 Kotlin 加 javax.crypto，不依赖任何 Android API，可直接在 JVM 单元测试中运行。
 */
object TotpEngine {

    /** Steam Guard 使用的自定义字母表（26 个字符）。 */
    private const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"

    /** Steam 令牌固定 5 位。 */
    private const val STEAM_DIGITS = 5

    /** 10 的 0 次方到 10 的 10 次方，用于按位数取模。 */
    private val POWERS_OF_TEN = longArrayOf(
        1L,
        10L,
        100L,
        1000L,
        10_000L,
        100_000L,
        1_000_000L,
        10_000_000L,
        100_000_000L,
        1_000_000_000L,
        10_000_000_000L,
    )

    /**
     * 生成 [epochSeconds] 时刻的验证码。
     *
     * 计数器为 epochSeconds 整除 period（8 字节大端），哈希后按 RFC 4226 第 5.3 节动态截断，
     * 取 [OtpSpec.digits] 位并左补零；[OtpSpec.isSteam] 为 true 时改用 Steam 的 5 位字母表编码。
     *
     * @throws IllegalArgumentException period 非正、epochSeconds 为负或 digits 不在 1..10。
     */
    fun generate(spec: OtpSpec, epochSeconds: Long): String {
        require(spec.period > 0) { "period 必须为正数，实际为 " + spec.period }
        require(epochSeconds >= 0) { "epochSeconds 不能为负数，实际为 " + epochSeconds }
        if (!spec.isSteam) {
            require(spec.digits in 1..10) { "digits 必须在 1..10 之间，实际为 " + spec.digits }
        }

        val counter = epochSeconds / spec.period
        val hash = hmac(spec, counter)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        return if (spec.isSteam) {
            steamCode(binary)
        } else {
            decimalCode(binary, spec.digits)
        }
    }

    /**
     * 返回当前时间窗剩余的秒数，取值 1 到 [period]。
     *
     * 当 [epochSeconds] 恰好整除 [period]（刚进入新窗口）时返回 [period]。
     *
     * @throws IllegalArgumentException period 非正或 epochSeconds 为负。
     */
    fun secondsRemaining(period: Int, epochSeconds: Long): Int {
        require(period > 0) { "period 必须为正数，实际为 " + period }
        require(epochSeconds >= 0) { "epochSeconds 不能为负数，实际为 " + epochSeconds }
        return (period - (epochSeconds % period)).toInt()
    }

    /**
     * 生成下一个时间窗的验证码（从 epochSeconds 之后的下一个窗口起点开始计算）。
     *
     * @throws IllegalArgumentException period 非正。
     */
    fun nextToken(spec: OtpSpec, epochSeconds: Long): String {
        require(spec.period > 0) { "period 必须为正数，实际为 " + spec.period }
        val nextWindowStart = ((epochSeconds / spec.period) + 1) * spec.period
        return generate(spec, nextWindowStart)
    }

    private fun hmac(spec: OtpSpec, counter: Long): ByteArray {
        val mac = Mac.getInstance(spec.algorithm.jcaName)
        mac.init(SecretKeySpec(spec.secret, spec.algorithm.jcaName))
        mac.update(counter.toBigEndianBytes())
        return mac.doFinal()
    }

    private fun decimalCode(binary: Int, digits: Int): String {
        val code = binary.toLong() % POWERS_OF_TEN[digits]
        return code.toString().padStart(digits, '0')
    }

    private fun steamCode(binary: Int): String {
        var value = binary
        val builder = StringBuilder(STEAM_DIGITS)
        repeat(STEAM_DIGITS) {
            builder.append(STEAM_ALPHABET[value % STEAM_ALPHABET.length])
            value /= STEAM_ALPHABET.length
        }
        return builder.toString()
    }

    /** 将计数器写成 8 字节大端序。 */
    private fun Long.toBigEndianBytes(): ByteArray {
        val bytes = ByteArray(8)
        var value = this
        var index = 7
        while (index >= 0) {
            bytes[index] = (value and 0xFF).toByte()
            value = value ushr 8
            index--
        }
        return bytes
    }
}

/**
 * 验证码的展示格式化工具。
 */
object CodeFormatter {

    /**
     * 把验证码从中间分成两组并用空格连接，便于阅读。
     *
     * 123456 变成 123 456；12345 变成 123 45；12345678 变成 1234 5678；
     * 长度不超过 3 时原样返回。输入中的空白字符会被忽略。
     */
    fun grouped(code: String): String {
        val digits = code.filterNot { it.isWhitespace() }
        if (digits.length <= 3) return digits
        val middle = (digits.length + 1) / 2
        return digits.substring(0, middle) + " " + digits.substring(middle)
    }
}
