package com.tessera.otp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [TotpEngine]、[Base32]、[CodeFormatter]、[Secret] 与 [OtpSpec] 的 JVM 单元测试。
 *
 * RFC 6238 附录 B 的官方向量已用独立实现（Node.js crypto 的 HMAC 与动态截断）交叉复算过，
 * 全部逐位一致。
 */
class TotpEngineTest {

    /** RFC 6238 附录 B 使用的 6 个时间点（TOTP，period = 30）。 */
    private val rfcTimes = longArrayOf(
        59L,
        1111111109L,
        1111111111L,
        1234567890L,
        2000000000L,
        20000000000L,
    )

    @Test
    fun rfc6238Sha1Vectors() {
        assertRfcVectors(
            algorithm = OtpAlgorithm.SHA1,
            secretText = "12345678901234567890",
            expected = longArrayOf(94287082L, 7081804L, 14050471L, 89005924L, 69279037L, 65353130L),
        )
    }

    @Test
    fun rfc6238Sha256Vectors() {
        assertRfcVectors(
            algorithm = OtpAlgorithm.SHA256,
            secretText = "12345678901234567890123456789012",
            expected = longArrayOf(46119246L, 68084774L, 67062674L, 91819424L, 90698825L, 77737706L),
        )
    }

    @Test
    fun rfc6238Sha512Vectors() {
        assertRfcVectors(
            algorithm = OtpAlgorithm.SHA512,
            secretText = "1234567890123456789012345678901234567890123456789012345678901234",
            expected = longArrayOf(90693936L, 25091201L, 99943326L, 93441116L, 38618901L, 47863826L),
        )
    }

    /**
     * Steam 变体：断言输出为 5 位且字符全部落在 Steam 字母表内。
     *
     * 参考值仅作交叉验证记录，不作为断言（避免把实现细节写死）：
     * 用 Node.js crypto 独立复算 epoch = 1700000000 得到 R87JJ。
     */
    @Test
    fun steamTokenUsesSteamAlphabet() {
        val spec = OtpSpec(
            secret = "12345678901234567890".toByteArray(Charsets.US_ASCII),
            algorithm = OtpAlgorithm.SHA1,
            digits = 5,
            period = 30,
            isSteam = true,
        )
        val token = TotpEngine.generate(spec, 1_700_000_000L)
        assertEquals(5, token.length)

        val alphabet = "23456789BCDFGHJKMNPQRTVWXY"
        assertTrue(
            "Steam 令牌字符必须来自 Steam 字母表，实际为 " + token,
            token.all { alphabet.contains(it) },
        )
    }

    @Test
    fun base32DecodesKnownSecret() {
        // JBSWY3DPEHPK3PXP 是 otpauth 生态里最常用的示例密钥，解码后为 ASCII 的 Hello! 加 DE AD BE EF。
        assertEquals("48656C6C6F21DEADBEEF", Base32.decode("JBSWY3DPEHPK3PXP").toHex())
    }

    @Test
    fun base32RoundTripsAndIgnoresFormatting() {
        val bytes = Base32.decode("JBSWY3DPEHPK3PXP")
        assertEquals(10, bytes.size)
        assertEquals("JBSWY3DPEHPK3PXP", Base32.encode(bytes))

        // 小写、空格、连字符与等于号填充都应被忽略。
        assertEquals("48656C6C6F21DEADBEEF", Base32.decode("jbswy3dp ehpk-3pxp").toHex())
        assertEquals("48656C6C6F21DEADBEEF", Base32.decode("JBSWY3DP-EHPK3PXP=").toHex())

        // 编码不带填充：foobar 的标准 Base32 为 MZXW6YTBOI 而不是 MZXW6YTBOI====。
        assertEquals("MZXW6YTBOI", Base32.encode("foobar".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun base32RejectsIllegalCharacter() {
        assertThrowsIllegalArgument { Base32.decode("JBSWY3DP!HPK3PXP") }
        assertThrowsIllegalArgument { Base32.decode("JBSWY3DP0HPK3PXP") }
    }

    @Test
    fun secondsRemainingWrapsWithinPeriod() {
        assertEquals(1, TotpEngine.secondsRemaining(30, 59L))
        assertEquals(30, TotpEngine.secondsRemaining(30, 60L))
        assertEquals(30, TotpEngine.secondsRemaining(30, 0L))
        assertEquals(15, TotpEngine.secondsRemaining(30, 15L))
        assertEquals(1, TotpEngine.secondsRemaining(1, 7L))
    }

    @Test
    fun nextTokenUsesNextWindow() {
        val spec = OtpSpec(
            secret = "12345678901234567890".toByteArray(Charsets.US_ASCII),
            algorithm = OtpAlgorithm.SHA1,
            digits = 8,
            period = 30,
        )
        assertEquals(TotpEngine.generate(spec, 60L), TotpEngine.nextToken(spec, 59L))
        assertEquals(TotpEngine.generate(spec, 60L), TotpEngine.nextToken(spec, 30L))
        assertEquals(TotpEngine.generate(spec, 90L), TotpEngine.nextToken(spec, 60L))
    }

    @Test
    fun codeFormatterGroupsCodes() {
        assertEquals("123 456", CodeFormatter.grouped("123456"))
        assertEquals("123 45", CodeFormatter.grouped("12345"))
        assertEquals("1234 5678", CodeFormatter.grouped("12345678"))
        assertEquals("123", CodeFormatter.grouped("123"))
        assertEquals("123 456", CodeFormatter.grouped(" 123456 "))
    }

    @Test
    fun secretComparesByContentAndHidesValue() {
        val first = Secret("1234".toByteArray(Charsets.US_ASCII))
        val second = Secret("1234".toByteArray(Charsets.US_ASCII))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, Secret("1235".toByteArray(Charsets.US_ASCII)))

        assertEquals("Secret(***)", first.toString())
        assertFalse(first.toString().contains("1234"))
    }

    @Test
    fun otpSpecComparesByContentAndHidesSecret() {
        val first = OtpSpec("1234".toByteArray(Charsets.US_ASCII))
        val second = OtpSpec("1234".toByteArray(Charsets.US_ASCII))

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first, first.copy())
        assertNotEquals(first, OtpSpec("1234".toByteArray(Charsets.US_ASCII), digits = 8))

        assertFalse(first.toString().contains("1234"))
    }

    /** 依次校验同一个算法在 RFC 6238 附录 B 时间点上的 8 位输出。 */
    private fun assertRfcVectors(algorithm: OtpAlgorithm, secretText: String, expected: LongArray) {
        val spec = OtpSpec(
            secret = secretText.toByteArray(Charsets.US_ASCII),
            algorithm = algorithm,
            digits = 8,
            period = 30,
        )
        rfcTimes.forEachIndexed { index, time ->
            val wanted = expected[index].toString().padStart(8, '0')
            val actual = TotpEngine.generate(spec, time)
            assertEquals("algorithm=" + algorithm + ", t=" + time, wanted, actual)
        }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("应当抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 期望行为
        }
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val hex = (byte.toInt() and 0xFF).toString(16).uppercase()
            if (hex.length == 1) builder.append('0')
            builder.append(hex)
        }
        return builder.toString()
    }
}
