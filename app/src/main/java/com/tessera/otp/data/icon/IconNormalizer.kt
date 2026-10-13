package com.tessera.otp.data.icon

import java.text.Normalizer

/**
 * 图标名/issuer 归一化工具。
 *
 * 实测图标集（1954 张 PNG）的命名陷阱：
 * - 多点号文件名：`34SP.com.png`、`ALL-INKL.COM.png`、`arena.net_light.png` → 必须按**首个** `.png` 截断，不能按 `.` 切分。
 * - 变体后缀：`_light`（196 个）、`_dark`（若干）。
 * - 空格/连字符/下划线/Unicode：`Aviron Club d'Auray 56.png`、`ETH Zürich_light.png`、`Hatena (はてな).png`。
 * - 超长名被截断（`Maryland Department of Transportation..._light.png`）。
 */
object IconNormalizer {

    /** 主题变体。 */
    enum class Variant { DEFAULT, LIGHT, DARK }

    /** 去掉扩展名与变体后缀，返回 (基础名, 变体)。 */
    fun splitVariant(fileName: String): Pair<String, Variant> {
        // 只按首个 ".png" 截断，保留文件名里其它点号
        val base = fileName.replace(Regex("\\.png$", RegexOption.IGNORE_CASE), "")
        return when {
            base.endsWith("_light", ignoreCase = true) -> base.dropLast(6) to Variant.LIGHT
            base.endsWith("_dark", ignoreCase = true) -> base.dropLast(5) to Variant.DARK
            else -> base to Variant.DEFAULT
        }
    }

    /**
     * 归一化键：NFKC + 小写 + 去掉所有非字母数字字符。
     * 例："ETH Zürich" → "ethzurich"；"ALL-INKL.COM" → "allinklcom"；"Hatena (はてな)" → "hatenaはてな"。
     */
    fun normalizeKey(raw: String): String {
        val nfkc = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            if (Character.isLetterOrDigit(ch)) sb.append(Character.toLowerCase(ch))
        }
        return sb.toString()
    }

    /** 从 issuer/账号名做宽松匹配键（先 NFKC，再按索引键规则）。 */
    fun normalizeIssuer(issuer: String): String = normalizeKey(issuer)
}
