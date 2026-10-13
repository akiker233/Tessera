package com.tessera.otp.data.icon

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 图标归一化测试。
 *
 * 用例全部取自工作区 `icons/` 里**实际存在**的文件名（实测 1954 个 PNG），
 * 用于防止回归：多点号、Unicode、变体后缀、连字符/空格/下划线。
 */
class IconNormalizerTest {

    @Test
    fun `多点号文件名只按首个 png 截断`() {
        assertEquals("34SP.com" to IconNormalizer.Variant.DEFAULT, IconNormalizer.splitVariant("34SP.com.png"))
        assertEquals("ALL-INKL.COM" to IconNormalizer.Variant.DEFAULT, IconNormalizer.splitVariant("ALL-INKL.COM.png"))
        assertEquals("arena.net" to IconNormalizer.Variant.LIGHT, IconNormalizer.splitVariant("arena.net_light.png"))
        assertEquals("cal.com" to IconNormalizer.Variant.DEFAULT, IconNormalizer.splitVariant("cal.com.png"))
    }

    @Test
    fun `变体后缀解析`() {
        assertEquals("Acronis" to IconNormalizer.Variant.LIGHT, IconNormalizer.splitVariant("Acronis_light.png"))
        assertEquals("Hetzner" to IconNormalizer.Variant.DARK, IconNormalizer.splitVariant("Hetzner_dark.png"))
        assertEquals("1Password" to IconNormalizer.Variant.DEFAULT, IconNormalizer.splitVariant("1Password.png"))
    }

    @Test
    fun `归一化忽略大小写空格连字符下划线与标点`() {
        val expected = IconNormalizer.normalizeKey("ALL-INKL.COM")
        assertEquals(expected, IconNormalizer.normalizeKey("all inkl com"))
        assertEquals(IconNormalizer.normalizeKey("1Password"), IconNormalizer.normalizeKey("1password"))

        // 下划线也是分隔符：Federal_Student_Aid 与 Federal Student Aid 应命中同一个键
        assertEquals(
            IconNormalizer.normalizeKey("Federal Student Aid"),
            IconNormalizer.normalizeKey("Federal_Student_Aid")
        )
    }

    @Test
    fun `unicode 归一化 NFKC`() {
        // ETH Zürich_light.png / Københavns Universitet / Hatena (はてな)
        // 注意：NFKC **不做**去音标（去除变音符号），ü / ø 仍是字母并保留在键里。
        // 这是刻意的：图标文件名与 issuer 用同一规则即可稳定互命中。
        assertEquals("ethzürich", IconNormalizer.normalizeKey("ETH Zürich"))
        assertEquals("københavnsuniversitet", IconNormalizer.normalizeKey("Københavns Universitet"))
        // 括号被丢弃，日文假名保留
        assertEquals("hatenaはてな", IconNormalizer.normalizeKey("Hatena (はてな)"))
        // 全角字符经 NFKC 折叠为半角
        assertEquals(IconNormalizer.normalizeKey("abc123"), IconNormalizer.normalizeKey("ＡＢＣ１２３"))
        // 大小写不敏感
        assertEquals(IconNormalizer.normalizeKey("ETH Zürich"), IconNormalizer.normalizeKey("eth zürich"))
    }

    @Test
    fun `注入到 issuer 的匹配键`() {
        // 与文件名归一化使用同一规则，保证运行时能命中索引
        assertEquals(
            IconNormalizer.normalizeKey("Google Authenticator"),
            IconNormalizer.normalizeIssuer("Google Authenticator")
        )
        assertEquals("2fas", IconNormalizer.normalizeIssuer("2FAS"))
    }
}
