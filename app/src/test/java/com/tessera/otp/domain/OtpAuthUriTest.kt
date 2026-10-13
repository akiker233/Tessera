package com.tessera.otp.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [OtpAuthUriParser] 与 [GoogleMigrationParser] 的 JVM 单元测试。
 *
 * 全部测试只用 java.net.URI 与手工解析，不依赖 android.net.Uri，因此可直接在 JVM 上运行。
 */
class OtpAuthUriTest {

    // ------------------------------------------------------------------
    // otpauth URI
    // ------------------------------------------------------------------

    @Test
    fun parsesTotpUriWithAllParameters() {
        val uri = "otpauth://totp/ACME%20Co:john@example.com" +
            "?secret=JBSWY3DPEHPK3PXP&issuer=ACME%20Co&algorithm=SHA256&digits=8&period=60"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        assertEquals("ACME Co", data.issuer)
        assertEquals("john@example.com", data.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", data.secretBase32)
        assertEquals(OtpAlgorithm.SHA256, data.algorithm)
        assertEquals(8, data.digits)
        assertEquals(60, data.period)
        assertFalse(data.isSteam)
        assertEquals("totp", data.type)
    }

    @Test
    fun parsesLabelWithoutIssuerPrefixAndWithoutIssuerParameter() {
        val uri = "otpauth://totp/john@example.com?secret=JBSWY3DPEHPK3PXP"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        assertNull(data.issuer)
        assertEquals("john@example.com", data.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", data.secretBase32)
        assertEquals(OtpAlgorithm.SHA1, data.algorithm)
        assertEquals(6, data.digits)
        assertEquals(30, data.period)
        assertFalse(data.isSteam)
        assertEquals("totp", data.type)
    }

    @Test
    fun splitsIssuerFromLabelWhenQueryHasNoIssuer() {
        val uri = "otpauth://totp/ACME%20Co:john@example.com?secret=JBSWY3DPEHPK3PXP"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        assertEquals("ACME Co", data.issuer)
        assertEquals("john@example.com", data.accountName)
    }

    @Test
    fun detectsSteamFromLabelAndNormalizesDigits() {
        val uri = "otpauth://totp/Steam:player?secret=JBSWY3DPEHPK3PXP&issuer=Steam&digits=6"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        assertTrue(data.isSteam)
        assertEquals(5, data.digits)
        assertEquals("Steam", data.issuer)
        assertEquals("player", data.accountName)
        assertEquals("totp", data.type)
    }

    @Test
    fun detectsSteamCaseInsensitivelyFromIssuerAndScheme() {
        val uri = "OTPAUTH://TOTP/player?secret=JBSWY3DPEHPK3PXP&issuer=STEAM"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        assertTrue(data.isSteam)
        assertEquals(5, data.digits)
        assertEquals("STEAM", data.issuer)
        assertEquals("player", data.accountName)
        assertEquals("totp", data.type)
    }

    @Test
    fun parsesHotpType() {
        val uri = "otpauth://hotp/john@example.com?secret=JBSWY3DPEHPK3PXP&counter=0&digits=8"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        assertEquals("hotp", data.type)
        assertEquals("john@example.com", data.accountName)
        assertEquals(8, data.digits)
        assertFalse(data.isSteam)
    }

    @Test
    fun fallsBackToDefaultsForUnknownAlgorithmOrBadNumbers() {
        val uri = "otpauth://totp/john@example.com" +
            "?secret=JBSWY3DPEHPK3PXP&algorithm=MD5&digits=99&period=abc"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        assertEquals(OtpAlgorithm.SHA1, data.algorithm)
        assertEquals(6, data.digits)
        assertEquals(30, data.period)
    }

    @Test
    fun decodesPercentEncodedUtf8LabelAndPlusInQuery() {
        val uri = "otpauth://totp/%E7%94%B2:user?secret=JBSWY3DPEHPK3PXP&issuer=ACME+Co"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        // %E7%94%B2 是汉字 甲 的 UTF-8 编码；label 前缀与 query 的 issuer 不一致时保留完整 label。
        assertEquals("ACME Co", data.issuer)
        assertEquals("甲:user", data.accountName)

        val matching = requireNotNull(
            OtpAuthUriParser.parse("otpauth://totp/%E7%94%B2:user?secret=JBSWY3DPEHPK3PXP&issuer=%E7%94%B2"),
        ) { "应当能解析带 UTF-8 issuer 的 URI" }
        assertEquals("甲", matching.issuer)
        assertEquals("user", matching.accountName)
    }

    @Test
    fun toleratesUriRejectedByJavaNetUri() {
        // label 里出现未编码的空格：java.net.URI 会抛 URISyntaxException，解析器需退回手工拆分。
        val uri = "otpauth://totp/ACME Co:john@example.com?secret=JBSWY3DPEHPK3PXP"

        val data = requireNotNull(OtpAuthUriParser.parse(uri)) { "应当能解析: " + uri }

        assertEquals("ACME Co", data.issuer)
        assertEquals("john@example.com", data.accountName)
    }

    @Test
    fun rejectsNonOtpAuthUriAndMissingSecret() {
        assertNull(OtpAuthUriParser.parse("https://example.com/?secret=JBSWY3DPEHPK3PXP"))
        assertNull(OtpAuthUriParser.parse("otpauth://totp/john@example.com"))
        assertNull(OtpAuthUriParser.parse("otpauth://foo/john@example.com?secret=JBSWY3DPEHPK3PXP"))
        assertNull(OtpAuthUriParser.parse("not a uri"))
    }

    // ------------------------------------------------------------------
    // Google Authenticator 迁移包
    // ------------------------------------------------------------------
    //
    // 下面四个样本都是按 otpauth-migration-offline.proto 手工拼出的最小合法 protobuf，
    // 并已用独立实现（Node.js 手写 varint 编码器）逐字节复算过。字节序列如下：
    //
    // 样本 A（SHA1 / 六位 / totp）：
    //   payload = 0a 0a 48656c6c6f21deadbeef                     字段1 secret(10 字节) = Hello! + DE AD BE EF
    //             12 10 6a6f686e406578616d706c652e636f6d        字段2 name = john@example.com
    //             1a 04 41434d45                                字段3 issuer = ACME
    //             20 01                                         字段4 algorithm = 1 (SHA1)
    //             28 01                                         字段5 digits = 1 (六位)
    //             30 02                                         字段6 type = 2 (totp)
    //   outer   = 0a 2a <payload>（0x2a = 42 字节）
    //   完整十六进制 = 0a2a0a0a48656c6c6f21deadbeef12106a6f686e406578616d706c652e636f6d1a0441434d45200128013002
    //
    // 样本 B（与 A 同结构，但 secret 换成 ba0dbb703e68eff9fecf，使 Base64 同时含 + 与 /）：
    //   payload = 0a 0a ba0dbb703e68eff9fecf 12 10 6a6f686e406578616d706c652e636f6d
    //             1a 04 41434d45 20 01 28 01 30 02
    //   outer   = 0a 2a <payload>
    //   完整十六进制 = 0a2a0a0aba0dbb703e68eff9fecf12106a6f686e406578616d706c652e636f6d1a0441434d45200128013002
    //
    // 样本 C（同 A，但在 payload 与 outer 中插入若干未知字段，用于验证按 wire type 跳过）：
    //   payload 追加 3a 03 78797a（字段7，length-delimited，xyz）
    //              和 4d 01020304（字段9，fixed32）
    //              和 51 0908070605040302（字段10，fixed64）
    //   outer   追加 18 01（字段3，varint）、25 aabbccdd（字段4，fixed32）、
    //              28 b424（字段5，varint 0x1234）、31 0102030405060708（字段6，fixed64）
    //   完整十六进制 = 0a3d0a0a48656c6c6f21deadbeef12106a6f686e406578616d706c652e636f6d1a0441434d4520013a0378797a28014d010203043002510908070605040302180125aabbccdd28b424310102030405060708
    //
    // 样本 D（SHA256 / 八位 / hotp）：
    //   payload = 0a 0a 48656c6c6f21deadbeef 12 10 6a6f686e406578616d706c652e636f6d
    //             1a 04 41434d45 20 02 28 02 30 01
    //   outer   = 0a 2a <payload>
    //   完整十六进制 = 0a2a0a0a48656c6c6f21deadbeef12106a6f686e406578616d706c652e636f6d1a0441434d45200228023001

    private val migrationSampleA =
        "CioKCkhlbGxvId6tvu8SEGpvaG5AZXhhbXBsZS5jb20aBEFDTUUgASgBMAI="

    private val migrationSampleB =
        "CioKCroNu3A+aO/5/s8SEGpvaG5AZXhhbXBsZS5jb20aBEFDTUUgASgBMAI="

    private val migrationSampleC =
        "Cj0KCkhlbGxvId6tvu8SEGpvaG5AZXhhbXBsZS5jb20aBEFDTUUgAToDeHl6KAFNAQIDBDACUQkIBwYFBAMC" +
            "GAElqrvM3Si0JDEBAgMEBQYHCA=="

    private val migrationSampleD =
        "CioKCkhlbGxvId6tvu8SEGpvaG5AZXhhbXBsZS5jb20aBEFDTUUgAigCMAE="

    @Test
    fun googleMigrationParsesMinimalSample() {
        val entries = GoogleMigrationParser.parse(migrationSampleA)

        assertEquals(1, entries.size)
        val entry = entries.first()
        assertEquals("ACME", entry.issuer)
        assertEquals("john@example.com", entry.accountName)
        assertEquals("JBSWY3DPEHPK3PXP", entry.secretBase32)
        assertEquals(OtpAlgorithm.SHA1, entry.algorithm)
        assertEquals(6, entry.digits)
        assertEquals("totp", entry.type)
    }

    @Test
    fun googleMigrationMapsAlgorithmDigitsAndType() {
        val entry = requireNotNull(GoogleMigrationParser.parse(migrationSampleD).firstOrNull())

        assertEquals(OtpAlgorithm.SHA256, entry.algorithm)
        assertEquals(8, entry.digits)
        assertEquals("hotp", entry.type)
        assertEquals("JBSWY3DPEHPK3PXP", entry.secretBase32)
    }

    @Test
    fun googleMigrationToleratesMissingPaddingAndLineBreaks() {
        val withoutPadding = migrationSampleA.trimEnd('=')
        assertNotEquals(migrationSampleA, withoutPadding)
        assertEquals(1, GoogleMigrationParser.parse(withoutPadding).size)

        val withLineBreaks = migrationSampleA.chunked(20).joinToString(" ")
        assertTrue(withLineBreaks.contains(' '))
        assertEquals(1, GoogleMigrationParser.parse(withLineBreaks).size)
    }

    @Test
    fun googleMigrationDecodesUrlSafeAlphabet() {
        val standard = requireNotNull(GoogleMigrationParser.parse(migrationSampleB).firstOrNull())
        assertEquals("XIG3W4B6NDX7T7WP", standard.secretBase32)
        assertEquals("ACME", standard.issuer)
        assertEquals("john@example.com", standard.accountName)
        assertEquals(OtpAlgorithm.SHA1, standard.algorithm)
        assertEquals(6, standard.digits)
        assertEquals("totp", standard.type)

        // 样本 B 的 Base64 里确实含有 + 与 /，换成 URL-safe 字母表后必须仍能解析出同一条记录。
        val urlSafeOnly = migrationSampleB.replace('+', '-').replace('/', '_')
        assertNotEquals(migrationSampleB, urlSafeOnly)
        assertEquals(standard, GoogleMigrationParser.parse(urlSafeOnly).first())
    }

    @Test
    fun googleMigrationSkipsUnknownFields() {
        // 样本 C 只是在样本 A 前后插入了未知字段，解析结果必须与样本 A 完全一致。
        val expected = GoogleMigrationParser.parse(migrationSampleA).first()
        val actual = GoogleMigrationParser.parse(migrationSampleC)

        assertEquals(1, actual.size)
        assertEquals(expected, actual.first())
    }

    @Test
    fun googleMigrationRejectsInvalidBase64() {
        assertThrowsIllegalArgument { GoogleMigrationParser.parse("@@@@not-base64@@@@") }
        assertThrowsIllegalArgument { GoogleMigrationParser.parse("   ") }
    }

    @Test
    fun googleMigrationRejectsTruncatedData() {
        // 声称 payload 长度为 42 字节（0x2a），实际只剩 6 字节。
        assertThrowsIllegalArgument { GoogleMigrationParser.parse("CioKCkhlbGxv") }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("应当抛出 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 期望行为
        }
    }
}
